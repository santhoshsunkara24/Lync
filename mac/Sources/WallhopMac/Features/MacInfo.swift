import AppKit
import CryptoKit
import IOKit.ps

/// Tells the phone what this Mac looks like right now, for the live Mac card in the phone app:
/// battery, lock state, whether it's a laptop with a notch, and a small copy of the wallpaper.
///
/// Sent when the phone connects and whenever something changes. The wallpaper (a ~25 KB JPEG)
/// is only sent when it differs from what this connection already has.
final class MacInfoReporter {
    private let server: LinkServer
    private var locked = false
    private var lastSent: [String: AnyHashable] = [:]
    private var sentWallpaper: String?
    private var timer: Timer?

    init(server: LinkServer) {
        self.server = server
        let center = DistributedNotificationCenter.default()
        center.addObserver(forName: .init("com.apple.screenIsLocked"), object: nil, queue: .main) { [weak self] _ in
            self?.locked = true
            self?.send()
        }
        center.addObserver(forName: .init("com.apple.screenIsUnlocked"), object: nil, queue: .main) { [weak self] _ in
            self?.locked = false
            self?.send()
        }
        // Battery and wallpaper have no cheap change notifications; a minute is fresh enough.
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in self?.send() }
        timer?.tolerance = 10
    }

    /// A new connection starts with nothing, so everything is sent again.
    func phoneConnected() {
        lastSent = [:]
        sentWallpaper = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.send() }
    }

    func phoneDisconnected() {
        lastSent = [:]
        sentWallpaper = nil
    }

    private func send() {
        guard server.isConnected else { return }
        let power = Self.power()
        var fields: [String: AnyHashable] = [
            "locked": locked,
            "laptop": power != nil,
            "notch": NSScreen.screens.contains { $0.safeAreaInsets.top > 0 },
        ]
        if let power {
            fields["battery"] = power.level
            fields["charging"] = power.charging
        }
        let wallpaper = Self.wallpaper()
        if let wallpaper { fields["wp"] = wallpaper.hash }

        let wallpaperChanged = wallpaper != nil && wallpaper?.hash != sentWallpaper
        guard fields != lastSent || wallpaperChanged else { return }
        lastSent = fields
        let body = wallpaperChanged ? wallpaper!.jpeg : Data()
        if wallpaperChanged { sentWallpaper = wallpaper?.hash }
        server.send(Message("mac_info", fields.mapValues { $0.base }, body: body))
    }

    /// Internal battery, or nil on a desktop Mac.
    private static func power() -> (level: Int, charging: Bool)? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
        for source in list {
            guard let description = IOPSGetPowerSourceDescription(info, source)?.takeUnretainedValue() as? [String: Any],
                  description[kIOPSTypeKey] as? String == kIOPSInternalBatteryType,
                  let current = description[kIOPSCurrentCapacityKey] as? Int,
                  let max = description[kIOPSMaxCapacityKey] as? Int, max > 0 else { continue }
            let onAC = description[kIOPSPowerSourceStateKey] as? String == kIOPSACPowerValue
            let charging = (description[kIOPSIsChargingKey] as? Bool ?? false) || onAC
            return (current * 100 / max, charging)
        }
        return nil
    }

    private static var wallpaperCache: (key: String, value: (hash: String, jpeg: Data)?)?

    /// The main screen's wallpaper as a small JPEG. Nil for wallpapers that aren't still images
    /// (e.g. moving aerials), in which case the phone draws its own.
    private static func wallpaper() -> (hash: String, jpeg: Data)? {
        guard let screen = NSScreen.main, let url = NSWorkspace.shared.desktopImageURL(for: screen) else { return nil }
        let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
        let key = "\(url.path)|\(modified?.timeIntervalSince1970 ?? 0)"
        if let cache = wallpaperCache, cache.key == key { return cache.value }

        var result: (hash: String, jpeg: Data)?
        if let image = NSImage(contentsOf: url), let jpeg = thumbnail(image, width: 480) {
            let hash = SHA256.hash(data: jpeg).prefix(8).map { String(format: "%02x", $0) }.joined()
            result = (hash, jpeg)
        }
        wallpaperCache = (key, result)
        return result
    }

    private static func thumbnail(_ image: NSImage, width: CGFloat) -> Data? {
        guard image.size.width > 0 else { return nil }
        let size = NSSize(width: width, height: (width * image.size.height / image.size.width).rounded())
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width), pixelsHigh: Int(size.height),
                                         bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                         colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        NSGraphicsContext.current?.imageInterpolation = .high
        image.draw(in: NSRect(origin: .zero, size: size))
        NSGraphicsContext.restoreGraphicsState()
        return rep.representation(using: .jpeg, properties: [.compressionFactor: 0.72])
    }
}
