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

struct FileBatch: Equatable, Codable {
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

struct ActivityItem: Identifiable, Equatable, Codable {
    enum Payload: Equatable, Codable {
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
    case notification(key: String)
    case call(String)
    case info(symbol: String, title: String, subtitle: String?)
    /// A small, short-lived note ("Connected", "Link opened", "Sent"): a mini card that never
    /// replaces or waits behind anything else, and doesn't take clicks.
    case quiet(symbol: String, text: String)
    /// Files dragged near the notch or the menu bar icon: drop them here to send them.
    case dropZone
    /// Files on their way to the phone after a drop.
    case sending(id: String)

    var isQuiet: Bool { if case .quiet = self { return true }; return false }
}

/// Files being dragged somewhere on the Mac while a phone is connected (item: drag anywhere).
struct FileDragInfo: Equatable {
    var count: Int
    var images: [URL]
    var allImages: Bool
    /// The pointer is close to the notch or the menu bar icon: the drop zone is open.
    var near = false

    var summary: String {
        let noun = allImages ? (count == 1 ? "photo" : "photos") : (count == 1 ? "file" : "files")
        return "\(count) \(noun)"
    }
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
    /// When the current phone connected, for the phone card's "connected 5 min ago".
    @Published private(set) var connectedAt: Date?
    @Published private(set) var pairingRequest: PairingRequest?
    @Published private(set) var pairedPhones: [PairedPhone] = []
    /// Shows the pairing QR in the panel, for adding another phone.
    @Published var showingPairingQR = false {
        didSet { if showingPairingQR { panelPage = .addPhone } }
    }
    /// The panel (notch or menu bar) and which page it shows.
    @Published var panelOpen = false
    @Published var panelPage: PanelPage = .home
    @Published private(set) var activity: [ActivityItem] = [] {
        didSet { if activity != oldValue { scheduleRecentSave() } }
    }
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
    /// The notification being replied to inside the open panel, if any.
    @Published var inlineReplyKey: String?
    /// A row the panel scrolls to when it opens (a clicked notification card), e.g. "notif-<key>".
    @Published var scrollTarget: String?
    /// The one-time code that's on the clipboard right now (nil once it's cleared or replaced).
    @Published private(set) var codeOnClipboard: String?
    /// After the first pairing: asks once whether Lync should open at login.
    @Published private(set) var offerLoginItem = false
    /// Why "Open at login" didn't turn on, e.g. it waits for approval in System Settings.
    @Published private(set) var loginItemNote: String?
    @Published private(set) var loginItemNeedsApproval = false
    /// Files are held over the open panel: it shows "Drop to send to <phone>".
    @Published private(set) var panelDropHover = false
    /// Files being dragged anywhere on the Mac (only while a phone is connected).
    @Published private(set) var fileDrag: FileDragInfo?
    /// Feedback shown inside the panel while it's open (the overlay is hidden then).
    @Published private(set) var toast: Toast?
    /// The paired phone (id) whose Forget button is waiting for confirmation in Settings.
    @Published var confirmingForget: String?
    /// Bumped when files are dropped on the notch or icon: the drop card pulses before it goes.
    @Published private(set) var dropPulse = 0
    /// Bumped when files are dragged over while no phone is connected: the card shakes.
    @Published private(set) var dropShake = 0
    /// Keeps the drop card up for its pulse after the drag ends.
    private var dropPulsing = false
    private var toastToken = 0
    /// Notifications cleared with "Clear all", dismissed on the phone once Undo has expired.
    private var pendingClear: (notifications: [PhoneNotification], work: DispatchWorkItem)?

    @Published var showNotifications: Bool {
        didSet { UserDefaults.standard.set(showNotifications, forKey: "showNotifications") }
    }
    /// Also hides notification titles and text previews in Recent.
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
    /// Cards that arrived while a call or a reply had the notch; shown afterwards, codes first.
    private var queued: [(card: LiveCard, duration: TimeInterval?)] = []
    /// Where the pointer is holding a file drag: "sensor", "overlay", "status", "near", "panel".
    private var dropSources = Set<String>()
    private var dropHideWork: DispatchWorkItem?
    /// The launch hint ("Lync lives in the notch") still to show this launch.
    private var launchHintPending = false
    private var recentSaveWork: DispatchWorkItem?
    /// What was on the clipboard before a code replaced it, put back when the code is cleared.
    private var clipboardBeforeCode: [NSPasteboardItem]?
    private var codeChangeCount: Int?
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
        activity = Self.loadRecent()
        noteScreens()

