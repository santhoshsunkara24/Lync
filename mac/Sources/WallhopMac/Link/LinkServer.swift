import AppKit
import Foundation
import Network

/// Something that happened on the link, delivered to the app on the main thread.
enum LinkEvent {
    case listening(port: UInt16?)
    /// The listener can't accept connections (it keeps trying by itself).
    case stopped(ListenerProblem)
    case pairingRequested(PairingRequest)
    case pairingEnded(id: UUID)
    /// A pairing request on screen ended without an answer: it expired, or the phone gave up.
    case pairingAbandoned(expired: Bool)
    /// A phone too old to pair was turned away (`pair_no` `version`).
    case phoneTooOldToPair
    /// A scanned QR didn't work (expired, used, replaced, or the wrong proof); a new one is due.
    case qrRejected(mismatch: Bool)
    /// What the connecting phone said about itself in `hello` (before `connected`).
    case phoneVersion(app: String?, protocolVersion: Int?)
    /// The phone's Material You colours (ARGB), so the Mac matches it.
    case theme(seed: UInt32, accent: UInt32?)
    /// A phone paired by scanning this Mac's QR code; no confirmation needed.
    case pairedWithQR(name: String)
    case connected(PairedPhone)
    case disconnected(PairedPhone)
    case text(String, from: PairedPhone)
    case otp(code: String, sender: String, app: String)
    case status(battery: Int, charging: Bool)
    case batchStarted(id: String, count: Int, bytes: Int64)
    /// `newTrack`: a different track or app, so old artwork no longer applies.
    case media(NowPlaying?, art: Data?, newTrack: Bool = false)
    case batchProgress(id: String, bytes: Int64)
    case fileSaved(batch: String, url: URL)
    /// `reason`: the file-ack code the Mac sent back (`storage_full`, `checksum`, …).
    case fileFailed(batch: String, name: String, reason: String)
    /// Files from the phone turned away before any were written (not enough space).
    case incomingRefused(reason: String)
    case batchFinished(id: String)
    /// The connection dropped before the batch finished.
    case batchInterrupted(id: String)
    /// The phone cancelled the send part-way.
    case batchCancelled(id: String)
    case notification(PhoneNotification, icon: Data?)
    case call(PhoneCall)
    case smsChanged
    case linkOpened(URL)
    case notificationRemoved(key: String)
    case outgoingProgress(id: String, sent: Int64, total: Int64)
    case outgoingFinished(id: String, result: OutgoingResult)
    /// The phone unpaired this Mac (`forget`); it's already removed from the paired phones.
    case forgotten(PairedPhone)
    /// The phone stopped ringing by itself (`ring` with `action` `stopped`).
    case ringStopped
    /// Codes, calls and notifications from this phone now come over Bluetooth (nil: not any more).
    case bluetooth(PairedPhone?)
    /// Bluetooth on this Mac is off, not allowed, or missing (or fine again).
    case bluetoothRadio(BluetoothRadio)
    /// The paired phones couldn't be written to disk.
    case storageFailed
}

/// Why the listener isn't accepting connections.
enum ListenerProblem: Equatable {
    /// Local Network access is off for Lync (Privacy & Security).
    case localNetworkDenied
    /// This Mac has no network.
    case offline
    case failed
}

/// How a message that needed the phone's `ack` went.
enum AckResult: Equatable {
    case ok
    /// The phone said no; `err` when it gave a code.
    case refused(String?)
    /// No answer in time (old phones don't answer some messages: callers decide what that means).
    case timedOut
    /// It couldn't be sent, or the connection dropped before the answer.
    case disconnected

    var ok: Bool { self == .ok }
}

/// The answer to `LinkServer.request`.
enum RequestResult {
    case reply(Message)
    /// No phone connected; `bluetoothOnly` when one is reachable over Bluetooth only.
    case notConnected(bluetoothOnly: Bool)
    case timedOut
    case disconnected
}

/// A notification mirrored from the phone.
struct PhoneNotification: Identifiable, Equatable {
    let key: String
    let packageName: String
    let appName: String
    let title: String
    let text: String
    let time: Date
    let canReply: Bool
    var actions: [NotificationAction] = []

    var id: String { key }
}

/// A button on a phone notification, such as "Mark as read".
struct NotificationAction: Equatable, Identifiable {
    let index: Int
    let title: String
    let isReply: Bool

    var id: Int { index }
}

/// What's playing on the phone.
struct NowPlaying: Equatable {
    let app: String
    let title: String
    let artist: String
    let playing: Bool
}

