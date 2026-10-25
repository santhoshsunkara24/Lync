import AppKit
import Combine
import SwiftUI

// MARK: - Model

struct SmsThread: Identifiable, Equatable {
    let id: String
    let address: String
    let name: String?
    let snippet: String
    let date: Date
    let unread: Int

    var title: String { name ?? address }
}

struct SmsMessage: Identifiable, Equatable {
    let id: String
    let body: String
    let date: Date
    let outgoing: Bool
    let failed: Bool
    let sim: String?
    var pending = false
    /// Why sending failed, shown on the bubble.
    var error: ProblemText?
    /// The phone never confirmed it: it may have gone, so the bubble says check before resending.
    var unconfirmed = false

    /// The same message under another id.
    func with(id: String) -> SmsMessage {
        var copy = SmsMessage(id: id, body: body, date: date, outgoing: outgoing, failed: failed, sim: sim, pending: pending, error: error)
        copy.unconfirmed = unconfirmed
        return copy
    }
}

/// A contact from the phone, for the "To:" field.
struct ContactMatch: Identifiable, Equatable {
    let name: String
    let number: String
    var id: String { name + "|" + number }
}

struct SimCard: Identifiable, Hashable {
    let id: Int
    let label: String
}

/// Conversations fetched live from the phone. Nothing is saved on the Mac.
final class MessagesModel: ObservableObject {
    enum Status: Equatable {
        case idle
        case loading
        /// The conversations can't be listed: why and what to do; `retry` offers Try again.
        case unavailable(symbol: String, ProblemText, retry: Bool)
    }

    @Published private(set) var threads: [SmsThread] = []
    @Published private(set) var messages: [SmsMessage] = []
    @Published private(set) var sims: [SimCard] = []
    @Published private(set) var status: Status = .idle
    @Published private(set) var loadingThread = false
    @Published var selectedThread: String? {
        didSet { if selectedThread != oldValue { messages = []; sentIDs = [:]; loadMessages() } }
    }
    @Published var selectedSim: Int?
    @Published var search = ""
    /// Set while writing to a number that has no conversation yet.
    @Published var newRecipient: String?
    /// The contact picked for `newRecipient`, if it came from the search.
    @Published private(set) var recipientName: String?
    /// The phone's contacts matching what's typed in "To:" (at most 20).
    @Published private(set) var contactResults: [ContactMatch] = []
    /// Why the phone's contacts can't be searched (not allowed, or no answer).
    @Published private(set) var contactsNote: ProblemText?
    /// Why the open conversation couldn't be loaded, with Try again.
    @Published private(set) var threadProblem: ProblemText?

    private let server: LinkServer
    private var observer: NSObjectProtocol?
    private var contactSearch: AnyCancellable?
    /// Only the newest search's answer counts.
    private var contactQuery = 0
    /// The number just picked from the results: it isn't searched again.
    private var pickedNumber: String?
    /// The phone's id for a text sent from here → the id its "Sending…" bubble had, so the
    /// bubble stays the same view and settles in place when the phone confirms it.
    private var sentIDs: [String: String] = [:]

    init(server: LinkServer) {
        self.server = server
        observer = NotificationCenter.default.addObserver(forName: .smsChanged, object: nil, queue: .main) { [weak self] _ in
            self?.refresh(quiet: true)
        }
        // Searches the phone's contacts as "To:" is typed, 250 ms after the last key.
        contactSearch = $newRecipient
            .removeDuplicates()
            .debounce(for: .milliseconds(250), scheduler: RunLoop.main)
            .sink { [weak self] query in self?.searchContacts(query) }
    }

