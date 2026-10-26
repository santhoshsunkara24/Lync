import AppKit
import CoreBluetooth
import CryptoKit
import Foundation
import os

/// Whether this Mac's Bluetooth can carry the fallback.
enum BluetoothRadio: Equatable {
    case ready
    case off
    /// Lync isn't allowed to use Bluetooth (Privacy & Security › Bluetooth).
    case denied
    case unsupported
}

/// The Bluetooth LE fallback (docs/PROTOCOL.md, Bluetooth fallback). While no phone is connected
/// over Wi-Fi, scans for a paired phone's advertisement, connects and runs the handshake; then
/// codes, calls and notifications arrive here instead. Everything runs on the server's queue.
final class BluetoothLink: NSObject {
    private let identity: Identity
    private let paired: PairedPhones
    private let queue: DispatchQueue

    /// A sealed message from the phone, after the handshake. Called on the queue.
    var onMessage: ((Message, PairedPhone) -> Void)?
    /// The link to a phone came up (phone) or went down (nil). Called on the queue.
    var onState: ((PairedPhone?) -> Void)?
    /// Bluetooth turned off or on, or was refused. Called on the queue.
    var onRadio: ((BluetoothRadio) -> Void)?

    private var central: CBCentralManager?
    private var wanted = false
    private var link: Link?
    /// Tag keys per paired phone, worked out once (a DH each).
    private var tagKeys: [Data: SymmetricKey] = [:]

    private static let serviceUUID = CBUUID(string: BleWire.service)
    private static let characteristicUUID = CBUUID(string: BleWire.characteristic)
    /// From finding the phone to `ready`.
    private static let handshakeTimeout: TimeInterval = 15
    /// Same rhythm as the Wi-Fi session: a ping every 15 s; 45 s without a word is a dead link.
    private static let pingEvery: TimeInterval = 15
    private static let deadAfter: TimeInterval = 45
    /// After waking, the phone gets this long to answer before the link is treated as stale.
    private static let wakeCheck: TimeInterval = 6
    /// While there's no link, how often to look again (a fresh scan, and devices already connected).
    private static let lookEvery: TimeInterval = 10
    /// Readable in Console (`subsystem:app.wallhop.mac category:bluetooth`); nothing secret is logged.
    private static let log = Logger(subsystem: "app.wallhop.mac", category: "bluetooth")
    private var watchdog: DispatchSourceTimer?