/// A phone call reported by the phone.
struct PhoneCall: Equatable {
    enum State: String { case ringing, active, ended, missed }

    let id: String
    let state: State
    let number: String?
    let name: String?
    let sim: String?

    var displayName: String { name ?? number ?? "Unknown caller" }
}

/// A phone asking to pair. `decide` may be called from any thread.
struct PairingRequest: Identifiable {
    let id: UUID
    let phoneName: String
    let code: String
    let decide: (Bool) -> Void
}

/// Listens on the local network, advertises `_wallhop._tcp` over Bonjour, and runs phone connections.
final class LinkServer {
    static let serviceType = "_wallhop._tcp"
    /// Development: WALLHOP_DEBUG=1 logs message types (never message text).
    static let debugLog = ProcessInfo.processInfo.environment["WALLHOP_DEBUG"] == "1"
    /// WALLHOP_PORT overrides it for a test copy, so a real phone never reaches that copy.
    static let preferredPort: UInt16 = ProcessInfo.processInfo.environment["WALLHOP_PORT"].flatMap(UInt16.init) ?? 47474

    /// The longest text that still fits in one frame with its header (PROTOCOL.md, Framing).
    static let maxTextBytes = Framing.maxOutgoingPayload - 16 - 2 - 1024
    /// Pairing attempts allowed per address per minute.
    static let pairAttemptsPerMinute = 5

    let identity: Identity
    let paired: PairedPhones
    let queue = DispatchQueue(label: "app.wallhop.link")
    let macName = ProcessInfo.processInfo.environment["WALLHOP_NAME"] ?? Host.current().localizedName ?? "Mac"

    /// Called on the main thread.
    var onEvent: ((LinkEvent) -> Void)?

    private var listener: NWListener?
    private var connections: [UUID: PhoneConnection] = [:]
    /// The newest established connection of each paired phone, by public key.
    private var readyByKey: [Data: PhoneConnection] = [:]
    /// The phone the app shows and talks to. Another paired phone stays connected in the
    /// background and takes over when this one leaves.
    private var active: PhoneConnection?
    private var listeningPort: UInt16?
    /// The secret in the pairing QR currently on screen. One at a time, used once, two minutes.
    private var invite: (token: Data, expires: Date)?
    /// The connection whose pairing request is waiting for the user; one at a time.
    private var pendingPairing: UUID?
    private var pairAttempts: [String: [Date]] = [:]
    /// Replies we're waiting for, with the connection they were sent on.
    private var pendingAcks: [String: (connection: UUID, callback: (AckResult) -> Void)] = [:]
    private var pendingResults: [String: (connection: UUID, callback: (RequestResult) -> Void)] = [:]
    private var senders: [String: FileSender] = [:]
    /// Holds off App Nap and idle sleep while files move.
    private let busy = TransferActivity()
    /// The fallback when no phone reaches this Mac over Wi-Fi.
    private let bluetooth: BluetoothLink
    /// Stands for the Bluetooth link in `pendingAcks`, so its acks fail when it drops.
    private let bluetoothConnectionId = UUID()
    /// A paired phone reaches this Mac over Bluetooth only.
    private var bluetoothUp = false

    init(identity: Identity, paired: PairedPhones) {
        self.identity = identity
        self.paired = paired
        bluetooth = BluetoothLink(identity: identity, paired: paired, queue: queue)
        bluetooth.onMessage = { [weak self] message, phone in self?.handleBluetooth(message, from: phone) }
        bluetooth.onState = { [weak self] phone in self?.bluetoothChanged(phone) }
        bluetooth.onRadio = { [weak self] radio in self?.emit(.bluetoothRadio(radio)) }
        paired.onSaveFailed = { [weak self] in self?.emit(.storageFailed) }
    }

    func start() {
        queue.async {
            self.startListener(port: Self.preferredPort)
            self.bluetooth.setWanted(true)
        }
    }

    /// Path selection (on the queue). The phone decides: it advertises only while its Wi-Fi link
    /// is down, so the Mac listens for it all the time (it may not have noticed the TCP connection
    /// die yet; keepalive takes most of a minute). A Wi-Fi connection from a phone ends its
    /// Bluetooth link at once.
    private func wifiReady(_ phone: PairedPhone) {
        bluetooth.setWanted(true) // A newly paired phone.
        bluetooth.disconnect(publicKey: phone.publicKey)
    }

