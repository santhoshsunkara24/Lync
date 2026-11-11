import CoreBluetooth
import Foundation
import os

/// Makes this Mac findable over Bluetooth for a minute, when the phone asks
/// ("companion_beacon"), so Android can link Lync to it as the Mac's companion app. From
/// Android 17 that's what lets texts with codes in the WebOTP format ("… @example.com #123456")
/// through at once instead of three hours later. It advertises only a fixed service ID and the
/// Mac's name; nothing connects to it.
final class CompanionBeacon: NSObject, CBPeripheralManagerDelegate {
    /// The phone's `Companion.BEACON`; not the Bluetooth fallback's service.
    static let service = CBUUID(string: "6C796E63-0002-4B1E-9C3A-5D2F7E8A1B40")
    private static let log = Logger(subsystem: "app.wallhop.mac", category: "bluetooth")
    private static let duration: TimeInterval = 60

    private let queue = DispatchQueue(label: "app.wallhop.beacon")
    private var manager: CBPeripheralManager?
    private var stopWork: DispatchWorkItem?
    private var wanted = false

    /// Starts (or extends) a minute of advertising.
    func show() {
        queue.async {
            self.wanted = true
            if let manager = self.manager {
                if manager.state == .poweredOn { self.advertise() }
            } else {
                self.manager = CBPeripheralManager(delegate: self, queue: self.queue,
                                                   options: [CBPeripheralManagerOptionShowPowerAlertKey: false])
            }
            self.stopWork?.cancel()
            let stop = DispatchWorkItem { [weak self] in self?.stop() }
            self.stopWork = stop
            self.queue.asyncAfter(deadline: .now() + Self.duration, execute: stop)
        }
    }

    private func advertise() {
        guard let manager, wanted, !manager.isAdvertising else { return }
        let name = Host.current().localizedName ?? "Mac"
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.service],
            CBAdvertisementDataLocalNameKey: name,
        ])
        Self.log.notice("findable for the phone's companion link")
    }

    private func stop() {
        wanted = false
        manager?.stopAdvertising()
        // Let go of the manager, so the Mac isn't holding a peripheral role it doesn't need.
        manager?.delegate = nil
        manager = nil
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn { advertise() }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error { Self.log.info("companion beacon failed: \(String(describing: error), privacy: .public)") }
    }
}
