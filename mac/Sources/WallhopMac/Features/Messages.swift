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
        case notConnected
        case needsPermission
        case failed(String)
    }

    @Published private(set) var threads: [SmsThread] = []
    @Published private(set) var messages: [SmsMessage] = []
    @Published private(set) var sims: [SimCard] = []
    @Published private(set) var status: Status = .idle
    @Published private(set) var loadingThread = false
    @Published var selectedThread: String? {
        didSet { if selectedThread != oldValue { messages = []; loadMessages() } }
    }
    @Published var selectedSim: Int?
    @Published var search = ""
    /// Set while writing to a number that has no conversation yet.
    @Published var newRecipient: String?

    private let server: LinkServer
    private var observer: NSObjectProtocol?

    init(server: LinkServer) {
        self.server = server
        observer = NotificationCenter.default.addObserver(forName: .smsChanged, object: nil, queue: .main) { [weak self] _ in
            self?.refresh(quiet: true)
        }
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
        server.request("sms_threads") { [weak self] reply in
            guard let self else { return }
            guard let reply else {
                if !quiet || self.threads.isEmpty { self.status = .notConnected }
                return
            }
            if reply.string("err") == "permission" {
                self.status = .needsPermission
                return
            }
            guard let json = try? JSONSerialization.jsonObject(with: reply.body) as? [String: Any] else {
                self.status = .failed("The phone sent something unexpected.")
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
        if !quiet { loadingThread = true }
        server.request("sms_thread", ["thread": thread]) { [weak self] reply in
            guard let self, self.selectedThread == thread else { return }
            self.loadingThread = false
            guard let reply, let json = try? JSONSerialization.jsonObject(with: reply.body) as? [String: Any] else { return }
            let pending = self.messages.filter(\.pending)
            let loaded = (json["messages"] as? [[String: Any]] ?? []).compactMap(Self.message)
            // Keep "Sending…" bubbles until the phone's copy shows up.
            let stillPending = pending.filter { p in !loaded.contains { $0.outgoing && $0.body == p.body } }
            self.messages = loaded + stillPending
        }
    }

    func startNewMessage() {
        selectedThread = nil
        messages = []
        newRecipient = ""
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
        server.sendSMS(to: address, text: trimmed, sim: sim) { [weak self] error in
            guard let self else { return }
            if let error {
                if let index = self.messages.firstIndex(where: { $0.id == placeholder.id }) {
                    self.messages[index] = SmsMessage(id: placeholder.id, body: trimmed, date: Date(), outgoing: true,
                                                      failed: true, sim: simLabel)
                }
                self.status = .failed(error)
            } else {
                self.refresh(quiet: true)
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
            window.title = "Messages"
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
            ConversationView(model: model, connected: appModel.connectedPhone != nil)
                .background(ThemedBackground())
        }
        .environment(\.colorScheme, .dark)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.startNewMessage()
                } label: {
                    Label("New message", systemImage: "square.and.pencil")
                }
                .help("New message")
                .keyboardShortcut("n")
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.refresh()
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
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
            case .notConnected:
                StatusNote(symbol: "iphone.slash", title: "Phone not connected",
                           text: "Open Lync on your phone and keep it on the same Wi-Fi.")
            case .needsPermission:
                StatusNote(symbol: "lock", title: "Allow texts on your phone",
                           text: "In Lync on your phone, tap Allow next to \u{201C}Texts on your Mac\u{201D}.")
            default:
                List(selection: $model.selectedThread) {
                    ForEach(model.filteredThreads) { thread in
                        ThreadRow(thread: thread).tag(thread.id)
                    }
                }
                .listStyle(.sidebar)
                .overlay {
                    if model.status == .loading && model.threads.isEmpty {
                        ProgressView().controlSize(.small)
                    }
                }
            }
        }
        .searchable(text: $model.search, placement: .sidebar, prompt: "Search")
    }
}

private struct ThreadRow: View {
    let thread: SmsThread

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Avatar(name: thread.name, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(thread.title)
                        .font(.system(size: 13, weight: thread.unread > 0 ? .semibold : .medium))
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
                        Circle().fill(Color.accentColor).frame(width: 8, height: 8).padding(.top, 4)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ConversationView: View {
    @ObservedObject var model: MessagesModel
    let connected: Bool
    @EnvironmentObject var theme: ThemeStore
    @State private var draft = ""
    @FocusState private var composerFocused: Bool

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
                            Bubble(message: message, showSim: model.sims.count > 1)
                                .id(message.id)
                        }
                    }
                    .padding(16)
                }
                .overlay {
                    if model.loadingThread && model.messages.isEmpty {
                        ProgressView().controlSize(.small)
                    } else if model.selectedThread == nil && model.newRecipient == nil {
                        Text("Choose a conversation").foregroundStyle(.secondary)
                    }
                }
                .onChange(of: model.messages.count) { _, _ in
                    if let last = model.messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
                .onAppear {
                    if let last = model.messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            if case .failed(let error) = model.status {
                Text(error)
                    .font(.wh(11, .medium))
                    .foregroundStyle(.red)
                    .padding(.top, 6)
            }
            composer
        }
    }

    @ViewBuilder private var header: some View {
        HStack(spacing: 10) {
            if model.newRecipient != nil {
                Text("To:").foregroundStyle(.secondary)
                TextField("Phone number", text: Binding(
                    get: { model.newRecipient ?? "" },
                    set: { model.newRecipient = $0 }
                ))
                .textFieldStyle(.plain)
                .font(.wh(14, .medium))
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
            Button(action: send) {
                Image(systemName: "arrow.up")
                    .font(.wh(13, .bold))
                    .foregroundStyle(canSend ? theme.theme.onPale : Color.white.opacity(0.5))
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(canSend ? theme.theme.pale : Color.white.opacity(0.12)))
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
            .help("Send")
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
    let showSim: Bool
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
                    .opacity(message.pending ? 0.6 : 1)
                HStack(spacing: 4) {
                    if message.failed {
                        Label("Not sent", systemImage: "exclamationmark.circle").foregroundStyle(.red)
                    } else if message.pending {
                        Text("Sending…")
                    } else {
                        Text(message.date.formatted(date: .omitted, time: .shortened))
                    }
                    if showSim, let sim = message.sim { Text("· \(sim)") }
                }
                .font(.wh(10, .medium))
                .foregroundStyle(.secondary)
            }
            if !message.outgoing { Spacer(minLength: 80) }
        }
    }
}

private struct Avatar: View {
    let name: String?
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(color.gradient)
            if let initials {
                Text(initials).font(.system(size: size * 0.38, weight: .semibold)).foregroundStyle(.white)
            } else {
                Image(systemName: "person.fill").font(.system(size: size * 0.42)).foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
    }

    private var initials: String? {
        guard let name, !name.isEmpty else { return nil }
        let letters = name.split(separator: " ").prefix(2).compactMap(\.first)
        return letters.isEmpty ? nil : String(letters).uppercased()
    }

    private var color: Color {
        let palette: [Color] = [.blue, .indigo, .purple, .pink, .orange, .teal, .green, .gray]
        let hash = (name ?? "").unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return palette[abs(hash) % palette.count]
    }
}

private struct StatusNote: View {
    let symbol: String
    let title: String
    let text: String

    var body: some View {
        VStack(spacing: 8) {
            Spacer()
            Image(systemName: symbol).font(.wh(24, .medium)).foregroundStyle(.secondary)
            Text(title).font(.wh(13, .semibold))
            Text(text).font(.wh(12, .medium)).foregroundStyle(.secondary).multilineTextAlignment(.center)
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