    /// The Bluetooth link to `phone` came up (or went down: nil). Its Wi-Fi connection, if the Mac
    /// still has one, is stale: the phone only advertises once it lost it. Close it now.
    private func bluetoothChanged(_ phone: PairedPhone?) {
        bluetoothUp = phone != nil
        if phone == nil {
            for (id, pending) in pendingAcks where pending.connection == bluetoothConnectionId {
                pendingAcks[id] = nil
                pending.callback(.disconnected)
            }
        }
        if let phone {
            for connection in connections.values where connection.phone?.publicKey == phone.publicKey {
                connection.close()
            }
        }
        emit(.bluetooth(phone))
    }

    // MARK: - Listener

    private func startListener(port: UInt16?) {
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 20
        tcp.keepaliveInterval = 10
        tcp.keepaliveCount = 3
        tcp.noDelay = true
        // No allowLocalEndpointReuse: with it, a second copy could bind 47474 too, and the
        // fallback to a random port below would never kick in.
        let parameters = NWParameters(tls: nil, tcp: tcp)

        let listener: NWListener
        do {
            if let port, let nwPort = NWEndpoint.Port(rawValue: port) {
                listener = try NWListener(using: parameters, on: nwPort)
            } else {
                listener = try NWListener(using: parameters)
            }
        } catch {
            if port != nil {
                startListener(port: nil)
            } else {
                NSLog("Lync: can't listen: \(error.localizedDescription)")
                emit(.stopped((error as? NWError).map(Self.problem) ?? .failed))
            }
            return
        }

        listener.service = NWListener.Service(name: macName, type: Self.serviceType)
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.listeningPort = listener?.port?.rawValue
                self.emit(.listening(port: listener?.port?.rawValue))
            case .failed(let error):
                listener?.cancel()
                if port != nil, case .posix(.EADDRINUSE) = error {
                    self.startListener(port: nil)
                } else {
                    NSLog("Lync: listener failed: \(error.localizedDescription)")
                    self.emit(.stopped(Self.problem(error)))
                    self.queue.asyncAfter(deadline: .now() + 3) { self.startListener(port: port) }
                }
            case .waiting(let error):
                // Not dead yet: it goes on by itself once the cause is fixed (it reports ready then).
                NSLog("Lync: listener waiting: \(error.localizedDescription)")
                self.emit(.stopped(Self.problem(error)))
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] nw in
            guard let self else { nw.cancel(); return }
            let connection = PhoneConnection(connection: nw, server: self)
            self.connections[connection.id] = connection
            connection.start()
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    /// What a listener error means for the user. Local Network denied shows up as the DNS-SD
    /// PolicyDenied error (-65570) when the Bonjour service can't be registered.
    private static func problem(_ error: NWError) -> ListenerProblem {
        switch error {
        case .dns(let code) where code == -65570: return .localNetworkDenied
        case .posix(.ENETDOWN), .posix(.ENETUNREACH), .posix(.EADDRNOTAVAIL): return .offline
        default: return .failed
        }
    }

    /// Starts listening again, e.g. after the user taps Try again.
    func restart() {
        queue.async {
            self.listener?.stateUpdateHandler = nil
            self.listener?.cancel()
            self.listener = nil
            self.startListener(port: Self.preferredPort)
        }
    }

    // MARK: - QR pairing

    /// What to type on the phone when the camera can't be used: "192.168.1.20", plus ":port"
    /// when the listener isn't on the usual port. Nil until the listener is up.
    func manualAddress() -> String? {
        queue.sync {
            guard let port = listeningPort, let host = NetworkInfo.localIPv4s().first else { return nil }
            return port == Self.preferredPort ? host : "\(host):\(port)"
        }
    }

    /// A fresh pairing link for a QR code: this Mac's address, name and key plus a one-time
    /// secret. Making a new one cancels the previous one. Nil until the listener is up.
    func makeInvite() -> URL? {
        queue.sync {
            let hosts = NetworkInfo.localIPv4s()
            guard let port = listeningPort, !hosts.isEmpty else { return nil }
            var token = Data(count: 16)
            let status = token.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
            guard status == errSecSuccess else { return nil }
            invite = (token, Date().addingTimeInterval(120))
            var components = URLComponents()
            components.scheme = "wallhop"
            components.host = "pair"
            components.queryItems = [
                URLQueryItem(name: "v", value: "1"),
                URLQueryItem(name: "n", value: macName),
                // Every address, comma-separated: the phone uses whichever it can reach.
                URLQueryItem(name: "h", value: hosts.joined(separator: ",")),
                URLQueryItem(name: "p", value: String(port)),
                URLQueryItem(name: "k", value: identity.publicKey.base64URL),
                URLQueryItem(name: "t", value: token.base64URL),
            ]
            return components.url
        }
    }

