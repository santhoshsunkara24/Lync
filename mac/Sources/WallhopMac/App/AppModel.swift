import AppKit
import Combine
import Foundation
import ServiceManagement

/// Where live moments (OTPs, transfers, pairing) appear.
enum DisplayMode: String, CaseIterable, Identifiable {
    case notch
    case menuBar

    var id: String { rawValue }
    var label: String {
        switch self {
        case .notch: return "Notch"
        case .menuBar: return "Menu bar"
        }
    }
}

struct FileBatch: Equatable {
    var expected: Int
    var totalBytes: Int64
    var receivedBytes: Int64 = 0
    var files: [URL] = []
    var failed = 0
    var finished = false
    /// Moved from the inbox to Downloads › From Phone.
    var saved = false
    /// The connection dropped before the phone finished sending.
    var interrupted = false

    var progress: Double {
        guard totalBytes > 0 else { return finished ? 1 : 0 }
        return min(1, Double(receivedBytes) / Double(totalBytes))
    }

    var summary: String {
        let count = max(files.count, finished ? files.count : expected)
        let allImages = !files.isEmpty && files.allSatisfy { $0.isImage }
        let noun = allImages ? (count == 1 ? "photo" : "photos") : (count == 1 ? "file" : "files")
        return "\(count) \(noun)"
    }
}

struct ActivityItem: Identifiable, Equatable {
    enum Payload: Equatable {
        case otp(code: String, sender: String, expiresAt: Date)
        case text(String)
        case files(FileBatch)
        case missedCall(caller: String, sim: String?)
    }

    let id: String
    let date: Date
    var payload: Payload
}

/// A moment shown in the notch, pill or under the menu bar icon.
enum LiveCard: Equatable {
    case otp(code: String, sender: String)
    case text(String)
    case receiving(batchId: String)
    case added(batchId: String)
    case saved(batchId: String)
    case pairing(UUID)
    case connected(String)
    case sentToPhone(String)
    case notification(key: String)
    case call(String)
    case info(symbol: String, title: String, subtitle: String?)
}

/// A short message at the bottom of the open panel, optionally with an Undo button.
struct Toast: Equatable {
    let id = UUID()
    let symbol: String
    let message: String
    var actionTitle: String?
    var action: (() -> Void)?

    static func == (a: Toast, b: Toast) -> Bool { a.id == b.id }
}

/// App state for the UI. Main thread only.
final class AppModel: ObservableObject {
    @Published private(set) var listening = false
    /// Listening failed (not just "still starting"): the panel offers Try again.
    @Published private(set) var listenFailed = false
    @Published private(set) var statusMessage = "Starting…"
    @Published private(set) var connectedPhone: PairedPhone?
    @Published private(set) var phoneHost: String?
    @Published private(set) var battery: Int?
    @Published private(set) var charging = false
    @Published private(set) var pairingRequest: PairingRequest?
    @Published private(set) var pairedPhones: [PairedPhone] = []
    /// Shows the pairing QR in the panel, for adding another phone.
    @Published var showingPairingQR = false {
        didSet { if showingPairingQR { panelPage = .addPhone } }
    }
    /// The panel (notch or menu bar) and which page it shows.
    @Published var panelOpen = false
    @Published var panelPage: PanelPage = .home
    @Published private(set) var activity: [ActivityItem] = []
    @Published private(set) var live: LiveCard?
    @Published private(set) var outgoing: (id: String, progress: Double)?
    @Published private(set) var notifications: [PhoneNotification] = []
    @Published private(set) var call: PhoneCall?
    @Published private(set) var nowPlaying: NowPlaying?
    @Published private(set) var ringing = false
    private var lowBatteryWarned = false
    private var ringTimer: Timer?
    @Published private(set) var artwork: NSImage?
    @Published private(set) var appIcons: [String: NSImage] = [:]
    /// The notification being replied to from the notch card, if any.
    @Published var replyingTo: String?
    /// Feedback shown inside the panel while it's open (the overlay is hidden then).
    @Published private(set) var toast: Toast?
    /// The paired phone (id) whose Forget button is waiting for confirmation in Settings.
    @Published var confirmingForget: String?
    private var toastToken = 0
    /// Notifications cleared with "Clear all", dismissed on the phone once Undo has expired.
    private var pendingClear: (notifications: [PhoneNotification], work: DispatchWorkItem)?