    /// Asks the phone for contacts matching `raw` (`contacts`, answered with `sms_result`).
    private func searchContacts(_ raw: String?) {
        contactQuery += 1
        let token = contactQuery
        guard let query = raw?.trimmingCharacters(in: .whitespaces), !query.isEmpty else {
            contactResults = []
            return
        }
        if query == pickedNumber { return }
        recipientName = nil
        server.request("contacts", ["q": query]) { [weak self] result in
            guard let self, token == self.contactQuery, self.newRecipient != nil else { return }
            guard case .reply(let reply) = result else {
                // No answer: the number can still be typed.
                self.contactResults = []
                self.contactsNote = Problem.contactsFailed
                return
            }
            if let err = reply.string("err") {
                // Only a permission problem asks for Contacts; anything else says type the number.
                self.contactsNote = err == PhoneError.contactsPermission || err == PhoneError.permission
                    ? Problem.contactsPermission : Problem.contactsFailed
                self.contactResults = []
                return
            }
            self.contactsNote = nil
            let json = (try? JSONSerialization.jsonObject(with: reply.body)) as? [String: Any]
            self.contactResults = (json?["items"] as? [[String: Any]] ?? []).prefix(20).compactMap { item in
                guard let number = item["number"] as? String, !number.isEmpty else { return nil }
                return ContactMatch(name: item["name"] as? String ?? number, number: number)
            }
        }
    }

    /// For the snapshot renderer: shows results without a phone.
    func showContactResults(_ results: [ContactMatch], unavailable: Bool = false) {
        newRecipient = ""
        contactResults = results
        contactsNote = unavailable ? Problem.contactsPermission : nil
    }

    func pickContact(_ contact: ContactMatch) {
        pickedNumber = contact.number
        recipientName = contact.name
        newRecipient = contact.number
        contactResults = []
        contactQuery += 1
    }