    init(identity: Identity, paired: PairedPhones, queue: DispatchQueue) {
        self.identity = identity
        self.paired = paired
        self.queue = queue
        super.init()
        // A link the Mac slept with can stay up on the radio and carry nothing. Check it on wake.
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification,
                                                          object: nil, queue: nil) { [weak self] _ in
            self?.queue.async { self?.checkAfterWake() }
        }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.lookEvery, repeating: Self.lookEvery)
        timer.setEventHandler { [weak self] in self?.lookAgain() }
        watchdog = timer
        timer.resume()
    }

    /// Without a link, keep looking: restart the scan (macOS can stop it across sleep while still
    /// saying it scans), and try a paired phone that's already connected at the system level,
    /// whose advertisements macOS may no longer report.
    private func lookAgain() {
        guard wanted, let central, central.state == .poweredOn else { return }
        if let link {
            Self.log.info("link: \(String(describing: link.stage), privacy: .public)")
            return
        }
        central.stopScan()
        scan()
        if let peripheral = central.retrieveConnectedPeripherals(withServices: [Self.serviceUUID]).first {
            Self.log.notice("phone already connected at system level, opening a link")
            begin(with: peripheral, phone: nil)
        }
    }

    private func checkAfterWake() {
        guard let link, link.stage == .open else { return }
        let woke = DispatchTime.now()
        send(Message("ping"))
        queue.asyncAfter(deadline: .now() + Self.wakeCheck) { [weak self, weak link] in
            guard let self, let link, self.link === link, link.lastHeard < woke else { return }
            Self.log.notice("link silent after wake, dropping")
            self.drop(link)
        }
    }

    private func startHeartbeat(on link: Link) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.pingEvery, repeating: Self.pingEvery)
        timer.setEventHandler { [weak self, weak link] in
            guard let self, let link, self.link === link else { return }
            let silent = Double(DispatchTime.now().uptimeNanoseconds - link.lastHeard.uptimeNanoseconds) / 1e9
            if silent > Self.deadAfter {
                Self.log.notice("link silent for \(Int(silent), privacy: .public) s, dropping")
                self.drop(link)
            } else {
                self.send(Message("ping"))
            }
        }
        link.heartbeat = timer
        timer.resume()
    }

    /// One connection to a phone, from connecting to ready.
    private final class Link {
        enum Stage { case connecting, hello, awaitingReady, open }

        let peripheral: CBPeripheral
        /// Known from the advertised tag; for a phone found already connected, from its hello_ok.
        var phone: PairedPhone?
        var stage = Stage.connecting
        var characteristic: CBCharacteristic?
        var reassembler = BleWire.Reassembler()
        var ephemeral: Curve25519.KeyAgreement.PrivateKey?
        var channel: SecureChannel?
        var timeout: DispatchWorkItem?
        var heartbeat: DispatchSourceTimer?
        /// When the phone last sent anything.
        var lastHeard = DispatchTime.now()

        init(peripheral: CBPeripheral, phone: PairedPhone?) {
            self.peripheral = peripheral
            self.phone = phone
        }
    }

    // MARK: - On and off (on the queue)

    /// Scans while `on` (there's a paired phone); off drops any Bluetooth link.
    func setWanted(_ on: Bool) {
        let on = on && !paired.all.isEmpty
        guard on != wanted else { return }
        wanted = on
        if on {
            // Created only when needed: that's when macOS asks the user about Bluetooth.
            if central == nil {
                central = CBCentralManager(delegate: self, queue: queue,
                                           options: [CBCentralManagerOptionShowPowerAlertKey: false])
            }
            scan()
        } else {
            central?.stopScan()
            if let link { drop(link) }
        }
    }

    /// Drops the link if it's to this phone (it was just forgotten).
    func disconnect(publicKey: Data) {
        tagKeys[publicKey] = nil
        if let link, link.phone?.publicKey == publicKey { drop(link) }
    }

    /// Sends a message to the phone over Bluetooth. False if there's no open link.
    @discardableResult
    func send(_ message: Message) -> Bool {
        guard let link, link.stage == .open, let channel = link.channel, central?.state == .poweredOn,
              let sealed = try? channel.seal(message.encoded()) else { return false }
        write(sealed, on: link)
        return true
    }

    private func scan() {
        guard wanted, link == nil, let central, central.state == .poweredOn, !central.isScanning else { return }
        // Duplicates: the tag sits in the scan response, which can arrive after the first sighting.
        central.scanForPeripherals(withServices: [Self.serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    private func drop(_ link: Link) {
        guard self.link === link else { return }
        self.link = nil
        link.timeout?.cancel()
        link.heartbeat?.cancel()
        link.heartbeat = nil
        let wasOpen = link.stage == .open
        Self.log.notice("link dropped at \(String(describing: link.stage), privacy: .public)")
        if let central, central.state == .poweredOn { central.cancelPeripheralConnection(link.peripheral) }
        if wasOpen { onState?(nil) }
        // Look again in a moment (the phone restarts advertising once the link is gone).
        queue.asyncAfter(deadline: .now() + 1) { [weak self] in self?.scan() }
    }

    /// The paired phone whose tag this is, for the current 15-minute window or either neighbour.
    private func phone(forTag tag: Data) -> PairedPhone? {
        guard tag.count == BleWire.tagSize else { return nil }
        let window = BleWire.window(Date())
        for phone in paired.all {
            guard let key = tagKey(for: phone) else { continue }
            for w in [window, window - 1, window + 1] where Handshake.constantTimeEqual(BleWire.tag(key: key, window: w), tag) {
                return phone
            }
        }
        return nil
    }

    private func tagKey(for phone: PairedPhone) -> SymmetricKey? {
        if let key = tagKeys[phone.publicKey] { return key }
        guard let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: phone.publicKey),
              let shared = try? identity.privateKey.sharedSecretFromKeyAgreement(with: pub) else { return nil }
        let key = BleWire.tagKey(staticShared: shared.data, phonePub: phone.publicKey, macPub: identity.publicKey)
        tagKeys[phone.publicKey] = key
        return key
    }

    /// Connects and runs the handshake with `peripheral` (the phone, if known from its tag).
    private func begin(with peripheral: CBPeripheral, phone: PairedPhone?) {
        guard link == nil, let central else { return }
        central.stopScan()
        let link = Link(peripheral: peripheral, phone: phone)
        self.link = link
        let timeout = DispatchWorkItem { [weak self, weak link] in
            guard let self, let link, link.stage != .open else { return }
            Self.log.notice("handshake timed out")
            self.drop(link)
        }
        link.timeout = timeout
        queue.asyncAfter(deadline: .now() + Self.handshakeTimeout, execute: timeout)
        peripheral.delegate = self
        central.connect(peripheral)
    }

    // MARK: - Frames

    private func write(_ frame: Data, on link: Link) {
        guard let characteristic = link.characteristic, central?.state == .poweredOn else { return }
        // Each piece fits one write; the phone doesn't take long (prepared) writes.
        let size = max(link.peripheral.maximumWriteValueLength(for: .withoutResponse), 20)
        for piece in BleWire.fragment(frame, maxChunk: size) {
            link.peripheral.writeValue(piece, for: characteristic, type: .withResponse)
        }
    }

    private func sendHello(on link: Link) {
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        link.ephemeral = ephemeral
        link.stage = .hello
        let hello: [String: Any] = ["t": "hello", "v": 1, "pub": identity.publicKey.base64EncodedString(),
                                    "eph": ephemeral.publicKey.rawRepresentation.base64EncodedString()]
        guard let data = try? HandshakeJSON.encode(hello) else { return drop(link) }
        write(data, on: link)
    }

    private func handle(_ frame: Data, on link: Link) throws {
        switch link.stage {
        case .connecting:
            throw BleWire.FrameError.bad
        case .hello:
            // hello_ok: it must be the phone whose tag we followed.
            guard let reply = HandshakeJSON.decode(frame), reply["t"] as? String == "hello_ok",
                  let pub = (reply["pub"] as? String).flatMap({ Data(base64Encoded: $0) }),
                  let eph = (reply["eph"] as? String).flatMap({ Data(base64Encoded: $0) }), eph.count == 32,
                  // The phone whose tag we followed, or, for one found already connected, any paired phone.
                  let phone = link.phone.flatMap({ Handshake.constantTimeEqual(pub, $0.publicKey) ? $0 : nil })
                      ?? (link.phone == nil ? paired.all.first(where: { Handshake.constantTimeEqual(pub, $0.publicKey) }) : nil),
                  let ephemeral = link.ephemeral else { throw BleWire.FrameError.bad }
            link.phone = phone
            let ikm = try BleWire.macIkm(macStatic: identity.privateKey, macEphemeral: ephemeral, phonePub: pub, phoneEph: eph)
            let keys = BleWire.sessionKeys(ikm: ikm, phonePub: pub, phoneEph: eph, macPub: identity.publicKey,
                                           macEph: ephemeral.publicKey.rawRepresentation)
            let channel = SecureChannel(sendKey: keys.macToPhone, receiveKey: keys.phoneToMac)
            link.channel = channel
            link.ephemeral = nil
            link.stage = .awaitingReady
            write(try channel.seal(Message("ready").encoded()), on: link)
        case .awaitingReady:
            // The phone's ready decrypted: it holds the paired phone's key.
            guard let channel = link.channel, try Message.decode(channel.open(frame)).type == "ready" else {
                throw BleWire.FrameError.bad
            }
            link.stage = .open
            link.timeout?.cancel()
            startHeartbeat(on: link)
            Self.log.notice("link up")
            onState?(link.phone)
        case .open:
            guard let channel = link.channel else { throw BleWire.FrameError.bad }
            let message = try Message.decode(channel.open(frame))
            if message.type == "ping" {
                send(Message("pong"))
            } else if message.type == "pong" {
                // Only for lastHeard.
            } else if let phone = link.phone {
                onMessage?(message, phone)
            }
        }
    }
}

extension BluetoothLink: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: onRadio?(.ready)
        case .poweredOff: onRadio?(.off)
        case .unauthorized: onRadio?(.denied)
        case .unsupported: onRadio?(.unsupported)
        default: break // Unknown or resetting: wait for the next update.
        }
        if central.state == .poweredOn {
            scan()
        } else if let link {
            drop(link)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard wanted, link == nil,
              let tag = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[Self.serviceUUID],
              let phone = phone(forTag: tag) else { return }
        // Only a paired phone's tag gets a connection; anything else is never contacted.
        Self.log.notice("found the phone, connecting")
        begin(with: peripheral, phone: phone)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let link, link.peripheral === peripheral else { return }
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if let link, link.peripheral === peripheral { drop(link) }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if let link, link.peripheral === peripheral { drop(link) }
    }
}