    @Published var showNotifications: Bool {
        didSet { UserDefaults.standard.set(showNotifications, forKey: "showNotifications") }
    }
    @Published var hideNotificationText: Bool {
        didSet { UserDefaults.standard.set(hideNotificationText, forKey: "hideNotificationText") }
    }
    @Published private(set) var mutedApps: [String: String] {
        didSet { UserDefaults.standard.set(mutedApps, forKey: "mutedApps") }
    }

    @Published var displayMode: DisplayMode {
        didSet { UserDefaults.standard.set(displayMode.rawValue, forKey: "displayMode") }
    }
    @Published var copyTextAutomatically: Bool {
        didSet { UserDefaults.standard.set(copyTextAutomatically, forKey: "copyText") }
    }
    @Published private(set) var launchAtLogin = false

    let server: LinkServer
    let macName: String
    let themeStore = ThemeStore()
    /// Opens the Messages window. Set by the app delegate.
    var openMessages: () -> Void = {}
    private lazy var macInfo = MacInfoReporter(server: server)
    private var liveToken = 0
    private var otpClearTimer: Timer?
    private var expiryTimer: Timer?
    private var inboxTimer: Timer?

    static let otpLifetime: TimeInterval = 10 * 60
    static let clipboardClearDelay: TimeInterval = 60