    var filteredThreads: [SmsThread] {
        let query = search.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return threads }
        return threads.filter {
            $0.title.localizedCaseInsensitiveContains(query) || $0.address.contains(query)
                || $0.snippet.localizedCaseInsensitiveContains(query)
        }
    }

    var currentThread: SmsThread? { threads.first { $0.id == selectedThread } }

    func refresh(quiet: Bool = false) {
        if !quiet { status = .loading }
        server.request("sms_threads") { [weak self] result in
            guard let self else { return }
            let reply: Message
            switch result {
            case .reply(let message):
                reply = message
            case .notConnected(let bluetoothOnly):
                if !quiet || self.threads.isEmpty {
                    self.status = bluetoothOnly ? .unavailable(symbol: "wifi.exclamationmark", Problem.textsNeedWifi, retry: false)
                                                : .unavailable(symbol: "iphone.slash", Problem.textsNotConnected, retry: false)
                }
                return
            case .disconnected:
                if !quiet || self.threads.isEmpty { self.status = .unavailable(symbol: "iphone.slash", Problem.textsNotConnected, retry: false) }
                return
            case .timedOut:
                // Connected, but no answer: not "not connected".
                if !quiet || self.threads.isEmpty { self.status = .unavailable(symbol: "hourglass", Problem.textsTimedOut, retry: true) }
                return
            }
            if let err = reply.string("err") {
                let symbol = err == PhoneError.permission ? "lock" : err == PhoneError.textsOff ? "message.badge" : "exclamationmark.circle"
                self.status = .unavailable(symbol: symbol, Problem.textsUnavailable(err),
                                           retry: err != PhoneError.permission && err != PhoneError.textsOff)
                return
            }
            guard let json = try? JSONSerialization.jsonObject(with: reply.body) as? [String: Any] else {
                // Something this Mac doesn't understand: most likely an older phone app.
                self.status = .unavailable(symbol: "arrow.down.app", Problem.textsOutdated, retry: false)
                return
            }
            self.threads = (json["threads"] as? [[String: Any]] ?? []).compactMap(Self.thread)
            self.sims = (json["sims"] as? [[String: Any]] ?? []).compactMap { item in
                guard let id = (item["id"] as? NSNumber)?.intValue, let label = item["label"] as? String else { return nil }
                return SimCard(id: id, label: label)
            }
            if self.selectedSim == nil || !self.sims.contains(where: { $0.id == self.selectedSim }) {
                self.selectedSim = self.sims.first?.id
            }
            self.status = .idle
            if self.selectedThread == nil, self.newRecipient == nil { self.selectedThread = self.threads.first?.id }
            if let recipient = self.newRecipient,
               let match = self.threads.first(where: { Self.sameNumber($0.address, recipient) }) {
                // A text to a new number created its conversation; switch to it.
                self.newRecipient = nil
                self.selectedThread = match.id
            } else if quiet {
                self.loadMessages(quiet: true)
            }
        }
    }

    func loadMessages(quiet: Bool = false) {
        guard let thread = selectedThread else { return }
        if !quiet { loadingThread = true; threadProblem = nil }
        server.request("sms_thread", ["thread": thread]) { [weak self] result in
            guard let self, self.selectedThread == thread else { return }
            self.loadingThread = false
            let failure: ProblemText?
            var json: [String: Any]?
            switch result {
            case .reply(let reply):
                if let err = reply.string("err") {
                    let text = Problem.textsUnavailable(err)
                    // Permission and Texts off say what to do; anything else is "couldn't load".
                    failure = err == PhoneError.permission || err == PhoneError.textsOff
                        ? text : ProblemText(title: Problem.conversationFailed, detail: text.detail)
                } else if let object = try? JSONSerialization.jsonObject(with: reply.body) as? [String: Any] {
                    json = object
                    failure = nil
                } else {
                    failure = Problem.textsOutdated
                }
            case .timedOut: failure = ProblemText(title: Problem.conversationFailed, detail: Problem.textsTimedOut.detail)
            case .notConnected, .disconnected: failure = nil // The window says the phone isn't connected.
            }
            // A quiet refresh that fails keeps what's on screen.
            if let failure, !quiet || self.messages.isEmpty { self.threadProblem = failure }
            guard let json else { return }
            self.threadProblem = nil
            let pending = self.messages.filter(\.pending)
            var loaded = (json["messages"] as? [[String: Any]] ?? []).compactMap(Self.message)
            // The phone's copy of a text sent from here takes over its "Sending…" bubble's id.
            for p in pending {
                if let sent = loaded.last(where: { $0.outgoing && $0.body == p.body && self.sentIDs[$0.id] == nil }) {
                    self.sentIDs[sent.id] = p.id
                }
            }
            if !self.sentIDs.isEmpty { loaded = loaded.map { m in self.sentIDs[m.id].map { m.with(id: $0) } ?? m } }
            // Keep "Sending…" bubbles until the phone's copy shows up.
            let stillPending = pending.filter { p in !loaded.contains { $0.id == p.id } }
            self.messages = loaded + stillPending
        }
    }

    func startNewMessage() {
        selectedThread = nil
        messages = []
        pickedNumber = nil
        recipientName = nil
        contactResults = []
        newRecipient = ""
    }

    /// Esc while writing to a new number: back to the first conversation.
    func cancelNewMessage() {
        guard newRecipient != nil else { return }
        contactResults = []
        recipientName = nil
        newRecipient = nil
        selectedThread = threads.first?.id
    }

    /// Sends a text that failed again.
    func retry(_ message: SmsMessage) {
        messages.removeAll { $0.id == message.id }
        send(message.body)
    }

    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let address: String
        if let recipient = newRecipient?.trimmingCharacters(in: .whitespaces), !recipient.isEmpty {
            address = recipient
        } else if let thread = currentThread {
            address = thread.address
        } else {
            return
        }
        let sim = sims.count > 1 ? selectedSim : nil
        let simLabel = sims.first { $0.id == sim }?.label
        let placeholder = SmsMessage(id: "pending-\(UUID().uuidString)", body: trimmed, date: Date(), outgoing: true,
                                     failed: false, sim: simLabel, pending: true)
        messages.append(placeholder)
        server.sendSMS(to: address, text: trimmed, sim: sim) { [weak self] result in
            guard let self else { return }
            if result.ok {
                self.refresh(quiet: true)
            } else {
                // The reason shows on the failed bubble itself, next to Try again (never automatic:
                // a text that wasn't confirmed may still have gone).
                if let index = self.messages.firstIndex(where: { $0.id == placeholder.id }) {
                    var failed = SmsMessage(id: placeholder.id, body: trimmed, date: Date(), outgoing: true,
                                            failed: true, sim: simLabel, error: Problem.smsFailed(result))
                    failed.unconfirmed = result == .timedOut
                    self.messages[index] = failed
                }
            }
        }
    }

    private static func thread(_ item: [String: Any]) -> SmsThread? {
        guard let id = item["thread"] as? String else { return nil }
        return SmsThread(
            id: id,
            address: item["address"] as? String ?? "",
            name: item["name"] as? String,
            snippet: item["snippet"] as? String ?? "",
            date: date(item["date"]),
            unread: (item["unread"] as? NSNumber)?.intValue ?? 0
        )
    }

    private static func message(_ item: [String: Any]) -> SmsMessage? {
        guard let id = item["id"] as? String else { return nil }
        return SmsMessage(
            id: id,
            body: item["body"] as? String ?? "",
            date: date(item["date"]),
            outgoing: (item["out"] as? NSNumber)?.boolValue ?? false,
            failed: (item["failed"] as? NSNumber)?.boolValue ?? false,
            sim: item["sim"] as? String
        )
    }

    private static func date(_ value: Any?) -> Date {
        Date(timeIntervalSince1970: ((value as? NSNumber)?.doubleValue ?? 0) / 1000)
    }

    static func sameNumber(_ a: String, _ b: String) -> Bool {
        let digitsA = a.filter(\.isNumber), digitsB = b.filter(\.isNumber)
        guard digitsA.count >= 7, digitsB.count >= 7 else { return a == b }
        return digitsA.hasSuffix(String(digitsB.suffix(10))) || digitsB.hasSuffix(String(digitsA.suffix(10)))
    }
}