    /// Stops `url`'s invite working, if it's still the current one: a QR leaving the screen must
    /// not cancel the newer QR that just replaced it.
    func cancelInvite(_ url: URL?) {
        guard let token = url.flatMap({ URLComponents(url: $0, resolvingAgainstBaseURL: false) })?
            .queryItems?.first(where: { $0.name == "t" })?.value else { return }
        queue.async { if self.invite?.token.base64URL == token { self.invite = nil } }
    }

    // MARK: - Called by PhoneConnection (on queue)

    /// Checks a phone's QR proof and uses up the secret if it matches. Otherwise the `pair_no`
    /// code: `expired` (expired, used or replaced) or `mismatch` (the proof is wrong); the app
    /// shows a fresh QR either way.
    func redeemInvite(proof: Data, phonePub: Data) -> String? {
        guard let current = invite, current.expires > Date() else {
            emit(.qrRejected(mismatch: false))
            return PhoneError.expired
        }
        let expected = Handshake.qrProof(token: current.token, phonePub: phonePub, macPub: identity.publicKey)
        guard Handshake.constantTimeEqual(expected, proof) else {
            emit(.qrRejected(mismatch: true))
            return PhoneError.mismatch
        }
        invite = nil
        return nil
    }

    /// A phone too old to pair was turned away.
    func refusedOldPhone() {
        emit(.phoneTooOldToPair)
    }

    /// At most `pairAttemptsPerMinute` pairing attempts per address per minute.
    func allowPairAttempt(from host: String) -> Bool {
        let now = Date()
        var recent = (pairAttempts[host] ?? []).filter { now.timeIntervalSince($0) < 60 }
        guard recent.count < Self.pairAttemptsPerMinute else { return false }
        recent.append(now)
        pairAttempts[host] = recent
        pairAttempts = pairAttempts.filter { !$0.value.isEmpty && now.timeIntervalSince($0.value.last!) < 60 }
        return true
    }

    /// Claims the single pairing slot. False while another phone's request is pending.
    func beginPairing(_ connection: PhoneConnection) -> Bool {
        if let pending = pendingPairing, connections[pending] != nil { return false }
        pendingPairing = connection.id
        return true
    }

    func endPairing(_ connection: PhoneConnection) {
        if pendingPairing == connection.id { pendingPairing = nil }
    }

    func pairedWithQR(name: String) {
        emit(.pairedWithQR(name: name))
    }

    func requestPairing(id: UUID, phoneName: String, code: String, decision: @escaping (Bool) -> Void) {
        let decideOnce = Once<Bool> { [weak self] accepted in
            self?.queue.async {
                decision(accepted)
                self?.emit(.pairingEnded(id: id))
            }
        }
        if ProcessInfo.processInfo.environment["WALLHOP_AUTO_PAIR"] == "1" {
            decideOnce.call(true)
            return
        }
        emit(.pairingRequested(PairingRequest(id: id, phoneName: phoneName, code: code, decide: decideOnce.call)))
    }

    func connectionReady(_ connection: PhoneConnection, phone: PairedPhone) {
        let key = phone.publicKey
        let old = readyByKey[key]
        readyByKey[key] = connection
        // The same phone reconnecting replaces its old connection.
        if let old, old !== connection {
            if active === old {
                active = connection
                retire(old)
                old.close()
                emitConnected(connection, phone)
                wifiReady(phone)
                return
            }
            old.close()
        }
        // Another phone that's still connected keeps the app; this one waits its turn.
        if let current = active, current !== connection {
            wifiReady(phone)
            return
        }
        active = connection
        emitConnected(connection, phone)
        wifiReady(phone)
    }

    /// `connected`, after what the phone said about its version.
    private func emitConnected(_ connection: PhoneConnection, _ phone: PairedPhone) {
        emit(.phoneVersion(app: connection.phoneApp, protocolVersion: connection.phoneProtocol))
        emit(.connected(phone))
    }

    func connectionClosed(_ connection: PhoneConnection) {
        connections[connection.id] = nil
        endPairing(connection)
        if let id = connection.pairingId {
            // Still waiting for the user: it ran out of time, or the phone gave up.
            emit(.pairingEnded(id: id))
            emit(.pairingAbandoned(expired: connection.pairingTimedOut))
        }
        if let key = connection.phone?.publicKey, readyByKey[key] === connection { readyByKey[key] = nil }
        retire(connection)
        guard active === connection else { return }
        active = nil
        if let phone = connection.phone { emit(.disconnected(phone)) }
        // Hand over to another paired phone that's still connected.
        if let next = readyByKey.values.first(where: { $0.state == .ready }), let phone = next.phone {
            active = next
            emitConnected(next, phone)
        }
    }