    init(server: LinkServer) {
        self.server = server
        self.macName = server.macName
        displayMode = DisplayMode(rawValue: UserDefaults.standard.string(forKey: "displayMode") ?? "") ?? .notch
        copyTextAutomatically = UserDefaults.standard.object(forKey: "copyText") as? Bool ?? true
        showNotifications = UserDefaults.standard.object(forKey: "showNotifications") as? Bool ?? true
        hideNotificationText = UserDefaults.standard.bool(forKey: "hideNotificationText")
        mutedApps = UserDefaults.standard.dictionary(forKey: "mutedApps") as? [String: String] ?? [:]
        pairedPhones = server.paired.all
        launchAtLogin = Self.loginItemEnabled

        server.onEvent = { [weak self] event in self?.handle(event) }
        _ = macInfo // Start watching lock state now, not on the first connection.
        FileReceiver.cleanTempFolder()
        ReceivedFiles.cleanInbox()
        inboxTimer = Timer.scheduledTimer(withTimeInterval: 60 * 60, repeats: true) { _ in ReceivedFiles.cleanInbox() }
        expiryTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.objectWillChange.send() // Re-render so expired codes get masked.
        }
    }

    var isPaired: Bool { !pairedPhones.isEmpty }

    /// A built-in display with a notch is connected.
    var hasNotch: Bool { NSScreen.screens.contains { $0.safeAreaInsets.top > 0 } }
    var notchHeight: CGFloat { NSScreen.screens.first { $0.safeAreaInsets.top > 0 }?.safeAreaInsets.top ?? 0 }

    /// The newest code that hasn't expired, for the panel's big code card.
    var latestCode: (code: String, sender: String, item: ActivityItem)? {
        for item in activity {
            if case .otp(let code, let sender, let expiresAt) = item.payload, expiresAt > Date(),
               Date().timeIntervalSince(item.date) < 5 * 60 {
                return (code, sender, item)
            }
        }
        return nil
    }

    var appVersion: String {
        let info = Bundle.main.infoDictionary
        return info?["CFBundleShortVersionString"] as? String ?? "dev"
    }

    func openPanel(_ page: PanelPage = .home) {
        panelPage = page
        panelOpen = true
    }

    func closePanel() {
        panelOpen = false
        confirmingForget = nil
        toast = nil
        if replyingTo == nil { panelPage = .home }
    }

    // MARK: - Link events

    private func handle(_ event: LinkEvent) {
        switch event {
        case .listening(let port):
            listening = true
            listenFailed = false
            statusMessage = "Listening on port \(port.map(String.init) ?? "?")"

        case .stopped:
            // The reason is logged by the server; people get plain words and a Try again button.
            listening = false
            listenFailed = true
            statusMessage = "Lync can't accept connections right now."

        case .pairingRequested(let request):
            pairingRequest = request
            show(.pairing(request.id), for: nil)
            NSSound(named: "Glass")?.play()

        case .pairedWithQR(let name):
            pairedPhones = server.paired.all
            showingPairingQR = false
            if panelPage == .addPhone { panelPage = .home }
            show(.connected(name), for: 2.5)
            NSSound(named: "Glass")?.play()

        case .pairingEnded(let id):
            if pairingRequest?.id == id { pairingRequest = nil }
            if live == .pairing(id) { hideLive() }
            pairedPhones = server.paired.all

        case .connected(let phone, let host):
            let wasConnected = connectedPhone != nil
            connectedPhone = phone
            phoneHost = host
            pairedPhones = server.paired.all
            if !wasConnected { show(.connected(phone.name), for: 2.5) }
            macInfo.phoneConnected()

        case .disconnected(let phone):
            if connectedPhone?.id == phone.id {
                macInfo.phoneDisconnected()
                connectedPhone = nil
                phoneHost = nil
                battery = nil
                nowPlaying = nil
                ringing = false
                ringTimer?.invalidate()
                notifications.removeAll()
                replyingTo = nil
                call = nil
                // Cards about this phone's live state can't be acted on any more.
                switch live {
                case .call, .receiving, .notification: hideLive()
                default: break
                }
            }

        case .text(let text, _):
            insert(ActivityItem(id: UUID().uuidString, date: Date(), payload: .text(text)))
            if copyTextAutomatically { Clipboard.copy(text) }
            show(.text(text), for: 4)

        case .otp(let code, let sender, _):
            let item = ActivityItem(id: UUID().uuidString, date: Date(),
                                    payload: .otp(code: code, sender: sender,
                                                  expiresAt: Date().addingTimeInterval(Self.otpLifetime)))
            insert(item)
            copyOTP(code)
            show(.otp(code: code, sender: sender), for: 8)

        case .status(let level, let isCharging):
            battery = level >= 0 ? level : nil
            charging = isCharging
            if isCharging || level > 20 {
                lowBatteryWarned = false
            } else if level >= 0, level <= 15, !lowBatteryWarned {
                lowBatteryWarned = true
                show(.info(symbol: "battery.25percent", title: "Phone battery low · \(level)%",
                           subtitle: "Plug in your phone to keep it connected"), for: 6)
            }

        case .linkOpened(let url):
            show(.info(symbol: "safari", title: "Opened from your phone", subtitle: url.host ?? url.absoluteString), for: 3)

        case .batchStarted(let id, let count, let bytes):
            insert(ActivityItem(id: id, date: Date(), payload: .files(FileBatch(expected: count, totalBytes: bytes))))
            show(.receiving(batchId: id), for: nil)

        case .media(let playing, let art):
            nowPlaying = playing
            if playing == nil {
                artwork = nil
            } else if let art {
                artwork = NSImage(data: art)
            }

        case .batchProgress(let id, let bytes):
            updateBatch(id) { $0.receivedBytes = bytes }

        case .fileSaved(let id, let url):
            updateBatch(id) { $0.files.append(url) }

        case .fileFailed(let id, _):
            updateBatch(id) { $0.failed += 1 }

        case .batchInterrupted(let id):
            updateBatch(id) { $0.finished = true; $0.interrupted = true }
            if let batch = batch(id), !batch.files.isEmpty { Clipboard.copyFiles(batch.files) }
            if live == .receiving(batchId: id) || live == nil { show(.added(batchId: id), for: 8) }

        case .batchFinished(let id):
            updateBatch(id) { $0.finished = true; $0.receivedBytes = $0.totalBytes }
            // Straight to the clipboard: paste anywhere. Saving to Downloads is a button away.
            if let batch = batch(id), !batch.files.isEmpty { Clipboard.copyFiles(batch.files) }
            show(.added(batchId: id), for: 8)

        case .theme(let seed, let accent):
            themeStore.update(seed: seed, accent: accent)

        case .notification(let notification, let iconData):
            if let iconData, let image = NSImage(data: iconData) { appIcons[notification.packageName] = image }
            notifications.removeAll { $0.key == notification.key }
            notifications.insert(notification, at: 0)
            if notifications.count > 30 { notifications.removeLast(notifications.count - 30) }
            if showNotifications, mutedApps[notification.packageName] == nil, replyingTo == nil {
                show(.notification(key: notification.key), for: 6)
            }

        case .call(let call):
            switch call.state {
            case .ringing:
                self.call = call
                show(.call(call.id), for: nil)
            case .active:
                self.call = call
                show(.call(call.id), for: nil) // Stays with an End button until the call ends.
            case .ended:
                if self.call?.id == call.id { self.call = nil }
                if live == .call(call.id) { hideLive() }
            case .missed:
                self.call = call
                insert(ActivityItem(id: call.id, date: Date(), payload: .missedCall(caller: call.displayName, sim: call.sim)))
                show(.call(call.id), for: 5)
            }

        case .smsChanged:
            NotificationCenter.default.post(name: .smsChanged, object: nil)

        case .notificationRemoved(let key):
            notifications.removeAll { $0.key == key }
            if live == .notification(key: key), replyingTo != key { hideLive() }

        case .outgoingProgress(let id, let sent, let total):
            outgoing = (id, total > 0 ? Double(sent) / Double(total) : 0)

        case .outgoingFinished(let id, let ok):
            if outgoing?.id == id { outgoing = nil }
            feedback(ok ? "Sent to your phone" : "Couldn't send to your phone", failed: !ok)
        }
    }

    func batch(_ id: String) -> FileBatch? {
        for item in activity where item.id == id {
            if case .files(let batch) = item.payload { return batch }
        }
        return nil
    }

    private func updateBatch(_ id: String, _ change: (inout FileBatch) -> Void) {
        guard let index = activity.firstIndex(where: { $0.id == id }),
              case .files(var batch) = activity[index].payload else { return }
        change(&batch)
        activity[index].payload = .files(batch)
    }

    private func insert(_ item: ActivityItem) {
        activity.insert(item, at: 0)
        if activity.count > 50 { activity.removeLast(activity.count - 50) }
    }

    // MARK: - Live card

    /// Shows a card. `duration` nil keeps it until replaced or hidden.
    func show(_ card: LiveCard, for duration: TimeInterval?) {
        // A ringing call stays until it's answered, declined or ends.
        if case .call = live, call?.state == .ringing || call?.state == .active {
            if case .call = card {} else if case .pairing = card {} else { return }
        }
        // Don't interrupt someone typing a reply, except to ask about pairing.
        if replyingTo != nil {
            if case .pairing = card {} else { return }
        }
        // A pending pairing request wins over everything except another pairing request.
        if case .pairing = live, pairingRequest != nil {
            if case .pairing = card {} else { return }
        }
        liveToken += 1
        let token = liveToken
        live = card
        if let duration {
            DispatchQueue.main.asyncAfter(deadline: .now() + duration) { [weak self] in
                guard let self, self.liveToken == token else { return }
                self.hideLive()
            }
        }
    }

    /// While the pointer is over the card, it stays; when it leaves, it hides shortly after.
    func setHovering(_ hovering: Bool) {
        guard live != nil, replyingTo == nil else { return }
        if case .pairing = live { return }
        liveToken += 1
        guard !hovering else { return }
        let token = liveToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            guard let self, self.liveToken == token, self.replyingTo == nil else { return }
            self.hideLive()
        }
    }

    func hideLive() {
        liveToken += 1
        replyingTo = nil
        live = nil
        if let request = pairingRequest {
            live = .pairing(request.id)
        }
    }

    // MARK: - Feedback

    /// Tells the user how an action went: in the panel when it's open (the overlay is hidden
    /// then), otherwise as a short live card.
    func feedback(_ message: String, symbol: String? = nil, subtitle: String? = nil, failed: Bool = false) {
        let icon = symbol ?? (failed ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
        if panelOpen {
            showToast(Toast(symbol: icon, message: message))
        } else if let subtitle {
            show(.info(symbol: icon, title: message, subtitle: subtitle), for: 3)
        } else {
            show(.sentToPhone(message), for: 2.5)
        }
    }

    /// Shows a toast in the panel and reads it out with VoiceOver. Toasts with an action stay 5 s.
    func showToast(_ toast: Toast) {
        self.toast = toast
        toastToken += 1
        let token = toastToken
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: toast.message, .priority: NSAccessibilityPriorityLevel.high.rawValue])
        DispatchQueue.main.asyncAfter(deadline: .now() + (toast.action == nil ? 2 : 5)) { [weak self] in
            guard let self, self.toastToken == token else { return }
            self.toast = nil
        }
    }

    func runToastAction() {
        let action = toast?.action
        toast = nil
        action?()
    }

    // MARK: - Actions

    func decidePairing(_ accept: Bool) {
        pairingRequest?.decide(accept)
        pairingRequest = nil
        hideLive()
    }

    func copyOTP(_ code: String) {
        let changeCount = Clipboard.copySecret(code)
        otpClearTimer?.invalidate()
        otpClearTimer = Timer.scheduledTimer(withTimeInterval: Self.clipboardClearDelay, repeats: false) { _ in
            Clipboard.clear(ifUnchangedSince: changeCount)
        }
    }

    func copy(_ item: ActivityItem) {
        switch item.payload {
        case .otp(let code, _, let expiresAt) where expiresAt > Date():
            copyOTP(code)
        case .text(let text):
            Clipboard.copy(text)
        default:
            break
        }
    }

    /// Puts a received batch on the clipboard again.
    func copyFiles(_ id: String) {
        guard let batch = batch(id), !batch.files.isEmpty else { return }
        Clipboard.copyFiles(batch.files)
        feedback("Copied \(batch.summary)", symbol: "doc.on.clipboard", subtitle: "Paste it anywhere")
    }

    /// Moves a received batch from the inbox into Downloads › From Phone.
    func saveFiles(_ id: String) {
        guard let current = batch(id), !current.files.isEmpty else { return }
        let results = ReceivedFiles.save(current.files)
        let failures = results.filter { !$0.ok }.count
        // Only a batch that really moved counts as saved; files that didn't stay in the inbox.
        updateBatch(id) { $0.files = results.map(\.url); $0.saved = failures == 0 }
        if failures == 0 {
            if panelOpen { feedback("Saved to Downloads › From Phone", symbol: "arrow.down.circle.fill") } else { show(.saved(batchId: id), for: 5) }
        } else {
            let what = failures == 1 ? "1 file" : "\(failures) files"
            feedback("Couldn't save \(what) to Downloads", subtitle: "Check Downloads has space and try again", failed: true)
        }
    }

    /// Opens a received file with its default app.
    func openFile(_ url: URL) {
        NSWorkspace.shared.open(url)
    }

    func revealFiles(_ id: String) {
        guard let batch = batch(id), !batch.files.isEmpty else { return }
        NSWorkspace.shared.activateFileViewerSelecting(batch.files)
        hideLive()
    }

    func remove(_ item: ActivityItem) {
        activity.removeAll { $0.id == item.id }
    }

    /// Clears Recent (it's only on this Mac), with Undo for a few seconds.
    func clearActivity() {
        let cleared = activity
        guard !cleared.isEmpty else { return }
        activity.removeAll()
        showToast(Toast(symbol: "trash", message: "Cleared Recent", actionTitle: "Undo") { [weak self] in
            guard let self else { return }
            self.activity = cleared + self.activity
        })
    }

    func ringPhone() {
        guard connectedPhone != nil else { return }
        let start = !ringing
        server.ringPhone(start)
        feedback(start ? "Ringing your phone" : "Stopped ringing", symbol: start ? "bell.and.waves.left.and.right" : "bell.slash")
        ringing = start
        ringTimer?.invalidate()
        if start {
            ringTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: false) { [weak self] _ in self?.ringing = false }
        }
    }

    func sendClipboardToPhone() {
        guard connectedPhone != nil else {
            feedback("Your phone isn't connected", failed: true)
            return
        }
        let raw = NSPasteboard.general.string(forType: .string)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if let url = URL(string: raw), let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
           !raw.contains(" "), raw.utf8.count < 8 * 1024 {
            server.openOnPhone(url)
            feedback("Link sent · tap it on your phone", symbol: "link")
            return
        }
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            feedback("Your clipboard has no text", failed: true)
            return
        }
        // Too long for one message: it goes as a text file instead (PROTOCOL.md, Framing).
        if text.utf8.count > LinkServer.maxTextBytes {
            sendTextAsFile(text)
            return
        }
        server.sendText(text) { [weak self] ok in
            self?.feedback(ok ? "Clipboard sent to your phone" : "Couldn't reach your phone", failed: !ok)
        }
    }

    private func sendTextAsFile(_ text: String) {
        let folder = Storage.directory.appendingPathComponent("Outgoing", isDirectory: true)
        let fm = FileManager.default
        try? fm.removeItem(at: folder) // Only the latest one is kept.
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let stamp = Date().formatted(.dateTime.year().month(.twoDigits).day(.twoDigits).hour().minute())
            .replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: ".")
        let url = folder.appendingPathComponent(ReceivedFiles.sanitize("Clipboard \(stamp).txt"))
        do {
            try Data(text.utf8).write(to: url, options: .atomic)
        } catch {
            feedback("Too big to send as text", failed: true)
            return
        }
        feedback("Long text · sending it as a file", symbol: "doc.text")
        if let id = server.sendFiles([url]) { outgoing = (id, 0) }
    }

    /// `skippedFolders`: how many dropped items were folders, which can't be sent.
    func sendFilesToPhone(_ urls: [URL], skippedFolders: Int = 0) {
        guard connectedPhone != nil else {
            feedback("Your phone isn't connected", failed: true)
            return
        }
        if skippedFolders > 0 {
            let what = skippedFolders == 1 ? "a folder" : "\(skippedFolders) folders"
            feedback(urls.isEmpty ? "Folders can't be sent · drop files instead" : "Skipped \(what) · sending the files",
                     symbol: "folder.badge.minus", failed: urls.isEmpty)
        }
        guard !urls.isEmpty else { return }
        if let id = server.sendFiles(urls) {
            outgoing = (id, 0)
        } else {
            feedback("Couldn't read those files", failed: true)
        }
    }

    /// Picks files to send. The panel closes first so the open dialog isn't hidden under it.
    func chooseFilesToSend() {
        closePanel()
        DispatchQueue.main.async { [weak self] in
            let panel = NSOpenPanel()
            panel.allowsMultipleSelection = true
            panel.canChooseDirectories = false
            panel.prompt = "Send to Phone"
            NSApp.activate(ignoringOtherApps: true)
            if panel.runModal() == .OK { self?.sendFilesToPhone(panel.urls) }
        }
    }

    /// While files are dragged over the notch or the menu bar icon: says where they'll go.
    func dropHover(_ entered: Bool) {
        let card = dropHintCard
        if entered {
            show(card, for: connectedPhone == nil ? 2.5 : nil)
        } else if live == card {
            hideLive()
        }
    }

    /// The overlay lets drags through while it shows this, so the drop still reaches the notch.
    func isDropHint(_ card: LiveCard?) -> Bool {
        guard let card else { return false }
        return card == dropHintCard
    }

    private var dropHintCard: LiveCard {
        if let phone = connectedPhone {
            return .info(symbol: "arrow.up.doc.fill", title: "Drop to send to \(phone.name)", subtitle: nil)
        }
        return .info(symbol: "iphone.slash", title: "Your phone isn't connected", subtitle: "Connect it to send files")
    }

    /// Tries to start listening again after it failed.
    func retryListening() {
        statusMessage = "Starting…"
        listenFailed = false
        server.restart()
    }

    // MARK: - Notifications

    func notification(_ key: String) -> PhoneNotification? {
        notifications.first { $0.key == key }
    }

    func startReply(_ key: String) {
        liveToken += 1 // Keep the card open while typing.
        if live != .notification(key: key) { live = .notification(key: key) }
        replyingTo = key
    }

    func cancelReply() {
        replyingTo = nil
        hideLive()
    }

    func reply(to key: String, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        replyingTo = nil
        server.replyToNotification(key: key, text: trimmed) { [weak self] ok in
            self?.show(.sentToPhone(ok ? "Reply sent" : "Couldn't send the reply"), for: 2)
        }
    }

    func performAction(_ action: NotificationAction, on notification: PhoneNotification) {
        server.performNotificationAction(key: notification.key, index: action.index)
        if live == .notification(key: notification.key) { hideLive() }
    }

    func media(_ action: String) {
        server.sendMediaCommand(action)
    }

    func answerCall() {
        server.sendCallCommand("answer")
    }

    func declineCall() {
        server.sendCallCommand("decline")
        hideLive()
    }

    func endCall() {
        server.sendCallCommand("end")
        hideLive()
    }

    func dismissNotification(_ key: String) {
        server.dismissNotification(key: key)
        notifications.removeAll { $0.key == key }
        if live == .notification(key: key) { hideLive() }
    }

    /// Clears every notification here at once and on the phone after 5 s, unless Undo is pressed.
    func dismissAllNotifications() {
        flushPendingClear()
        let cleared = notifications
        guard !cleared.isEmpty else { return }
        notifications.removeAll()
        let work = DispatchWorkItem { [weak self] in self?.flushPendingClear() }
        pendingClear = (cleared, work)
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
        let what = cleared.count == 1 ? "1 notification" : "\(cleared.count) notifications"
        showToast(Toast(symbol: "bell.slash", message: "Cleared \(what)", actionTitle: "Undo") { [weak self] in
            guard let self, let pending = self.pendingClear else { return }
            pending.work.cancel()
            self.pendingClear = nil
            let keys = Set(self.notifications.map(\.key))
            self.notifications = (self.notifications + pending.notifications.filter { !keys.contains($0.key) })
                .sorted { $0.time > $1.time }
        })
    }

    private func flushPendingClear() {
        guard let pending = pendingClear else { return }
        pending.work.cancel()
        pendingClear = nil
        pending.notifications.forEach { server.dismissNotification(key: $0.key) }
    }

    func mute(_ notification: PhoneNotification) {
        mutedApps[notification.packageName] = notification.appName
        if live == .notification(key: notification.key) { hideLive() }
    }

    func unmute(_ packageName: String) {
        mutedApps[packageName] = nil
    }

    func forget(_ phone: PairedPhone) {
        confirmingForget = nil
        server.paired.remove(id: phone.id)
        server.disconnect(publicKey: phone.publicKey)
        pairedPhones = server.paired.all
        feedback("Forgot \(phone.name)", symbol: "iphone.slash")
    }

    /// The standard About window, with the version. The panel closes so it doesn't cover it.
    func showAbout() {
        closePanel()
        NSApp.activate(ignoringOtherApps: true)
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        NSApp.orderFrontStandardAboutPanel(options: [
            .applicationName: "Lync",
            .applicationVersion: appVersion,
            .version: build,
            .credits: NSAttributedString(string: "Codes, texts, files and notifications from your Android phone.",
                                         attributes: [.font: NSFont.systemFont(ofSize: 11), .foregroundColor: NSColor.secondaryLabelColor]),
        ])
    }

    func openReceivedFolder() {
        NSWorkspace.shared.open(ReceivedFiles.folder)
    }

    // MARK: - Launch at login

    private static var loginItemEnabled: Bool {
        guard Bundle.main.bundleIdentifier != nil else { return false }
        return SMAppService.mainApp.status == .enabled
    }

    var canLaunchAtLogin: Bool { Bundle.main.bundleIdentifier != nil }

    func setLaunchAtLogin(_ enabled: Bool) {
        guard canLaunchAtLogin else { return }
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            NSLog("Lync: launch at login change failed: \(error)")
        }
        launchAtLogin = Self.loginItemEnabled
    }
}