// MARK: - Window

/// Opens the Messages window. The app shows in the Dock and app switcher while it's open.
final class MessagesWindowController: NSObject, NSWindowDelegate {
    private let model: MessagesModel
    private let appModel: AppModel
    private var window: NSWindow?
    private var titleObserver: AnyCancellable?

    init(server: LinkServer, appModel: AppModel) {
        model = MessagesModel(server: server)
        self.appModel = appModel
    }

    func show() {
        if window == nil {
            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 880, height: 600),
                styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                backing: .buffered,
                defer: false
            )
            window.title = "Lync — Texts"
            CapturePrivacy.apply(window)
            window.titlebarAppearsTransparent = true
            // Same dark, phone-coloured style as the panel.
            window.appearance = NSAppearance(named: .darkAqua)
            window.isReleasedWhenClosed = false
            window.minSize = NSSize(width: 640, height: 420)
            window.contentView = NSHostingView(rootView: MessagesView(model: model, appModel: appModel)
                .environmentObject(appModel.themeStore))
            window.center()
            window.setFrameAutosaveName("WallhopMessages")
            window.delegate = self
            self.window = window
            // "Lync — Texts", with the open conversation underneath.
            titleObserver = model.$selectedThread.combineLatest(model.$threads).sink { [weak window] id, threads in
                window?.subtitle = threads.first { $0.id == id }?.title ?? ""
            }
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
        model.refresh()
    }

    func windowWillClose(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
    }
}

// MARK: - Views

struct MessagesView: View {
    @ObservedObject var model: MessagesModel
    @ObservedObject var appModel: AppModel

    var body: some View {
        NavigationSplitView {
            ThreadList(model: model)
                .navigationSplitViewColumnWidth(min: 240, ideal: 280, max: 360)
        } detail: {
            ConversationView(model: model, connected: appModel.connectedPhone != nil,
                             bluetoothOnly: appModel.connectedPhone == nil && appModel.bluetoothPhone != nil)
                .background(ThemedBackground())
        }
        .environment(\.colorScheme, .dark)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.startNewMessage()
                } label: {
                    Label { Text("New message") } icon: { NudgeImage("square.and.pencil") }
                }
                .help("New message")
                .keyboardShortcut("n")
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.refresh()
                } label: {
                    Label { Text("Refresh") } icon: { NudgeImage("arrow.clockwise") }
                }
                .help("Refresh")
                .keyboardShortcut("r")
            }
        }
    }
}

private struct ThreadList: View {
    @ObservedObject var model: MessagesModel

