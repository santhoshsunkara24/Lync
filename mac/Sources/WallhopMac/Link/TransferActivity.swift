import Foundation

/// Keeps files moving at full speed while a transfer runs. Lync lives in the menu bar with no
/// window on screen, which is exactly what App Nap throttles; and a long send shouldn't stop
/// because the Mac idled to sleep. Held only while files go either way.
final class TransferActivity {
    private var transfers: Set<String> = []
    private var activity: NSObjectProtocol?
    private let lock = NSLock()

    func begin(_ id: String) {
        lock.lock(); defer { lock.unlock() }
        transfers.insert(id)
        if activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .idleSystemSleepDisabled], reason: "Moving files with your phone")
        }
    }

    func end(_ id: String) {
        lock.lock(); defer { lock.unlock() }
        guard transfers.remove(id) != nil, transfers.isEmpty, let current = activity else { return }
        ProcessInfo.processInfo.endActivity(current)
        activity = nil
    }
}
