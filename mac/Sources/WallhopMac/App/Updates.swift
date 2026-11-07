import AppKit
#if canImport(Sparkle)
import Sparkle
#endif

/// Updates without a new download (Sparkle). Lync checks the appcast on GitHub Pages once a day;
/// a new version downloads in the background and installs on relaunch. Every update is checked
/// against the EdDSA key in Info.plist (SUPublicEDKey), so only builds signed with the release key
/// install. Releasing: scripts/release-mac.sh.
#if canImport(Sparkle)
@MainActor
enum Updates {
    private static let gentle = GentleReminders()
    private static let controller = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: nil, userDriverDelegate: gentle)
    private static var started = false

    /// Starts the daily check. Debug builds from this checkout don't check: they'd offer the
    /// released version over the one being worked on.
    static func start() {
        #if !DEBUG
        startOnce()
        #endif
    }

    /// "Check for updates": looks now and says what it found, even "You're up to date".
    static func check() {
        NSApp.activate()
        startOnce()
        controller.checkForUpdates(nil)
    }

    /// Sparkle refuses a second start (and says so in an alert).
    private static func startOnce() {
        guard !started else { return }
        started = true
        controller.startUpdater()
    }
}

/// A menu bar app has no Dock icon to badge, so a scheduled update shouldn't open a window over
/// whatever the user is doing. Sparkle shows it at a quiet moment instead.
private final class GentleReminders: NSObject, SPUStandardUserDriverDelegate {
    var supportsGentleScheduledUpdateReminders: Bool { true }
}
#else
/// Builds without Sparkle (the snapshot renderer, built with swiftc alone): no updates.
@MainActor
enum Updates {
    static func start() {}
    static func check() {}
}
#endif