    var body: some View {
        VStack(spacing: 0) {
            switch model.status {
            case .unavailable(let symbol, let problem, let retry):
                StatusNote(symbol: symbol, title: problem.title, text: problem.detail ?? "",
                           retry: retry ? { model.refresh() } : nil)
            default:
                List(selection: $model.selectedThread) {
                    ForEach(model.filteredThreads) { thread in
                        ThreadRow(thread: thread).tag(thread.id)
                            .contextMenu {
                                Button("Copy number") { Clipboard.copy(thread.address) }
                                if thread.name != nil { Button("Copy name") { Clipboard.copy(thread.title) } }
                            }
                    }
                }
                .listStyle(.sidebar)
                .overlay {
                    if model.status == .loading && model.threads.isEmpty {
                        ProgressView().controlSize(.small)
                    } else if model.status != .loading && model.filteredThreads.isEmpty {
                        let query = model.search.trimmingCharacters(in: .whitespaces)
                        if query.isEmpty {
                            MessagesEmptyState(symbol: "message", title: "No conversations yet",
                                               text: "Texts on your phone show up here. Start one with the pencil above.")
                        } else {
                            MessagesEmptyState(symbol: "magnifyingglass", title: "No results",
                                               text: "Nothing matches \u{201C}\(query)\u{201D}.")
                        }
                    }
                }
            }
        }
        .searchable(text: $model.search, placement: .sidebar, prompt: "Search")
    }
}