        server.onEvent = { [weak self] event in self?.handle(event) }
        _ = macInfo // Start watching lock state now, not on the first connection.
        FileReceiver.cleanTempFolder()
        ReceivedFiles.cleanInbox()
        inboxTimer = Timer.scheduledTimer(withTimeInterval: 60 * 60, repeats: true) { _ in ReceivedFiles.cleanInbox() }
        expiryTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            guard let self else { return }
            // Something else was copied since the code: it's no longer on the clipboard.
            if let count = self.codeChangeCount, NSPasteboard.general.changeCount != count { self.forgetClipboardCode() }
            self.objectWillChange.send() // Re-render so expired codes get masked.
        }
    }

    var isPaired: Bool { !pairedPhones.isEmpty }

    /// A built-in display with a notch is connected.
    var hasNotch: Bool { NSScreen.screens.contains { $0.safeAreaInsets.top > 0 } }
    /// This Mac has a notch, but it isn't available now (lid closed, external displays only).
    var notchUnavailable: Bool { !hasNotch && UserDefaults.standard.bool(forKey: "sawNotch") }

    /// Remembers that this Mac has a notch, for `notchUnavailable`. Called when screens change.
    func noteScreens() {
        if hasNotch { UserDefaults.standard.set(true, forKey: "sawNotch") }
    }
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

    /// `scrollTo`: a row id the home page scrolls to, e.g. "notif-<key>".
    func openPanel(_ page: PanelPage = .home, scrollTo: String? = nil) {
        panelPage = page
        scrollTarget = scrollTo
        panelOpen = true
        // Everything waiting is in the panel too.
        queued.removeAll()
    }

