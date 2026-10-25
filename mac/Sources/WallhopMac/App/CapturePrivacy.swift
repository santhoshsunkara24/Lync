import AppKit

/// Whether Lync's windows show up in screenshots, screen recordings and screen sharing. They can
/// show codes, texts and caller names, so they're hidden by default; Settings › Privacy turns it off.
enum CapturePrivacy {
    private static let key = "hideFromCapture"

    static var hidden: Bool {
        get { UserDefaults.standard.object(forKey: key) as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: key)
            NSApp.windows.forEach(apply)
        }
    }

    /// Call on every window Lync shows; the setting change re-applies it to open windows.
    static func apply(_ window: NSWindow) {
        let forced = ProcessInfo.processInfo.environment["WALLHOP_CAPTURE"] == "1"
        window.sharingType = hidden && !forced ? .none : .readOnly
    }
}