private struct ThreadRow: View {
    let thread: SmsThread
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Avatar(name: thread.name, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(thread.title)
                        .font(.wh(13, thread.unread > 0 ? .semibold : .medium))
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(shortDate(thread.date))
                        .font(.wh(11, .medium))
                        .foregroundStyle(.secondary)
                }
                HStack(alignment: .top) {
                    Text(thread.snippet)
                        .font(.wh(12, .medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                    Spacer(minLength: 4)
                    if thread.unread > 0 {
                        Circle().fill(theme.theme.pale).frame(width: 8, height: 8).padding(.top, 4)
                            .accessibilityLabel("Unread")
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .padding(.horizontal, 6)
        .rowHover(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .padding(.horizontal, -6)
    }
}

private struct ConversationView: View {
    @ObservedObject var model: MessagesModel
    let connected: Bool
    /// The phone reaches this Mac over Bluetooth only: texts need Wi‑Fi.
    var bluetoothOnly = false
    @EnvironmentObject var theme: ThemeStore
    @State private var draft = ""
    @FocusState private var composerFocused: Bool
    /// The contact match chosen with the arrow keys.
    @State private var highlighted: Int?
    /// How many messages were on screen before the latest change: only messages added to an
    /// open conversation slide in, not a whole conversation loading.
    @State private var shownCount = 0

    private var appending: Bool { shownCount > 0 && model.messages.count > shownCount }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(Array(model.messages.enumerated()), id: \.element.id) { index, message in
                            if showsDay(at: index) {
                                Text(dayLabel(message.date))
                                    .font(.wh(11, .medium))
                                    .foregroundStyle(.secondary)
                                    .padding(.vertical, 8)
                            }
                            Bubble(message: message, sender: model.currentThread?.title ?? model.recipientName ?? "Them",
                                   showSim: model.sims.count > 1, connected: connected) {
                                model.retry(message)
                            }
                            .id(message.id)
                            .transition(WMotion.transition(.offset(y: 16).combined(with: .opacity)))
                        }
                    }
                    .padding(16)
                    .animation(appending ? WMotion.animation(WMotion.smooth) : nil, value: model.messages.count)
                }
                .overlay {
                    if model.loadingThread && model.messages.isEmpty {
                        ProgressView().controlSize(.small)
                    } else if let problem = model.threadProblem, model.messages.isEmpty, connected {
                        VStack(spacing: 8) {
                            Text(problem.title).font(.wh(13, .semibold))
                            if let detail = problem.detail {
                                Text(detail).font(.wh(12, .medium)).foregroundStyle(.secondary).multilineTextAlignment(.center)
                            }
                            Button("Try again") { model.loadMessages() }
                                .buttonStyle(WPill(prominent: false, compact: true))
                                .padding(.top, 4)
                        }
                        .padding(20)
                    } else if model.selectedThread == nil && model.newRecipient == nil {
                        Text(model.threads.isEmpty ? "No conversation open" : "Choose a conversation")
                            .font(.wh(13, .medium)).foregroundStyle(.secondary)
                    }
                }
                .onChange(of: model.messages.count) { _, count in
                    // A message arriving in the open conversation scrolls into view; another
                    // conversation (or this one loading) just opens at its latest message.
                    if let last = model.messages.last {
                        if appending {
                            withMotion(WMotion.smooth) { proxy.scrollTo(last.id, anchor: .bottom) }
                        } else {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                    }
                    shownCount = count
                }
                .onAppear {
                    if let last = model.messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                    shownCount = model.messages.count
                }
            }
            if !connected {
                Text(bluetoothOnly ? "\(Problem.textsNeedWifi.title). \(Problem.textsNeedWifi.detail ?? "")" : Problem.textsConnectHint)
                    .font(.wh(12, .medium))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)
                    .padding(.horizontal, 16)
            }
            composer
        }
    }

    @ViewBuilder private var header: some View {
        VStack(spacing: 0) {
            headerRow
            if model.newRecipient != nil {
                ContactResults(model: model, highlighted: $highlighted) { pick($0) }
            }
        }
    }

    /// Picks a contact from the search and moves on to the message.
    private func pick(_ contact: ContactMatch) {
        model.pickContact(contact)
        highlighted = nil
        composerFocused = true
    }

    @ViewBuilder private var headerRow: some View {
        HStack(spacing: 10) {
            if model.newRecipient != nil {
                Text("To:").foregroundStyle(.secondary)
                TextField("Name or phone number", text: Binding(
                    get: { model.newRecipient ?? "" },
                    set: { model.newRecipient = $0; highlighted = nil }
                ))
                .textFieldStyle(.plain)
                .font(.wh(14, .medium))
                .accessibilityLabel("To")
                .onExitCommand { model.cancelNewMessage() }
                // ↑/↓ move through the matches, Return picks one (or goes on with the number as typed).
                .onKeyPress(.downArrow) {
                    guard !model.contactResults.isEmpty else { return .ignored }
                    highlighted = min((highlighted ?? -1) + 1, model.contactResults.count - 1)
                    return .handled
                }
                .onKeyPress(.upArrow) {
                    guard let current = highlighted else { return .ignored }
                    highlighted = current > 0 ? current - 1 : nil
                    return .handled
                }
                .onSubmit {
                    if let index = highlighted, model.contactResults.indices.contains(index) {
                        pick(model.contactResults[index])
                    } else if model.contactResults.count == 1 {
                        pick(model.contactResults[0])
                    } else {
                        composerFocused = true
                    }
                }
                if let name = model.recipientName {
                    Text(name).font(.wh(12, .semibold)).foregroundStyle(.secondary).lineLimit(1)
                }
                Button("Cancel") { model.cancelNewMessage() }
                    .buttonStyle(WPill(prominent: false, compact: true))
                    .help("Cancel the new message (Esc)")
            } else if let thread = model.currentThread {
                Avatar(name: thread.name, size: 30)
                VStack(alignment: .leading, spacing: 0) {
                    Text(thread.title).font(.wh(14, .semibold))
                    if thread.name != nil {
                        Text(thread.address).font(.wh(11, .medium)).foregroundStyle(.secondary)
                    }
                }
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if model.sims.count > 1 {
                Picker("SIM", selection: Binding(
                    get: { model.selectedSim ?? model.sims[0].id },
                    set: { model.selectedSim = $0 }
                )) {
                    ForEach(model.sims) { sim in Text(sim.label).tag(sim.id) }
                }
                .labelsHidden()
                .fixedSize()
                .help("Send from")
            }
            TextField("Text message", text: $draft, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...6)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Capsule().fill(Color.white.opacity(0.1)))
                .focused($composerFocused)
                .onSubmit(send)
                .onExitCommand { model.cancelNewMessage() }
            Button(action: send) {
                NudgeImage("arrow.up")
                    .font(.wh(13, .bold))
                    .foregroundStyle(canSend ? theme.theme.onPale : Color.white.opacity(0.5))
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(canSend ? theme.theme.pale : Color.white.opacity(0.12)))
            }
            .buttonStyle(WShapePress(shape: Circle()))
            .disabled(!canSend)
            .help("Send")
            .accessibilityLabel("Send")
            .keyboardShortcut(.return, modifiers: .command)
        }
        .padding(12)
        .disabled(!connected)
    }

    private var canSend: Bool {
        connected && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && (model.currentThread != nil || !(model.newRecipient ?? "").trimmingCharacters(in: .whitespaces).isEmpty)
    }

    private func send() {
        guard canSend else { return }
        model.send(draft)
        draft = ""
        composerFocused = true
    }

    private func showsDay(at index: Int) -> Bool {
        guard index > 0 else { return true }
        return !Calendar.current.isDate(model.messages[index].date, inSameDayAs: model.messages[index - 1].date)
    }
}