extension BluetoothLink: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let link, link.peripheral === peripheral else { return }
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else { return drop(link) }
        peripheral.discoverCharacteristics([Self.characteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let link, link.peripheral === peripheral else { return }
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == Self.characteristicUUID }) else {
            return drop(link)
        }
        link.characteristic = characteristic
        // Still subscribed from a radio link that never went down: macOS wouldn't write the
        // subscription again or confirm it, so the hello would never go. Unsubscribe first.
        if characteristic.isNotifying {
            peripheral.setNotifyValue(false, for: characteristic)
        } else {
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard let link, link.peripheral === peripheral else { return }
        guard error == nil else { return drop(link) }
        guard link.stage == .connecting else { return }
        // Subscribed: the phone can answer now, so say hello. Unsubscribed (see above): subscribe.
        if characteristic.isNotifying { sendHello(on: link) } else { peripheral.setNotifyValue(true, for: characteristic) }
    }

    /// The phone's Bluetooth server restarted, so the service and characteristic changed: start over.
    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard let link, link.peripheral === peripheral else { return }
        Self.log.notice("phone's services changed, dropping")
        drop(link)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let link, link.peripheral === peripheral, let value = characteristic.value else { return }
        link.lastHeard = .now()
        do {
            if let frame = try link.reassembler.add(value) { try handle(frame, on: link) }
        } catch {
            Self.log.notice("bad frame, dropping: \(String(describing: error), privacy: .public)")
            drop(link)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if error != nil, let link, link.peripheral === peripheral { drop(link) }
    }
}