    /// Fails everything still waiting on a connection that's going away.
    private func retire(_ connection: PhoneConnection) {
        for batch in connection.files.cancelAll() {
            busy.end("in:\(batch)")
            emit(.batchInterrupted(id: batch))
        }
        for (id, pending) in pendingAcks where pending.connection == connection.id {
            pendingAcks[id] = nil
            pending.callback(.disconnected)
        }
        for (id, pending) in pendingResults where pending.connection == connection.id {
            pendingResults[id] = nil
            pending.callback(.disconnected)
        }
    }

    /// Public keys of paired phones with a working connection (the active one and any waiting).
    func connectedPhoneKeys() -> Set<Data> {
        queue.sync { Set(readyByKey.filter { $0.value.state == .ready }.keys) }
    }

    /// Makes another connected paired phone the one the app shows and talks to.
    func makeActive(publicKey: Data) {
        queue.async {
            guard let next = self.readyByKey[publicKey], next.state == .ready, next !== self.active,
                  let phone = next.phone else { return }
            let previous = self.active?.phone
            self.active = next
            if let previous { self.emit(.disconnected(previous)) }
            self.emitConnected(next, phone)
        }
    }

    /// Drops the connection of a phone that was just forgotten.
    func disconnect(publicKey: Data) {
        queue.async {
            for connection in self.connections.values where connection.phone?.publicKey == publicKey {
                connection.close()
            }
            self.bluetooth.disconnect(publicKey: publicKey)
        }
    }