private struct Bubble: View {
    let message: SmsMessage
    /// The other person, for VoiceOver ("Anna: …").
    let sender: String
    let showSim: Bool
    let connected: Bool
    let retry: () -> Void
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 80) }
            VStack(alignment: message.outgoing ? .trailing : .leading, spacing: 2) {
                Text(message.body)
                    .font(.wh(13, .medium))
                    .textSelection(.enabled)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .foregroundStyle(message.outgoing ? theme.theme.onPale : Color.white)
                    .background(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(message.outgoing ? theme.theme.pale : Color.white.opacity(0.1))
                    )
                    // Sending: a little smaller and paler until the phone confirms it, then settles.
                    .opacity(message.pending ? 0.6 : 1)
                    .scaleEffect(message.pending ? 0.98 : 1, anchor: .bottomTrailing)
                    .animation(WMotion.animation(WMotion.quick), value: message.pending)
                    .contextMenu {
                        if message.failed && message.outgoing {
                            Button("Try again", action: retry).disabled(!connected)
                        }
                        Button("Copy") { Clipboard.copy(message.body) }
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(message.outgoing ? "You" : sender): \(message.body)")
                    .accessibilityActions {
                        if message.failed && message.outgoing && connected { Button("Try again", action: retry) }
                        Button("Copy") { Clipboard.copy(message.body) }
                    }
                HStack(spacing: 6) {
                    if message.failed {
                        // A text that wasn't confirmed may have gone: "Send again", after checking.
                        Label { Text(message.unconfirmed ? (message.error?.title ?? "Not confirmed")
                                     : message.error.map { "Not sent · \($0.title)" } ?? "Not sent") } icon: { NudgeImage("exclamationmark.circle") }
                            .foregroundStyle(WTheme.danger)
                        Button(message.unconfirmed ? "Send again" : "Try again", action: retry).buttonStyle(WPlainPress()).foregroundStyle(theme.theme.pale)
                            .disabled(!connected)
                        Button("Copy") { Clipboard.copy(message.body) }.buttonStyle(WPlainPress()).foregroundStyle(theme.theme.pale)
                    } else if message.pending {
                        Text("Sending…").transition(.opacity)
                    } else {
                        Text(message.date.formatted(date: .omitted, time: .shortened)).transition(.opacity)
                    }
                    if showSim, let sim = message.sim { Text("· \(sim)") }
                }
                .font(.wh(11, .medium))
                .foregroundStyle(.secondary)
                .animation(WMotion.animation(WMotion.quick), value: message.pending)
                if message.failed, let detail = message.error?.detail {
                    Text(detail).font(.wh(11, .medium)).foregroundStyle(.secondary)
                }
            }
            if !message.outgoing { Spacer(minLength: 80) }
        }
    }
}