extension Notification.Name {
    static let smsChanged = Notification.Name("WallhopSMSChanged")
}

enum Clipboard {
    @discardableResult
    static func copy(_ text: String) -> Int {
        let board = NSPasteboard.general
        board.clearContents()
        board.setString(text, forType: .string)
        return board.changeCount
    }

    /// A one-time code: kept off Universal Clipboard and marked (nspasteboard.org) so clipboard
    /// managers neither show nor keep it.
    @discardableResult
    static func copySecret(_ text: String) -> Int {
        let board = NSPasteboard.general
        board.prepareForNewContents(with: .currentHostOnly)
        board.setString(text, forType: .string)
        board.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        board.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.TransientType"))
        return board.changeCount
    }

    static func copyFile(_ url: URL) {
        let board = NSPasteboard.general
        board.clearContents()
        board.writeObjects([url as NSURL])
    }

    /// Files as both images (when they are) and file references, so they paste into chat apps,
    /// documents and Finder alike.
    static func copyFiles(_ urls: [URL]) {
        let board = NSPasteboard.general
        board.clearContents()
        var objects: [NSPasteboardWriting] = []
        if urls.count == 1, urls[0].isImage, let image = NSImage(contentsOf: urls[0]) { objects.append(image) }
        objects.append(contentsOf: urls.map { $0 as NSURL })
        board.writeObjects(objects)
    }

    /// Puts an image on the clipboard as both image data and a file, so it pastes into any app.
    static func copyImage(_ url: URL) {
        let board = NSPasteboard.general
        board.clearContents()
        if let image = NSImage(contentsOf: url) {
            board.writeObjects([image, url as NSURL])
        } else {
            board.writeObjects([url as NSURL])
        }
    }

    /// Clears the clipboard only if nothing new was copied since `changeCount`.
    static func clear(ifUnchangedSince changeCount: Int) {
        let board = NSPasteboard.general
        if board.changeCount == changeCount { board.clearContents() }
    }
}

extension URL {
    var isImage: Bool {
        (try? resourceValues(forKeys: [.contentTypeKey]).contentType?.conforms(to: .image)) ?? false
    }
}