    func closePanel() {
        panelOpen = false
        confirmingForget = nil
        toast = nil
        inlineReplyKey = nil
        scrollTarget = nil
        if replyingTo == nil { panelPage = .home }
        if launchHintPending {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.showLaunchHint() }
        }
    }

    // MARK: - First run

    /// The user opened the panel themselves (click, shortcut, menu): no more launch hints.
    func noteUserOpenedPanel() {
        UserDefaults.standard.set(true, forKey: "openedPanel")
        launchHintPending = false
    }

    /// Called at launch. Unpaired: the panel opens by itself to show the pairing steps. Until the
    /// user has opened the panel once, a short hint says where Lync lives (once per launch).
    func launched(openPanel open: () -> Void) {
        launchHintPending = !UserDefaults.standard.bool(forKey: "openedPanel")
        if !isPaired {
            open()
        } else if launchHintPending {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.showLaunchHint() }
        }
    }

    private func showLaunchHint() {
        guard launchHintPending, !panelOpen else { return }
        launchHintPending = false
        let inNotch = displayMode == .notch && hasNotch
        show(.info(symbol: inNotch ? "laptopcomputer" : "menubar.rectangle",
                   title: inNotch ? "Lync lives in the notch — click it" : "Lync lives in the menu bar — click its icon",
                   subtitle: "Or press ⌥⌘L from anywhere"), for: 6)
    }

    /// The panel's "See how it looks": a sample code card, then the panel again if still unpaired.
    func previewCard() {
        closePanel()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.show(.otp(code: "482913", sender: "Preview"), for: 4)
        }
        if !isPaired {
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.8) { [weak self] in
                guard let self, !self.panelOpen, !self.isPaired else { return }
                self.openPanel()
            }
        }
    }

    /// Reloads the paired phones; after the very first pairing, offers to open at login (once).
    private func reloadPaired() {
        let wasEmpty = pairedPhones.isEmpty
        pairedPhones = server.paired.all
        if wasEmpty, !pairedPhones.isEmpty, canLaunchAtLogin, !launchAtLogin,
           !UserDefaults.standard.bool(forKey: "askedLaunchAtLogin") {
            offerLoginItem = true
        }
    }

    func answerLoginOffer(_ turnOn: Bool) {
        offerLoginItem = false
        UserDefaults.standard.set(true, forKey: "askedLaunchAtLogin")
        guard turnOn else { return }
        setLaunchAtLogin(true)
        if loginItemNeedsApproval {
            showToast(Toast(symbol: "person.badge.key", message: "Allow Lync in Login Items", actionTitle: "Open") { [weak self] in
                self?.openLoginItemsSettings()
            })
        } else if let note = loginItemNote {
            feedback(note, failed: true)
        }
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
            reloadPaired()
            showingPairingQR = false
            if panelPage == .addPhone { panelPage = .home }
            show(.quiet(symbol: "iphone.radiowaves.left.and.right", text: "Paired with \(name)"), for: 2)
            NSSound(named: "Glass")?.play()

        case .pairingEnded(let id):
            if pairingRequest?.id == id { pairingRequest = nil }
            if live == .pairing(id) { hideLive() }
            reloadPaired()

        case .connected(let phone, let host):
            let wasConnected = connectedPhone != nil
            connectedPhone = phone
            phoneHost = host
            if !wasConnected { connectedAt = Date() }
            reloadPaired()
            if !wasConnected { show(.quiet(symbol: "iphone.radiowaves.left.and.right", text: "\(phone.name) connected"), for: 1.5) }
            macInfo.phoneConnected()

        case .forgotten(let phone):
            // The phone unpaired this Mac (Forget on the phone): same as Forget in Settings.
            if confirmingForget == phone.id { confirmingForget = nil }
            pairedPhones = server.paired.all
            feedback("\(phone.name) unpaired this Mac", symbol: "iphone.slash", subtitle: "Pair again to use it with Lync")

        case .ringStopped:
            ringing = false
            ringTimer?.invalidate()

        case .disconnected(let phone):
            if connectedPhone?.id == phone.id {
                macInfo.phoneDisconnected()
                connectedPhone = nil
                connectedAt = nil
                phoneHost = nil
                battery = nil
                nowPlaying = nil
                ringing = false
                ringTimer?.invalidate()
                notifications.removeAll()
                replyingTo = nil
                inlineReplyKey = nil
                call = nil
                fileDrag = nil
                // Cards about this phone's live state can't be acted on any more.
                queued.removeAll { item in
                    switch item.card {
                    case .call, .notification: return true
                    default: return false
                    }
                }
                switch live {
                case .call, .receiving, .notification, .dropZone, .sending: hideLive()
                default: break
                }
            }

        case .text(let text, _):
            insert(ActivityItem(id: UUID().uuidString, date: Date(), payload: .text(text)))
            if copyTextAutomatically { Clipboard.copy(text) }
            show(.text(text), for: copyTextAutomatically ? 4 : 6)

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
            show(.quiet(symbol: "safari", text: "Opened \(url.host ?? "a link") from your phone"), for: 1.5)

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
                queued.removeAll { $0.card == .call(call.id) }
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
            if live == .sending(id: id) { hideLive() }
            feedback(ok ? "Sent to your phone" : "Couldn't send to your phone",
                     subtitle: ok ? nil : "Check your phone is nearby and try again", failed: !ok)
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

    // MARK: - Recent across restarts

    /// Recent is kept in the data folder as a small JSON (what happened, never file data):
    /// texts, missed calls and received files that still exist. Codes are never written to disk.
    private static let recentFile = "recent.json"

    private func scheduleRecentSave() {
        recentSaveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.saveRecent() }
        recentSaveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: work)
    }

    private func saveRecent() {
        let keep = activity.filter { item in
            switch item.payload {
            case .otp: return false
            case .files(let batch): return batch.finished && !batch.files.isEmpty
            case .text, .missedCall: return true
            }
        }
        guard let data = try? JSONEncoder().encode(keep) else { return }
        try? Storage.writePrivate(data, to: Self.recentFile)
    }

    /// Saved Recent, without files that are gone (unsaved ones are removed after a day).
    private static func loadRecent() -> [ActivityItem] {
        guard let data = Storage.read(recentFile),
              let items = try? JSONDecoder().decode([ActivityItem].self, from: data) else { return [] }
        let fm = FileManager.default
        return items.compactMap { item in
            guard case .files(var batch) = item.payload else { return item }
            batch.files = batch.files.filter { fm.fileExists(atPath: $0.path) }
            guard !batch.files.isEmpty else { return nil }
            var copy = item
            copy.payload = .files(batch)
            return copy
        }
    }

    // MARK: - Live card

    /// Shows a card. `duration` nil keeps it until replaced or hidden. While a call, a reply or a
    /// pairing request has the notch, other cards wait their turn (codes first) instead of being lost.
    func show(_ card: LiveCard, for duration: TimeInterval?) {
        if card.isQuiet {
            announce(card)
            // Quiet notes never replace a card or wait for one: VoiceOver still hears them.
            guard live == nil || live?.isQuiet == true else { return }
        } else if isBlocked(by: card) {
            enqueue(card, duration)
            return
        }
        liveToken += 1
        let token = liveToken
        live = card
        if !card.isQuiet { announce(card) }
        if var duration {
            // VoiceOver users get time to hear the card and act on it.
            if NSWorkspace.shared.isVoiceOverEnabled, !card.isQuiet { duration = max(duration, 30) }
            DispatchQueue.main.asyncAfter(deadline: .now() + duration) { [weak self] in
                guard let self, self.liveToken == token else { return }
                self.hideLive()
            }
        }
    }

    /// Something that mustn't be interrupted has the notch: a call, a reply being typed, a pairing request.
    private func isBlocked(by card: LiveCard) -> Bool {
        if case .pairing = card { return false }
        if case .call = live, call?.state == .ringing || call?.state == .active {
            if case .call = card { return false }
            return true
        }
        if replyingTo != nil { return true }
        if case .pairing = live, pairingRequest != nil { return true }
        return false
    }

    private func enqueue(_ card: LiveCard, _ duration: TimeInterval?) {
        switch card {
        case .dropZone, .sending, .quiet: return // Only make sense right now.
        case .info where isDropHint(card): return
        case .added(let id), .saved(let id):
            queued.removeAll { $0.card == .receiving(batchId: id) || $0.card == .added(batchId: id) }
        default: break
        }
        queued.removeAll { $0.card == card }
        queued.append((card, duration))
        if queued.count > 8 { queued.removeFirst(queued.count - 8) }
    }

    /// Shows the next waiting card, codes first, once the notch is free.
    private func showQueued() {
        guard live == nil, !panelOpen, !queued.isEmpty else { return }
        // A beat later, so the previous card has gone back up.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
            guard let self, self.live == nil || self.live?.isQuiet == true, !self.panelOpen else { return }
            while !self.queued.isEmpty {
                let index = self.queued.firstIndex { if case .otp = $0.card { return true }; return false } ?? 0
                var next = self.queued.remove(at: index)
                // Checked now, not when it was queued: things may have ended meanwhile.
                switch next.card {
                case .notification(let key) where self.notification(key) == nil: continue // Cleared.
                case .call(let id) where self.call?.id != id || self.call?.state == .ended: continue // Over.
                case .receiving(let id) where self.batch(id)?.finished == true: next = (.added(batchId: id), 8)
                default: break
                }
                self.show(next.card, for: next.duration ?? 6)
                return
            }
        }
    }

    /// Reads a card out with VoiceOver, with what can be done with it.
    private func announce(_ card: LiveCard) {
        guard let text = accessibilityAnnouncement(card) else { return }
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: text, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    private func accessibilityAnnouncement(_ card: LiveCard) -> String? {
        let open = "Option-Command-L opens Lync."
        switch card {
        case .otp(let code, let sender):
            let digits = code.map(String.init).joined(separator: " ")
            return "Code \(digits)\(sender.isEmpty ? "" : " from \(sender)"), copied. \(open)"
        case .text(let text):
            return "Text from your phone: \(hideNotificationText ? "hidden" : text). \(copyTextAutomatically ? "Copied." : open)"
        case .receiving(let id): return "Receiving \(batch(id)?.summary ?? "files") from your phone."
        case .added(let id): return "\(batch(id)?.summary ?? "Files") copied. Kept for a day. \(open) to save them."
        case .saved(let id): return "\(batch(id)?.summary ?? "Files") saved to Downloads."
        case .pairing:
            return "\(pairingRequest?.phoneName ?? "A phone") wants to pair. Code \((pairingRequest?.code ?? "").map(String.init).joined(separator: " ")). Return to pair, Escape to decline."
        case .notification(let key):
            guard let n = notification(key) else { return nil }
            let body = hideNotificationText ? "New notification" : [n.title, n.text].filter { !$0.isEmpty }.joined(separator: ": ")
            return "\(n.appName): \(body). \(n.canReply ? "Reply available. " : "")Option-Command-L to act on it."
        case .call:
            guard let call else { return nil }
            switch call.state {
            case .ringing: return "Incoming call from \(call.displayName). Option-Command-L, then Decline or Answer on phone."
            case .active: return "On a call with \(call.displayName)."
            case .missed: return "Missed call from \(call.displayName)."
            case .ended: return nil
            }
        case .info(_, let title, let subtitle): return [title, subtitle].compactMap { $0 }.joined(separator: ". ")
        case .quiet(_, let text): return text
        case .dropZone:
            guard let phone = connectedPhone else { return nil }
            return "Drop here to send to \(phone.name)."
        case .sending: return "Sending to your phone."
        }
    }

    /// While the pointer is over the card, it stays; when it leaves, it hides shortly after.
    func setHovering(_ hovering: Bool) {
        guard live != nil, replyingTo == nil else { return }
        if case .pairing = live { return }
        if live == .dropZone || live.map(isDropHint) == true { return }
        if case .sending = live { return }
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
            return
        }
        showQueued()
    }

    // MARK: - Feedback

    /// Tells the user how an action went: in the panel when it's open (the overlay is hidden
    /// then), otherwise as a short live card.
    func feedback(_ message: String, symbol: String? = nil, subtitle: String? = nil, failed: Bool = false) {
        let icon = symbol ?? (failed ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
        if panelOpen {
            showToast(Toast(symbol: icon, message: message))
        } else if subtitle != nil || failed {
            show(.info(symbol: icon, title: message, subtitle: subtitle), for: failed ? 5 : 3)
        } else {
            show(.quiet(symbol: icon, text: message), for: 1.5)
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

    /// Puts a code on the clipboard for a minute. What was there before comes back afterwards,
    /// unless something else was copied in the meantime.
    func copyOTP(_ code: String) {
        // Our own earlier code isn't worth keeping; whatever the user had before it is.
        if codeChangeCount != NSPasteboard.general.changeCount { clipboardBeforeCode = Clipboard.snapshot() }
        codeChangeCount = Clipboard.copySecret(code)
        codeOnClipboard = code
        otpClearTimer?.invalidate()
        otpClearTimer = Timer.scheduledTimer(withTimeInterval: Self.clipboardClearDelay, repeats: false) { [weak self] _ in
            self?.clearCodeFromClipboard()
        }
    }

    private func clearCodeFromClipboard() {
        if let count = codeChangeCount, NSPasteboard.general.changeCount == count {
            Clipboard.restore(clipboardBeforeCode ?? [])
        }
        forgetClipboardCode()
    }

    private func forgetClipboardCode() {
        otpClearTimer?.invalidate()
        codeChangeCount = nil
        clipboardBeforeCode = nil
        codeOnClipboard = nil
    }

    /// ⌘C in the panel with nothing focused, or the right-click menu.
    @discardableResult
    func copyLatestCode() -> Bool {
        guard let latest = latestCode else { return false }
        copyOTP(latest.code)
        feedback("Code copied", symbol: "doc.on.clipboard")
        return true
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

    /// Starts or stops the phone ringing. The phone reports when it stops by itself (`ring`
    /// `stopped`, e.g. someone silenced it); without that report the Mac assumes 30 s.
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
        let board = NSPasteboard.general
        // Copied files (Finder) go as files; folders can't be sent.
        let urls = board.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        if !urls.isEmpty {
            let files = urls.filter { !$0.hasDirectoryPath }
            sendFilesToPhone(files, skippedFolders: urls.count - files.count)
            return
        }
        // A copied image (screenshot, from a browser or an editor) goes as a PNG file, unless
        // the clipboard offers text first.
        let types = board.types ?? []
        let imageTypes: Set<NSPasteboard.PasteboardType> = [.png, .tiff, .init("public.jpeg"), .init("public.heic")]
        if let imageIndex = types.firstIndex(where: imageTypes.contains),
           types.firstIndex(of: .string).map({ imageIndex < $0 }) ?? true {
            sendClipboardImage()
            return
        }
        let raw = board.string(forType: .string)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
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
        guard let url = writeOutgoing(Data(text.utf8), name: "Clipboard", ext: "txt") else {
            feedback("Too big to send as text", failed: true)
            return
        }
        feedback("Long text · sending it as a file", symbol: "doc.text")
        if let id = server.sendFiles([url]) { outgoing = (id, 0) }
    }

    private func sendClipboardImage() {
        guard let image = NSImage(pasteboard: NSPasteboard.general), let tiff = image.tiffRepresentation,
              let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]),
              let url = writeOutgoing(png, name: "Clipboard image", ext: "png") else {
            feedback("Couldn't read the image on your clipboard", failed: true)
            return
        }
        sendFilesToPhone([url])
    }

    /// A file in the data folder's Outgoing folder, for sending. Older ones are removed unless a
    /// send is still reading them.
    private func writeOutgoing(_ data: Data, name: String, ext: String) -> URL? {
        let folder = Storage.directory.appendingPathComponent("Outgoing", isDirectory: true)
        let fm = FileManager.default
        if outgoing == nil { try? fm.removeItem(at: folder) }
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd 'at' HH.mm.ss"
        let stamp = formatter.string(from: Date())
        let url = ReceivedFiles.uniqueURL(for: "\(name) \(stamp).\(ext)", in: folder)
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
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
            // With the panel closed, progress shows in the notch (after the drop's pulse).
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                guard let self, self.outgoing?.id == id, !self.panelOpen else { return }
                self.show(.sending(id: id), for: nil)
            }
        } else {
            feedback("Couldn't read those files", failed: true)
        }
    }

    /// Picks files to send. The panel closes first so the open dialog isn't hidden under it.
    /// Lync is a menu bar app that isn't active, and since macOS 14 activation can be refused,
    /// so the dialog is brought to the front and made key itself rather than relying on it.
    func chooseFilesToSend() {
        closePanel()
        DispatchQueue.main.async { [weak self] in
            let panel = NSOpenPanel()
            panel.allowsMultipleSelection = true
            panel.canChooseDirectories = false
            panel.prompt = "Send to Phone"
            panel.message = "Choose files to send to \(self?.connectedPhone?.name ?? "your phone")"
            panel.level = .floating
            panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
            NSApp.activate(ignoringOtherApps: true)
            panel.begin { response in
                guard response == .OK else { return }
                let urls = panel.urls
                DispatchQueue.main.async { self?.sendFilesToPhone(urls) }
            }
            panel.orderFrontRegardless()
            panel.makeKey()
        }
    }

    /// While files are dragged over the notch, its drop zone, the menu bar icon, or near them
    /// (`source` says which): says where they'll go. It stays while any source still has the
    /// drag, so moving from the notch onto the card that opened under it doesn't flicker.
    func dropHover(_ entered: Bool, source: String) {
        // Over the open panel: it highlights itself; no card (the overlay is hidden then).
        if source == "panel" {
            panelDropHover = entered && connectedPhone != nil
            return
        }
        let card = dropHintCard
        if entered {
            dropHideWork?.cancel()
            dropSources.insert(source)
            guard live != card else { return }
            show(card, for: connectedPhone == nil ? 2.5 : nil)
            if connectedPhone == nil {
                // After the card has come out of the notch.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) { [weak self] in
                    guard let self, self.live == card else { return }
                    self.dropShake += 1
                }
            }
        } else {
            dropSources.remove(source)
            guard dropSources.isEmpty else { return }
            dropHideWork?.cancel()
            let work = DispatchWorkItem { [weak self] in
                guard let self, self.dropSources.isEmpty, !self.dropPulsing, self.isDropHint(self.live) else { return }
                self.hideLive()
            }
            dropHideWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: work)
        }
    }

    // MARK: - File drags anywhere on the Mac

    /// A drag of files started somewhere on the Mac (FileDragWatcher). Ignored without a phone.
    func fileDragBegan(files: [URL]) {
        guard connectedPhone != nil, !files.isEmpty, !panelOpen else { return }
        let images = files.filter(\.isImage)
        fileDrag = FileDragInfo(count: files.count, images: Array(images.prefix(3)), allImages: images.count == files.count)
    }

    /// The pointer came close to the notch or the menu bar icon, or moved away again.
    func fileDragNear(_ near: Bool) {
        guard var drag = fileDrag, drag.near != near else { return }
        drag.near = near
        fileDrag = drag
        dropHover(near, source: "near")
    }

    func fileDragEnded() {
        guard fileDrag != nil else { return }
        // A beat later: a drop on Lync is handled first, so its pulse isn't cut short.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
            guard let self else { return }
            self.fileDrag = nil
            self.dropHover(false, source: "near")
        }
    }

    /// Files were dropped and will be sent: a quick pulse on the card, then it goes back up.
    func dropAccepted() {
        let card = dropHintCard
        dropSources.removeAll()
        panelDropHover = false
        dropPulse += 1
        dropPulsing = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) { [weak self] in
            guard let self else { return }
            self.dropPulsing = false
            if self.live == card { self.hideLive() }
        }
    }

    /// The overlay lets drags through while it shows this, so the drop still reaches the notch.
    func isDropHint(_ card: LiveCard?) -> Bool {
        guard let card else { return false }
        return card == .dropZone || card == Self.notConnectedDropCard
    }

    private static let notConnectedDropCard = LiveCard.info(symbol: "iphone.slash", title: "Your phone isn't connected",
                                                            subtitle: "Connect it to send files")

    private var dropHintCard: LiveCard {
        connectedPhone != nil ? .dropZone : Self.notConnectedDropCard
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

    /// Notification apps whose notifications are texts: clicking their card opens Texts.
    func isTextsApp(_ packageName: String) -> Bool {
        let name = packageName.lowercased()
        return name.contains("messaging") || name.contains("mms") || name.hasSuffix(".sms")
    }

    /// A click on a notification card: Texts for a text message, otherwise the panel at that notification.
    func openNotification(_ key: String) {
        guard let notification = notification(key) else { openPanel(); return }
        hideLive()
        if isTextsApp(notification.packageName) {
            openMessages()
        } else {
            openPanel(scrollTo: "notif-\(key)")
        }
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
        if inlineReplyKey == key { inlineReplyKey = nil }
        if live == .notification(key: key) { hideLive() }
        server.replyToNotification(key: key, text: trimmed) { [weak self] ok in
            self?.feedback(ok ? "Reply sent" : "Couldn't send the reply",
                           subtitle: ok ? nil : "Check your phone is connected and try again", failed: !ok)
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

    /// Paired phones that are connected right now (the active one and any waiting in the background).
    func reachablePhoneIds() -> Set<String> {
        let keys = server.connectedPhoneKeys()
        return Set(pairedPhones.filter { keys.contains($0.publicKey) }.map(\.id))
    }

    /// Makes a paired phone that's connected in the background the one Lync shows.
    func makeActive(_ phone: PairedPhone) {
        server.makeActive(publicKey: phone.publicKey)
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
        loginItemNote = nil
        loginItemNeedsApproval = false
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            NSLog("Lync: launch at login change failed: \(error)")
            if SMAppService.mainApp.status != .requiresApproval {
                loginItemNote = "Couldn't change this: \(error.localizedDescription)"
            }
        }
        if enabled, SMAppService.mainApp.status == .requiresApproval {
            loginItemNote = "Allow Lync in System Settings › General › Login Items"
            loginItemNeedsApproval = true
        }
        launchAtLogin = Self.loginItemEnabled
    }

    func openLoginItemsSettings() {
        SMAppService.openSystemSettingsLoginItems()
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

    /// A copy of everything on the clipboard, to put back later.
    static func snapshot() -> [NSPasteboardItem] {
        (NSPasteboard.general.pasteboardItems ?? []).map { item in
            let copy = NSPasteboardItem()
            for type in item.types { if let data = item.data(forType: type) { copy.setData(data, forType: type) } }
            return copy
        }
    }

    /// Replaces the clipboard with `items` (from `snapshot()`), or just clears it.
    static func restore(_ items: [NSPasteboardItem]) {
        let board = NSPasteboard.general
        board.clearContents()
        if !items.isEmpty { board.writeObjects(items) }
    }
}

extension URL {
    var isImage: Bool {
        (try? resourceValues(forKeys: [.contentTypeKey]).contentType?.conforms(to: .image)) ?? false
    }
}