    func handle(_ message: Message, from connection: PhoneConnection) {
        if Self.debugLog { NSLog("Lync: ← \(message.type) \(message.header.filter { $0.key != "t" && $0.key != "text" })") }
        guard let phone = connection.phone else { return }
        // A paired phone waiting in the background still delivers codes, texts, links and files;
        // its status, media, calls and notifications would mix with the active phone's.
        if connection !== active {
            let background: Set<String> = ["ping", "text", "otp", "open_url", "ack", "forget",
                                           "batch_start", "file_start", "file_chunk", "file_end", "batch_end", "batch_cancel"]
            guard background.contains(message.type) else { return }
        }
        let files = connection.files
        switch message.type {
        case "ping":
            connection.send(Message("pong"))

        case "text":
            emit(.text(message.bodyText, from: phone))
            ack(message, on: connection)

        case "otp":
            if let code = message.string("code"), !code.isEmpty {
                emit(.otp(code: code, sender: message.string("sender") ?? "", app: message.string("app") ?? ""))
            }
            ack(message, on: connection)

        case "status":
            emit(.status(battery: message.int("battery") ?? -1, charging: message.bool("charging") ?? false))

        case "batch_start":
            if let batch = message.string("batch") {
                let bytes = Int64(message.int("bytes") ?? 0)
                // Not enough room: every file of it is refused (`storage_full`) and nothing is written.
                if !ReceivedFiles.hasRoom(for: bytes) {
                    files.refuseBatch(batch, reason: PhoneError.storageFull)
                    emit(.incomingRefused(reason: PhoneError.storageFull))
                    break
                }
                files.startBatch(batch)
                busy.begin("in:\(batch)")
                emit(.batchStarted(id: batch, count: message.int("count") ?? 1, bytes: bytes))
            }

        case "file_start":
            files.begin(message)

        case "file_chunk":
            if let (batch, bytes) = files.append(message) {
                emit(.batchProgress(id: batch, bytes: bytes))
            }

        case "file_end":
            let result = files.finish(message)
            switch result {
            case .saved(let batch, let url):
                emit(.fileSaved(batch: batch, url: url))
                connection.send(Message("ack", ["id": message.string("id") ?? "", "ok": true]))
            case .failed(let batch, let name, let reason):
                emit(.fileFailed(batch: batch, name: name, reason: reason))
                connection.send(Message("ack", ["id": message.string("id") ?? "", "ok": false, "err": reason]))
            case .refused(let reason):
                connection.send(Message("ack", ["id": message.string("id") ?? "", "ok": false, "err": reason]))
            }

        case "batch_cancel":
            if let batch = message.string("batch") {
                files.cancelBatch(batch)
                busy.end("in:\(batch)")
                emit(.batchCancelled(id: batch))
            }

        case "batch_end":
            if let batch = message.string("batch") {
                busy.end("in:\(batch)")
                // A refused batch already said so when it started.
                if files.endBatch(batch) { emit(.batchFinished(id: batch)) }
            }

        case "notif":
            if let notification = Self.notification(from: message) {
                emit(.notification(notification, icon: message.body.isEmpty ? nil : message.body))
            }

        case "media":
            if message.bool("active") == true {
                let nowPlaying = NowPlaying(app: message.string("app") ?? "", title: message.string("title") ?? "",
                                            artist: message.string("artist") ?? "", playing: message.bool("playing") ?? false)
                emit(.media(nowPlaying, art: message.bool("art") == true && !message.body.isEmpty ? message.body : nil,
                            newTrack: message.bool("new_track") ?? false))
            } else {
                emit(.media(nil, art: nil, newTrack: true))
            }

        case "call":
            if let call = Self.call(from: message) { emit(.call(call)) }

        case "sms_result":
            if let id = message.string("id"), let pending = pendingResults.removeValue(forKey: id) {
                pending.callback(.reply(message))
            }

        case "open_url":
            if let raw = message.string("url"), let url = URL(string: raw),
               let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                DispatchQueue.main.async { NSWorkspace.shared.open(url) }
                emit(.linkOpened(url))
                ack(message, on: connection)
            }

        case "theme":
            if let seed = (message.header["p"] as? NSNumber)?.int64Value {
                // Not "t": that's the message type.
                let accent = (message.header["a"] as? NSNumber)?.int64Value
                emit(.theme(seed: UInt32(truncatingIfNeeded: seed), accent: accent.map { UInt32(truncatingIfNeeded: $0) }))
            }

        case "sms_changed":
            emit(.smsChanged)

        case "notif_removed":
            if let key = message.string("key") { emit(.notificationRemoved(key: key)) }

        case "ack":
            if let id = message.string("id"), let pending = pendingAcks.removeValue(forKey: id) {
                pending.callback(Self.ackResult(message))
            }

        case "forget":
            // Forget on the phone, sent over this authenticated session just before it
            // disconnects: drop the pairing here too, like Forget in Settings.
            paired.remove(id: phone.id)
            emit(.forgotten(phone))
            connection.close()

        case "ring":
            if message.string("action") == "stopped" { emit(.ringStopped) }

        default:
            break // Unknown types are ignored so newer phones keep working.
        }
    }

    private static func notification(from message: Message) -> PhoneNotification? {
        guard let key = message.string("key"), let pkg = message.string("pkg") else { return nil }
        let time = (message.header["time"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) } ?? Date()
        let actions = (message.header["actions"] as? [[String: Any]] ?? []).compactMap { item -> NotificationAction? in
            guard let index = (item["i"] as? NSNumber)?.intValue, let title = item["title"] as? String else { return nil }
            return NotificationAction(index: index, title: title, isReply: (item["reply"] as? NSNumber)?.boolValue ?? false)
        }
        return PhoneNotification(
            key: key, packageName: pkg, appName: message.string("app") ?? pkg,
            title: message.string("title") ?? "", text: message.string("text") ?? "",
            time: time, canReply: message.bool("reply") ?? false, actions: actions
        )
    }

    private static func call(from message: Message) -> PhoneCall? {
        guard let id = message.string("id"), let state = PhoneCall.State(rawValue: message.string("state") ?? "") else { return nil }
        return PhoneCall(id: id, state: state, number: message.string("number"), name: message.string("name"), sim: message.string("sim"))
    }

    /// A message over the Bluetooth fallback (on the queue). Only the small things it carries;
    /// anything else is ignored, so Bluetooth can't do more than codes, calls and notifications.
    private func handleBluetooth(_ message: Message, from phone: PairedPhone) {
        if Self.debugLog { NSLog("Lync: ← (Bluetooth) \(message.type)") }
        switch message.type {
        case "otp":
            if let code = message.string("code"), !code.isEmpty {
                emit(.otp(code: code, sender: message.string("sender") ?? "", app: message.string("app") ?? ""))
            }
            if let id = message.string("id") { bluetooth.send(Message("ack", ["id": id, "ok": true])) }
        case "call":
            if let call = Self.call(from: message) { emit(.call(call)) }
        case "notif":
            // No icon over Bluetooth; the Mac uses one it already has.
            if let notification = Self.notification(from: message) { emit(.notification(notification, icon: nil)) }
        case "notif_removed":
            if let key = message.string("key") { emit(.notificationRemoved(key: key)) }
        case "ack":
            // Call buttons answered over Bluetooth.
            if let id = message.string("id"), let pending = pendingAcks.removeValue(forKey: id) {
                pending.callback(Self.ackResult(message))
            }
        default:
            break
        }
    }

    /// An `ack`: `ok` (true when missing, as before), with the phone's `err` code when it said no.
    private static func ackResult(_ message: Message) -> AckResult {
        (message.bool("ok") ?? true) ? .ok : .refused(message.string("err"))
    }

    // MARK: - Sending to the phone

    var isConnected: Bool {
        queue.sync { active != nil }
    }

    /// Sends text to the connected phone. `completion` runs on the main thread.
    func sendText(_ text: String, completion: @escaping (AckResult) -> Void) {
        sendAcked(Message("text", body: Data(text.utf8)), timeout: 10, completion: completion)
    }

    /// Replies through the notification's own quick-reply action on the phone.
    func replyToNotification(key: String, text: String, completion: @escaping (AckResult) -> Void) {
        sendAcked(Message("notif_reply", ["key": key], body: Data(text.utf8)), timeout: 10, completion: completion)
    }

    /// Taps a notification's action button on the phone. Phones before the `notif_action` ack
    /// don't answer: `timedOut` then means "sent, outcome unknown".
    func performNotificationAction(key: String, index: Int, completion: @escaping (AckResult) -> Void) {
        sendAcked(Message("notif_action", ["key": key, "i": index]), timeout: 5, completion: completion)
    }

    /// Asks the phone something and waits for its `sms_result` reply. `completion` runs on the main thread.
    func request(_ type: String, _ fields: [String: Any] = [:], timeout: TimeInterval = 15,
                 completion: @escaping (RequestResult) -> Void) {
        queue.async {
            guard let active = self.active else {
                let bluetoothOnly = self.bluetoothUp
                DispatchQueue.main.async { completion(.notConnected(bluetoothOnly: bluetoothOnly)) }
                return
            }
            let id = UUID().uuidString
            var header = fields
            header["id"] = id
            self.pendingResults[id] = (active.id, { result in DispatchQueue.main.async { completion(result) } })
            active.send(Message(type, header)) { sent in
                if !sent, let pending = self.pendingResults.removeValue(forKey: id) { pending.callback(.disconnected) }
            }
            self.queue.asyncAfter(deadline: .now() + timeout) {
                if let pending = self.pendingResults.removeValue(forKey: id) { pending.callback(.timedOut) }
            }
        }
    }

    /// Sends a text from the phone. `completion` runs on the main thread with the phone's ack.
    func sendSMS(to address: String, text: String, sim: Int?, completion: @escaping (AckResult) -> Void) {
        var header: [String: Any] = ["address": address]
        if let sim { header["sim"] = sim }
        sendAcked(Message("sms_send", header, body: Data(text.utf8)), timeout: 20, completion: completion)
    }

    /// Offers a link on the phone (it shows as a notification to tap). Phones that don't ack
    /// `open_url` time out: the link was sent, the outcome is unknown.
    func openOnPhone(_ url: URL, completion: @escaping (AckResult) -> Void) {
        sendAcked(Message("open_url", ["url": url.absoluteString]), timeout: 3, completion: completion)
    }

    /// Starts or stops the phone ringing so it can be found. `completion` (main thread): whether it was sent.
    func ringPhone(_ start: Bool, completion: @escaping (Bool) -> Void) {
        send(Message("ring", ["action": start ? "start" : "stop"]), completion: completion)
    }

    /// Sends a message that needs no reply. Dropped when no phone is connected.
    func send(_ message: Message) {
        queue.async { self.active?.send(message) }
    }

    /// Sends a message that needs no reply; `completion` (main thread) says whether it went out.
    private func send(_ message: Message, completion: @escaping (Bool) -> Void) {
        queue.async {
            guard let active = self.active else {
                DispatchQueue.main.async { completion(false) }
                return
            }
            active.send(message) { sent in DispatchQueue.main.async { completion(sent) } }
        }
    }

    /// Play, pause, skip or change volume on the phone.
    func sendMediaCommand(_ action: String) {
        queue.async { self.active?.send(Message("media_cmd", ["action": action])) }
    }

    /// Answers, declines or ends the call on the phone; over Bluetooth if that's how the call came.
    /// Newer phones ack within 3 s; older ones never do, so `timedOut` means "sent, outcome
    /// unknown", not failed. `completion` runs on the main thread.
    func sendCallCommand(_ action: String, completion: @escaping (AckResult) -> Void) {
        queue.async {
            let done: (AckResult) -> Void = { result in DispatchQueue.main.async { completion(result) } }
            if let active = self.active {
                self.expectAck(on: active.id, message: Message("call_cmd", ["action": action]), timeout: 3,
                               send: { message, sent in active.send(message, completion: sent) }, completion: done)
            } else {
                self.expectAck(on: self.bluetoothConnectionId, message: Message("call_cmd", ["action": action]), timeout: 3,
                               send: { message, sent in sent(self.bluetooth.send(message)) }, completion: done)
            }
        }
    }

    /// Re-announces this Mac on the network, for example after waking, so the phone reconnects fast.
    func refreshAdvertising() {
        queue.async {
            guard let listener = self.listener else { return }
            listener.service = nil
            listener.service = NWListener.Service(name: self.macName, type: Self.serviceType)
        }
    }

    /// Clears a notification on the phone. `completion` (main thread): whether it was sent.
    func dismissNotification(key: String, completion: @escaping (Bool) -> Void = { _ in }) {
        send(Message("notif_dismiss", ["key": key]), completion: completion)
    }

    /// Streams files to the connected phone. Check they can be read first (`FileAccess`): the
    /// result reports any that can't. Nil when nothing was given.
    func sendFiles(_ urls: [URL]) -> String? {
        guard !urls.isEmpty else { return nil }
        let batch = UUID().uuidString
        queue.async {
            guard let active = self.active else {
                let failure: Problem.SendFailure = .disconnected
                self.emit(.outgoingFinished(id: batch, result: OutgoingResult(total: urls.count, failed: urls.map { ($0, failure) })))
                return
            }
            let sender = FileSender(batch: batch, urls: urls, connection: active, queue: self.queue) { event in
                // The sender is released here, which also breaks the server ↔ sender reference.
                switch event {
                case .progress(let sent, let total): self.emit(.outgoingProgress(id: batch, sent: sent, total: total))
                case .finished(let result):
                    self.senders[batch] = nil
                    self.busy.end("out:\(batch)")
                    self.emit(.outgoingFinished(id: batch, result: result))
                }
            } registerAck: { id, callback in
                self.pendingAcks[id] = (active.id, callback)
            }
            self.senders[batch] = sender
            self.busy.begin("out:\(batch)")
            sender.start()
        }
        return batch
    }

    // MARK: - Helpers

    /// Sends a message with a fresh `id` to the active phone and waits for its ack.
    /// `completion` runs on the main thread.
    private func sendAcked(_ message: Message, timeout: TimeInterval, completion: @escaping (AckResult) -> Void) {
        queue.async {
            guard let active = self.active else {
                DispatchQueue.main.async { completion(.disconnected) }
                return
            }
            self.expectAck(on: active.id, message: message, timeout: timeout,
                           send: { message, sent in active.send(message, completion: sent) }) { result in
                DispatchQueue.main.async { completion(result) }
            }
        }
    }

    /// Sends a message with a fresh `id` and calls back (on the queue) with the phone's ack:
    /// `disconnected` if it can't be sent or the connection drops, `timedOut` if no ack comes in time.
    private func expectAck(on connection: UUID, message: Message, timeout: TimeInterval,
                           send: (Message, @escaping (Bool) -> Void) -> Void,
                           completion: @escaping (AckResult) -> Void) {
        let id = UUID().uuidString
        var message = message
        message.header["id"] = id
        pendingAcks[id] = (connection, completion)
        send(message) { sent in
            self.queue.async {
                if !sent, let pending = self.pendingAcks.removeValue(forKey: id) { pending.callback(.disconnected) }
            }
        }
        queue.asyncAfter(deadline: .now() + timeout) {
            if let pending = self.pendingAcks.removeValue(forKey: id) { pending.callback(.timedOut) }
        }
    }

    private func ack(_ message: Message, on connection: PhoneConnection) {
        guard let id = message.string("id") else { return }
        connection.send(Message("ack", ["id": id, "ok": true]))
    }

    private func emit(_ event: LinkEvent) {
        DispatchQueue.main.async { self.onEvent?(event) }
    }
}

/// Runs a callback at most once, from any thread.
final class Once<Value> {
    private let lock = NSLock()
    private var callback: ((Value) -> Void)?

    init(_ callback: @escaping (Value) -> Void) {
        self.callback = callback
    }

    func call(_ value: Value) {
        lock.lock()
        let callback = self.callback
        self.callback = nil
        lock.unlock()
        callback?(value)
    }
}