private struct Avatar: View {
    let name: String?
    let size: CGFloat
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        ZStack {
            Circle().fill(color.gradient)
            if let initials {
                Text(initials).font(.wh(size * 0.38, .semibold)).foregroundStyle(.white)
            } else {
                NudgeImage("person.fill").font(.wh(size * 0.42)).foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
    }

    private var initials: String? {
        guard let name, !name.isEmpty else { return nil }
        let letters = name.split(separator: " ").prefix(2).compactMap(\.first)
        return letters.isEmpty ? nil : String(letters).uppercased()
    }

    /// From the phone's palette, so avatars match the rest of the window.
    private var color: Color {
        let palette: [Color] = [theme.theme.top, theme.theme.peek, theme.theme.mid, theme.theme.card]
        let hash = (name ?? "").unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return palette[abs(hash) % palette.count]
    }
}

/// Matches from the phone's contacts under the "To:" field: name and number, ↑/↓ to choose.
struct ContactResults: View {
    @ObservedObject var model: MessagesModel
    @Binding var highlighted: Int?
    let pick: (ContactMatch) -> Void
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        if let note = model.contactsNote, model.contactResults.isEmpty {
            HStack(spacing: 8) {
                NudgeImage("person.crop.circle.badge.exclamationmark").foregroundStyle(WTheme.secondary)
                Text([note.title, note.detail].compactMap { $0 }.joined(separator: " · "))
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                Spacer()
            }
            .padding(.horizontal, 16).padding(.bottom, 10)
        } else if !model.contactResults.isEmpty {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(model.contactResults.enumerated()), id: \.element.id) { index, contact in
                            Button { pick(contact) } label: {
                                HStack(spacing: 10) {
                                    Text(contact.name).font(.wh(13, .semibold)).lineLimit(1)
                                    Spacer(minLength: 8)
                                    Text(contact.number).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(1)
                                }
                                .padding(.horizontal, 12).frame(height: 34)
                                .background(RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .fill(highlighted == index ? Color.white.opacity(0.14) : Color.clear))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .rowHover(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .id(index)
                            .accessibilityLabel("\(contact.name), \(contact.number)")
                            .accessibilityAddTraits(highlighted == index ? .isSelected : [])
                        }
                    }
                    .padding(6)
                }
                .frame(maxHeight: min(CGFloat(model.contactResults.count) * 34 + 12, 220))
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(theme.theme.card))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.white.opacity(0.1), lineWidth: 1))
                .padding(.horizontal, 12).padding(.bottom, 10)
                .onChange(of: highlighted) { _, index in if let index { proxy.scrollTo(index) } }
                .accessibilityElement(children: .contain)
                .accessibilityLabel("Contacts")
            }
        }
    }
}

/// No conversations, or nothing matching the search.
struct MessagesEmptyState: View {
    let symbol: String
    let title: String
    let text: String

    var body: some View {
        VStack(spacing: 8) {
            NudgeImage(symbol).font(.wh(22, .medium)).foregroundStyle(WTheme.secondary)
                .frame(width: 52, height: 52).background(Circle().fill(WTheme.chip))
            Text(title).font(.wh(13, .semibold))
            Text(text).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).multilineTextAlignment(.center)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
    }
}

private struct StatusNote: View {
    let symbol: String
    let title: String
    let text: String
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 8) {
            Spacer()
            NudgeImage(symbol).font(.wh(24, .medium)).foregroundStyle(.secondary)
            Text(title).font(.wh(13, .semibold)).multilineTextAlignment(.center)
            Text(text).font(.wh(12, .medium)).foregroundStyle(.secondary).multilineTextAlignment(.center)
            if let retry {
                Button("Try again", action: retry).buttonStyle(WPill(prominent: false, compact: true)).padding(.top, 4)
            }
            Spacer()
        }
        .padding(20)
        .frame(maxWidth: .infinity)
    }
}

private func shortDate(_ date: Date) -> String {
    if Calendar.current.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
    if Calendar.current.isDateInYesterday(date) { return "Yesterday" }
    return date.formatted(.dateTime.day().month(.abbreviated))
}

private func dayLabel(_ date: Date) -> String {
    if Calendar.current.isDateInToday(date) { return "Today" }
    if Calendar.current.isDateInYesterday(date) { return "Yesterday" }
    return date.formatted(.dateTime.weekday(.wide).day().month(.wide))
}


/// The phone's colour at the top of the conversation, fading to ink.
private struct ThemedBackground: View {
    @EnvironmentObject var theme: ThemeStore
    var body: some View {
        LinearGradient(stops: [.init(color: theme.theme.mid, location: 0), .init(color: WTheme.ink, location: 0.6)],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }
}
