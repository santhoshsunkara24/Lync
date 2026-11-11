import AppKit
import SwiftUI

/// Which page the panel shows.
enum PanelPage: Equatable {
    case home
    case settings
    case addPhone
}

/// Lync's panel, in the phone app's style (DESIGN.md "Current direction"): the phone's colour
/// fading to ink, calm medium titles, pale pills for main actions, see-through chips, cards.
/// The same view opens from the notch (notch mode) or the menu bar icon (menu bar mode).
struct PanelView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var theme: ThemeStore
    /// Notch mode draws a black band at the top so the panel grows out of the notch.
    var fromNotch: Bool
    /// The page on screen. Follows `model.panelPage` a frame later, once `forward` is set, so
    /// the page leaving slides the right way too.
    @State private var page: PanelPage
    /// Going deeper (Home › Settings › Add a phone) pushes from the trailing edge; back pops.
    @State private var forward = true
    @Environment(\.panelRevealed) private var revealed

    init(model: AppModel, theme: ThemeStore, fromNotch: Bool) {
        self.model = model
        self.theme = theme
        self.fromNotch = fromNotch
        _page = State(initialValue: model.panelPage)
    }

    var body: some View {
        VStack(spacing: 0) {
            if fromNotch { Color.clear.frame(height: max(model.notchHeight, 0)) }
            // Pages overlap while one slides out and the next slides in; the panel springs to the
            // new page's height.
            ZStack(alignment: .top) {
                content
                    .id(page)
                    .transition(pageTransition)
            }
        }
        .frame(width: 380)
        .background(background)
        .overlay {
            // Files held over the open panel.
            if model.panelDropHover, let phone = model.connectedPhone {
                ZStack {
                    RoundedRectangle(cornerRadius: WRadius.panel, style: .continuous).fill(Color.black.opacity(0.55))
                    RoundedRectangle(cornerRadius: WRadius.panel, style: .continuous).strokeBorder(theme.theme.codeAccent, lineWidth: 2).padding(WSpace.xs)
                    Label { Text("Drop to send to \(phone.name)") } icon: { NudgeImage("arrow.up.doc.fill") }
                        .labelStyle(.titleAndIcon)
                        .font(.wh(15, .semibold)).foregroundStyle(theme.theme.codeAccent)
                }
                .allowsHitTesting(false)
                .transition(.opacity)
            } else if let problem = model.panelDropProblem {
                // Files held over the panel that can't go: why, and what to do.
                ZStack {
                    RoundedRectangle(cornerRadius: WRadius.panel, style: .continuous).fill(Color.black.opacity(0.6))
                    RoundedRectangle(cornerRadius: WRadius.panel, style: .continuous).strokeBorder(WTheme.danger, lineWidth: 2).padding(WSpace.xs)
                    VStack(spacing: WSpace.xxs) {
                        Label { Text(problem.title) } icon: { NudgeImage(problem.symbol) }
                            .labelStyle(.titleAndIcon)
                            .font(.wh(15, .semibold)).foregroundStyle(WTheme.danger)
                        if let detail = problem.detail {
                            Text(detail).font(.wh(13, .medium)).foregroundStyle(WTheme.secondary)
                        }
                    }
                    .multilineTextAlignment(.center).padding(.horizontal, WSpace.xl)
                }
                .allowsHitTesting(false)
                .transition(.opacity)
            }
        }
        .animation(WMotion.animation(WMotion.snappy), value: model.panelDropHover)
        .animation(WMotion.animation(WMotion.snappy), value: model.panelDropProblem)
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                ToastView(model: model, toast: toast)
                    // Inside the panel's gutter, like everything else on it.
                    .padding(.horizontal, WSpace.m)
                    .padding(.bottom, WSpace.m)
                    .transition(WMotion.transition(.asymmetric(
                        insertion: .move(edge: .bottom).combined(with: .opacity).animation(WMotion.bouncy),
                        removal: .offset(y: 10).combined(with: .opacity).animation(.easeOut(duration: 0.18)))))
                    .id(toast.id)
            }
        }
        .animation(WMotion.animation(WMotion.bouncy), value: model.toast)
        .onChange(of: model.panelPage) { _, next in turn(to: next) }
        .environmentObject(theme)
        .foregroundStyle(.white)
        .font(.wh(13))
        .environment(\.colorScheme, .dark)
    }

    /// Not paired yet: the onboarding steps on plain black, no colour wash.
    private var onboarding: Bool { (page == .home && !model.isPaired && model.pairingRequest == nil) || model.qrLinking != nil }

    @ViewBuilder private var background: some View {
        let t = theme.theme
        if onboarding {
            Color.black
        } else {
            LinearGradient(
            stops: fromNotch
                ? [.init(color: .black, location: 0), .init(color: t.top, location: 0.22), .init(color: t.mid, location: 0.5), .init(color: WTheme.ink, location: 0.85)]
                : [.init(color: t.top, location: 0), .init(color: t.mid, location: 0.36), .init(color: WTheme.ink, location: 0.8)],
            startPoint: .top, endPoint: .bottom
            )
        }
    }

    private var pageTransition: AnyTransition {
        let insertion: AnyTransition = .move(edge: forward ? .trailing : .leading).combined(with: .opacity)
        let removal: AnyTransition = AnyTransition.move(edge: forward ? .leading : .trailing)
            .combined(with: .opacity).combined(with: .collapsingHeight)
        if WMotion.reduceMotion { return .asymmetric(insertion: .opacity, removal: AnyTransition.opacity.combined(with: .collapsingHeight)) }
        return .asymmetric(insertion: insertion, removal: removal)
    }

    private func turn(to next: PanelPage) {
        guard next != page else { return }
        // Closed or still opening (or closing): no slide, just show it.
        guard model.panelOpen, revealed else {
            page = next
            return
        }
        forward = Self.depth(next) > Self.depth(page)
        DispatchQueue.main.async { withMotion(WMotion.smooth) { page = next } }
    }

    private static func depth(_ page: PanelPage) -> Int {
        switch page {
        case .home: return 0
        case .settings: return 1
        case .addPhone: return 2
        }
    }

    @ViewBuilder private var content: some View {
        if let moment = model.qrLinking {
            QrLinkingView(qrText: moment.text, phoneName: moment.name, showing: { [weak model] in model?.qrLinking == moment }) {
                withMotion(WMotion.smooth) { model.finishQrLinking(moment) }
            }
                .id(moment.id)
                // Closed before it finished: nothing left to watch; the note says it instead.
                .onChange(of: model.panelOpen) { _, open in if !open { model.finishQrLinking(moment, early: true) } }
                .transition(.opacity)
        } else {
            pageContent
        }
    }

    @ViewBuilder private var pageContent: some View {
        switch page {
        case .settings: SettingsPage(model: model).revealSection(0)
        case .addPhone: AddPhonePage(model: model).revealSection(0)
        case .home:
            if !model.isPaired && model.pairingRequest == nil {
                OnboardingPage(model: model)
            } else {
                HomePage(model: model)
            }
        }
    }
}

/// A pale pill at the bottom of the panel (the same as the phone's): how an action went, sometimes
/// with a button such as Undo, whose ring shows the time left. A problem has two lines (why, then
/// what to do) and often a button that does it.
struct ToastView: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
    let toast: Toast

    var body: some View {
        let colors = theme.theme
        let twoLines = toast.detail != nil
        // One line: a capsule. Two: rounded corners from the same scale.
        let shape = RoundedRectangle(cornerRadius: twoLines ? WRadius.field : 20, style: .continuous)
        HStack(alignment: twoLines ? .firstTextBaseline : .center, spacing: WSpace.s) {
            NudgeImage(toast.symbol).font(.wh(13, .semibold))
                .foregroundStyle(toast.isProblem ? Self.problemRed : colors.onPale)
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                Text(toast.message).font(.wh(13, .semibold)).lineLimit(2)
                if let detail = toast.detail {
                    Text(detail).font(.wh(12, .medium)).foregroundStyle(colors.onPale.opacity(0.78)).lineLimit(2)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            if let title = toast.actionTitle {
                Spacer(minLength: WSpace.xs)
                actionButton(title)
            }
        }
        .foregroundStyle(colors.onPale)
        .padding(.leading, WSpace.m)
        .padding(.trailing, toast.actionTitle == nil ? WSpace.m : WSpace.xs)
        .padding(.vertical, twoLines ? WSpace.s : WSpace.xs)
        .frame(minHeight: 40)
        .frame(maxWidth: twoLines ? 340 : nil)
        .background(shape.fill(colors.pale))
        .shadow(color: .black.opacity(0.3), radius: 12, y: 4)
        // Reading it, or reaching for Undo: it waits.
        .onHover { model.toastHovered = $0 }
        .accessibilityElement(children: .combine)
    }

    /// Red dark enough to read on the pale pill.
    static let problemRed = Color(red: 0.72, green: 0.11, blue: 0.1)

    /// A solid pill in the toast's ink, with a ring that runs down as the time to press it does.
    /// Undo also answers ⌘Z; other buttons are only clicked.
    @ViewBuilder private func actionButton(_ title: String) -> some View {
        let colors = theme.theme
        let button = Button { model.runToastAction() } label: {
            HStack(spacing: WSpace.xs) {
                if let clock = model.toastClock {
                    TimelineView(.animation(minimumInterval: nil, paused: clock.paused != nil)) { context in
                        CountdownRing(left: clock.left(at: context.date), color: colors.pale)
                    }
                    .frame(width: 14, height: 14)
                    .accessibilityHidden(true)
                }
                Text(title).font(.wh(13, .semibold))
            }
            .foregroundStyle(colors.pale)
            .padding(.horizontal, WSpace.s)
            .frame(height: 32)
            .background(Capsule().fill(colors.onPale))
            .contentShape(Capsule())
        }
        .buttonStyle(WPlainPress())
        .fixedSize()
        if title == "Undo" { button.keyboardShortcut("z", modifiers: .command) } else { button }
    }
}

/// A thin ring, starting at the top, that shrinks back towards it as time runs out.
private struct CountdownRing: View {
    let left: Double
    let color: Color

    var body: some View {
        ZStack {
            Circle().stroke(color.opacity(0.25), lineWidth: 2)
            Circle().trim(from: 0, to: left)
                .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(1)
    }
}

// MARK: - Home

private struct HomePage: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
    /// 0…1, a single glow on the phone's name (the title) when it connects.
    @State private var glow: CGFloat = 0
    /// Which list shows under the cards: Recent or Notifications.
    @State private var list = HomeList.recent
    /// The list being cleared: its rows sweep out one after another before they go.
    @State private var clearing: HomeList?

    private var listTransition: AnyTransition {
        WMotion.transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity),
                                       removal: .opacity.combined(with: .scale(scale: 0.96))))
    }

    var body: some View {
        ScrollViewReader { proxy in
        MaybeScroll {
            VStack(alignment: .leading, spacing: 0) {
                header.revealSection(0)
                if let phone = model.connectedPhone ?? model.pairedPhones.first {
                    PhoneCard(model: model, phone: phone).padding(.top, WSpace.l).revealSection(0)
                }
                if model.offerLoginItem {
                    LoginOfferCard(model: model).padding(.top, WSpace.m).revealSection(1)
                        .transition(WMotion.transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top))))
                }
                if model.pairingRequest != nil {
                    PairingCard(model: model).padding(.top, WSpace.m).revealSection(1)
                        .transition(WMotion.transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top))))
                }
                sendRow.padding(.top, WSpace.m).revealSection(1)
                if let outgoing = model.outgoing {
                    ProgressView(value: outgoing.progress)
                        .progressViewStyle(.linear)
                        .tint(theme.theme.pale)
                        .animation(WMotion.animation(WMotion.smooth), value: outgoing.progress)
                        .padding(.top, WSpace.s)
                        .transition(.opacity)
                }
                Group {
                    if let code = model.latestCode {
                        LatestCodeCard(model: model, code: code).padding(.top, WSpace.l)
                            .transition(WMotion.transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top))))
                    }
                    if let playing = model.nowPlaying {
                        NowPlayingCard(model: model, playing: playing).padding(.top, WSpace.m)
                            .transition(.opacity)
                    }
                }
                .revealSection(2)
                // Recent and Notifications share one spot: two tabs side by side, one list at a time.
                Group {
                    ListTabs(model: model, selection: $list).padding(.top, WSpace.xl)
                    // The list's own header: further from the tabs than from the list it belongs to.
                    ListClear(model: model, selection: list, clearing: clearing != nil, clear: clear)
                        .padding(.top, WSpace.m).padding(.horizontal, RowMetrics.horizontal)
                    Group {
                        if list == .recent {
                            RecentList(model: model, clearing: clearing == .recent)
                        } else {
                            notificationList
                        }
                    }
                    .padding(.top, WSpace.s)
                    .transition(.opacity)
                }
                .revealSection(3)
            }
            .padding(.horizontal, WSpace.gutter).padding(.top, WSpace.m).padding(.bottom, WSpace.l)
            .onChange(of: model.connectedPhone?.id) { old, new in
                guard new != nil, old == nil, model.panelOpen, !WMotion.reduceMotion else { return }
                withAnimation(.easeOut(duration: 0.25)) { glow = 1 }
                withAnimation(.easeInOut(duration: 0.9).delay(0.25)) { glow = 0 }
            }
            // Rows coming and going (and Undo bringing them back) move the rest smoothly.
            .animation(WMotion.animation(WMotion.smooth), value: ListChange(model.notifications, \.key))
            .animation(WMotion.animation(WMotion.smooth), value: model.callLog)
            .animation(WMotion.animation(WMotion.smooth), value: ListChange(model.activity, \.id))
            .animation(WMotion.animation(WMotion.smooth), value: model.latestCode?.code)
            .animation(WMotion.animation(WMotion.smooth), value: model.pairingRequest != nil)
            .animation(WMotion.animation(WMotion.smooth), value: model.outgoing == nil)
            .animation(WMotion.animation(WMotion.smooth), value: model.offerLoginItem)
            .animation(WMotion.animation(WMotion.quick), value: list)
        }
        // Snapshots show the whole page, not the first 600 points of a scrolling one.
        .frame(maxHeight: PanelSnapshots.active ? 2000 : 600)
        .fixedSize(horizontal: false, vertical: true)
        // A clicked notification card: scroll to that notification once the panel is open.
        .onAppear { scroll(proxy) }
        .onChange(of: model.scrollTarget) { _, _ in scroll(proxy) }
        }
    }

    /// Clear and Clear all: the rows sweep out to the right, top first, a beat apart; then they
    /// go and the list closes up around what's left (or its empty state fades in).
    private func clear(_ action: @escaping () -> Void) {
        guard clearing == nil else { return }
        let rows = list == .recent ? RecentList.items(model).count : NotificationEntry.all(model).filter(\.clears).count
        guard !WMotion.reduceMotion, rows > 0 else { action(); return }
        clearing = list
        DispatchQueue.main.asyncAfter(deadline: .now() + ClearSweep.duration(rows: rows)) {
            action()
            // The rows are gone (or going, already hidden) before the sweep lets go of them.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { clearing = nil }
        }
    }

    /// The phone's notifications, newest first, or what will appear here.
    @ViewBuilder private var notificationList: some View {
        let entries = NotificationEntry.all(model)
        if entries.isEmpty {
            ListEmpty(symbol: "bell",
                      text: "Calls and notifications from your phone show up here. Pick which apps in Lync on your phone.")
        } else {
            VStack(spacing: 0) {
                ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                    VStack(spacing: 0) {
                        if index > 0 { Divider().overlay(Color.white.opacity(0.08)).padding(.leading, RowMetrics.textInset) }
                        switch entry {
                        case .notification(let notification): NotificationRow(model: model, notification: notification)
                        case .call(let call): CallRow(model: model, call: call)
                        }
                    }
                    .modifier(ClearSweep(active: clearing == .notifications && entry.clears, index: index))
                    .id(entry.id)
                    .transition(listTransition)
                }
            }
            .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
            .clipShape(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous))
        }
    }

    private func scroll(_ proxy: ScrollViewProxy) {
        guard let target = model.scrollTarget else { return }
        // A clicked notification card: its tab first, then the row.
        if target.hasPrefix("notif-") { list = .notifications }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            withMotion(WMotion.smooth) { proxy.scrollTo(target, anchor: .center) }
            model.scrollTarget = nil
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: WSpace.xs) {
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                // The phone's own name ("Pixel 9"), so it's clear which one this is.
                Text((model.connectedPhone ?? model.pairedPhones.first)?.name ?? "Your phone")
                    .font(.whTitle(24)).lineLimit(1).minimumScaleFactor(0.6)
                    .shadow(color: theme.theme.pale.opacity(0.7 * glow), radius: 14)
                HStack(spacing: WSpace.xs) {
                    // Over Bluetooth the phone is still linked: the same calm line as Connected.
                    LinkCatchView(connected: model.connectedPhone != nil || model.bluetoothPhone != nil, animate: model.panelOpen, shaded: false, haptic: false)
                        .frame(width: 16, height: 16)
                    Text(statusLine).font(.wh(13, calm ? .medium : .semibold))
                        .foregroundStyle(calm ? WTheme.secondary : .white)
                        .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                    if looking && model.panelOpen {
                        LookingDots().foregroundStyle(WTheme.secondary).transition(.opacity)
                    }
                }
                .animation(WMotion.animation(WMotion.smooth), value: statusLine)
                // Not connected: what to do, and the button that does it.
                if let problem = model.connectionProblem {
                    if let detail = problem.detail {
                        Text(detail).font(.wh(12, .medium)).foregroundStyle(calm ? WTheme.tertiary : WTheme.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.leading, Self.statusInset)
                            .contentTransition(.opacity)
                    }
                    if let title = problem.actionTitle, let action = problem.action {
                        Button(title, action: action).buttonStyle(WPill(compact: true)).padding(.top, WSpace.xxs).padding(.leading, Self.statusInset)
                    }
                }
                if let note = model.connectionNote {
                    ConnectionNote(problem: note).padding(.top, WSpace.xxs).padding(.leading, Self.statusInset)
                        .transition(.opacity)
                }
            }
            .animation(WMotion.animation(WMotion.smooth), value: model.connectionProblem?.title)
            .animation(WMotion.animation(WMotion.smooth), value: model.connectionNote?.title)
            Spacer(minLength: WSpace.xs)
            // Ring works over Bluetooth too.
            if model.connectedPhone != nil || model.bluetoothPhone != nil {
                Button { model.ringPhone() } label: {
                    NudgeImage(model.ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right")
                }
                .buttonStyle(WRound(size: 32))
                .help(model.ringing ? "Stop ringing" : "Ring your phone to find it")
                .accessibilityLabel(model.ringing ? "Stop ringing" : "Ring phone")
            }
            Button { model.panelPage = .settings } label: { NudgeImage("gearshape") }
                .buttonStyle(WRound(size: 32))
                .help("Settings")
                .accessibilityLabel("Settings")
        }
    }

    /// Where the status text starts, past the link and the gap after it; what's under it lines up.
    private static let statusInset: CGFloat = 16 + WSpace.xs

    /// Nothing to fix: connected, or linked over Bluetooth (fewer features, but nothing's wrong).
    private var calm: Bool { model.connectionProblem == nil || model.bluetoothPhone != nil }

    /// Listening, no phone yet and nothing wrong: the only state with a running indicator.
    private var looking: Bool { model.listening && model.connectedPhone == nil && model.bluetoothPhone == nil }

    /// Why no phone is connected (it never just waits forever without saying why).
    private var statusLine: String {
        if model.connectedPhone != nil { return "Connected" }
        if let problem = model.connectionProblem { return problem.title }
        if !model.listening { return model.statusMessage }
        return "Waiting for your phone"
    }

    private var sendRow: some View {
        let connected = model.connectedPhone != nil
        // Off without Wi‑Fi: the tooltip says why (and over Bluetooth, what does work).
        let help = connected ? nil : Problem.disabledHelp(bluetooth: model.bluetoothPhone != nil)
        return HStack(spacing: WSpace.xs) {
            // Over Bluetooth the clipboard's text still goes; the button says if something needs Wi‑Fi.
            let canClip = connected || model.bluetoothPhone != nil
            Button { model.sendClipboardToPhone() } label: { Label { Text("Send clipboard") } icon: { NudgeImage("doc.on.clipboard") } }
                .buttonStyle(WPill()).disabled(!canClip).opacity(canClip ? 1 : 0.45)
                .help(help ?? "Send what's on the clipboard to your phone")
            Button { model.chooseFilesToSend() } label: { Label { Text("Files") } icon: { NudgeImage("paperplane") } }
                .buttonStyle(WPill(prominent: false)).disabled(!connected).opacity(connected ? 1 : 0.45)
                .help(help ?? "Choose files to send to your phone")
            Button { model.openMessages() } label: { Label { Text("Texts") } icon: { NudgeImage("message") } }
                .buttonStyle(WPill(prominent: false))
        }
        .labelStyle(.titleAndIcon)
    }
}

/// A smaller second note under the connection status (Bluetooth off, firewall), with its button.
private struct ConnectionNote: View {
    let problem: Problem

    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        if problem.detail == nil, let title = problem.actionTitle, let action = problem.action {
            // A heads-up while all else works: one quiet line, its button a link at the end.
            HStack(alignment: .firstTextBaseline, spacing: WSpace.xs) {
                NudgeImage(problem.symbol).font(.wh(11, .semibold)).foregroundStyle(WTheme.tertiary)
                Text(problem.title).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                Button(title, action: action).buttonStyle(WPlainPress())
                    .font(.wh(12, .semibold)).foregroundStyle(theme.theme.pale)
            }
            .accessibilityElement(children: .contain)
        } else {
            full
        }
    }

    private var full: some View {
        // The icon sits on the title's baseline.
        HStack(alignment: .firstTextBaseline, spacing: WSpace.xs) {
            NudgeImage(problem.symbol).font(.wh(11, .semibold)).foregroundStyle(WTheme.secondary)
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                Text(problem.title).font(.wh(12, .semibold))
                if let detail = problem.detail {
                    Text(detail).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).fixedSize(horizontal: false, vertical: true)
                }
                if let title = problem.actionTitle, let action = problem.action {
                    Button(title, action: action).buttonStyle(WPill(prominent: false, compact: true)).padding(.top, WSpace.xxs)
                }
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// The phone as a little device (the Mac card on the phone, the other way round): its screen in
/// the phone's own colours with the time, cut off by the details below it. Not connected, it's
/// dimmed and says so; adding another phone is a link (the header says why it isn't connected).
private struct PhoneCard: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
    let phone: PairedPhone

    private var name: String { phone.name }
    private var connected: Bool { model.connectedPhone != nil }
    private var confirming: Bool { model.confirmingForget == phone.id }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .top) {
                LinearGradient(colors: [Color.white.opacity(0.07), Color.white.opacity(0.03)], startPoint: .top, endPoint: .bottom)
                MiniPhone(connected: connected || model.bluetoothPhone != nil, theme: theme.theme).padding(.top, WSpace.l)
            }
            .frame(height: connected ? 196 : 152, alignment: .top)
            .clipped()
            // The card's one action, in its top-right corner: forgetting this phone. It asks first.
            .overlay(alignment: .topTrailing) {
                if !confirming {
                    Button("Forget phone") { model.confirmingForget = phone.id }
                        .buttonStyle(WPlainPress())
                        // Red, like Forget's own confirm: it unpairs the phone.
                        .font(.wh(12, .semibold)).foregroundStyle(WTheme.danger)
                        .padding(.horizontal, WSpace.s).frame(height: 26)
                        .background(Capsule().fill(Color.black.opacity(0.45)))
                        .contentShape(Capsule())
                        .padding(WSpace.s)
                        .help("Unpair \(name) from this Mac")
                        .accessibilityLabel("Forget \(name)")
                        .transition(.opacity)
                }
            }
            Group {
                if confirming { forgetConfirmation.transition(.swap) } else { details.transition(.swap) }
            }
            .background(theme.theme.card)
        }
        .clipShape(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).strokeBorder(WTheme.edge, lineWidth: 1))
        // Right-click: what you'd do with the phone, without hunting for the buttons.
        .contextMenu {
            if connected || model.bluetoothPhone != nil {
                Button(model.ringing ? "Stop ringing" : "Ring phone") { model.ringPhone() }
            }
            if connected {
                Button("Send clipboard") { model.sendClipboardToPhone() }
                Button("Send files…") { model.chooseFilesToSend() }
                Button("Texts") { model.openMessages() }
                Divider()
            }
            Button("Add a phone") { model.panelPage = .addPhone }
            Button("Settings") { model.panelPage = .settings }
        }
        .accessibilityElement(children: .contain)
        .animation(WMotion.animation(WMotion.smooth), value: connected)
        .animation(WMotion.animation(WMotion.smooth), value: confirming)
    }

    /// Forget asks once, right in the card: the phone has to pair again afterwards.
    private var forgetConfirmation: some View {
        VStack(alignment: .leading, spacing: WSpace.xxs) {
            Text("Forget \(name)?").font(.wh(15, .semibold)).lineLimit(1)
            Text("You'll need to pair again to use it with this Mac.")
                .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: WSpace.xs) {
                Spacer()
                Button("Cancel") { model.confirmingForget = nil }
                    .buttonStyle(WPill(prominent: false, compact: true))
                    .keyboardShortcut(.cancelAction)
                Button("Forget") { model.forget(phone) }
                    .buttonStyle(WPill(compact: true, destructive: true))
                    .accessibilityLabel("Forget \(name)")
            }
            .padding(.top, WSpace.xs)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(WSpace.m)
        .accessibilityElement(children: .contain)
    }

    private var details: some View {
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                Text("Android phone").font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                if connected {
                    TimelineView(.periodic(from: .now, by: 30)) { context in
                        Text(detail(now: context.date)).font(.wh(13)).foregroundStyle(WTheme.secondary)
                            .contentTransition(.numericText())
                    }
                } else {
                    Text(model.bluetoothPhone != nil ? "Over Bluetooth" : "Not connected")
                        .font(.wh(13)).foregroundStyle(WTheme.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, WSpace.m).padding(.top, WSpace.m).padding(.bottom, connected ? WSpace.l : WSpace.m)
            .accessibilityElement(children: .contain)
    }

    private func detail(now: Date) -> String {
        var parts: [String] = []
        if let battery = model.battery { parts.append("\(battery)% battery" + (model.charging ? ", charging" : "")) }
        if let since = model.connectedAt {
            let minutes = Int(now.timeIntervalSince(since) / 60)
            parts.append(minutes < 1 ? "just connected"
                         : minutes < 60 ? "connected \(minutes) min ago"
                         : "connected \(minutes / 60) h ago")
        }
        return parts.isEmpty ? "Connected" : parts.joined(separator: " · ")
    }
}

/// A modern Android phone, drawn: thin dark frame, punch-hole camera, a lock screen in the phone's
/// colours. Only its top shows; the card cuts it off. Everything scales with `width`.
private struct MiniPhone: View {
    let connected: Bool
    let theme: WTheme
    var width: CGFloat = 236

    var body: some View {
        let u = width / 148
        ZStack(alignment: .top) {
            RoundedRectangle(cornerRadius: 30 * u, style: .continuous)
                .fill(Color(red: 0.09, green: 0.09, blue: 0.1))
                .overlay(RoundedRectangle(cornerRadius: 30 * u, style: .continuous).strokeBorder(Color.white.opacity(0.16), lineWidth: 1))
            screen(u)
                .clipShape(RoundedRectangle(cornerRadius: 25 * u, style: .continuous))
                .padding(5 * u)
            // Punch-hole camera, with the green light while connected.
            ZStack {
                Circle().fill(Color.black).frame(width: 11 * u, height: 11 * u)
                Circle().fill(Color.white.opacity(0.12)).frame(width: 4 * u, height: 4 * u).offset(x: -1.5 * u, y: -1.5 * u)
            }
            .padding(.top, 13 * u)
            if connected {
                Circle().fill(WTheme.success).frame(width: 5 * u, height: 5 * u)
                    .padding(.top, 16 * u).offset(x: 14 * u)
                    .transition(.opacity)
            }
        }
        .frame(width: width, height: width * 2.1)
        .shadow(color: .black.opacity(0.35), radius: 16, y: 8)
        .accessibilityHidden(true)
    }

    private func screen(_ u: CGFloat) -> some View {
        ZStack(alignment: .top) {
            LinearGradient(colors: [theme.top, theme.mid, WTheme.ink], startPoint: .top, endPoint: .bottom)
                .saturation(connected ? 1 : 0.15)
                .opacity(connected ? 1 : 0.55)
            TimelineView(.everyMinute) { context in
                let date = PanelSnapshots.clock ?? context.date
                VStack(spacing: 2 * u) {
                    Text(date, format: .dateTime.hour(.defaultDigits(amPM: .omitted)).minute())
                        .font(.system(size: 34 * u, weight: .light, design: .rounded))
                        .monospacedDigit()
                    Text(date, format: .dateTime.weekday(.abbreviated).day().month(.abbreviated))
                        .font(.wh(10 * u, .medium)).foregroundStyle(Color.white.opacity(0.75))
                }
                .padding(.top, 34 * u)
            }
            // Status bar.
            HStack(spacing: 3 * u) {
                Capsule().frame(width: 16 * u, height: 3 * u)
                Spacer()
                Capsule().frame(width: 8 * u, height: 3 * u)
                Capsule().frame(width: 12 * u, height: 3 * u)
            }
            .foregroundStyle(Color.white.opacity(0.7))
            .padding(.horizontal, 16 * u).padding(.top, 12 * u)
            // Glare.
            LinearGradient(stops: [.init(color: .white.opacity(0), location: 0.3), .init(color: .white.opacity(0.07), location: 0.45), .init(color: .white.opacity(0), location: 0.55)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }
}

/// The two lists under the home page's cards.
private enum HomeList { case recent, notifications }

/// Recent and Notifications as the phone's filter chips: a see-through capsule each, and a pale
/// pill that slides to the chosen one. A count shows what's waiting in Notifications.
private struct ListTabs: View {
    @EnvironmentObject var theme: ThemeStore
    @ObservedObject var model: AppModel
    @Binding var selection: HomeList
    @Namespace private var pill

    var body: some View {
        HStack(spacing: WSpace.xs) {
            tab("Recent", .recent, count: nil)
            tab("Notifications", .notifications, count: NotificationEntry.count(model) == 0 ? nil : NotificationEntry.count(model))
        }
        .animation(WMotion.animation(WMotion.snappy), value: selection)
    }

    private func tab(_ title: String, _ list: HomeList, count: Int?) -> some View {
        let chosen = selection == list
        let colors = theme.theme
        return Button { selection = list } label: {
            HStack(spacing: WSpace.xxs) {
                Text(title).font(.wh(13, .semibold)).lineLimit(1)
                if let count {
                    Text("\(min(count, 99))").font(.wh(11, .semibold).monospacedDigit())
                        .padding(.horizontal, 6).frame(minWidth: 18, minHeight: 18)
                        .background(Capsule().fill(chosen ? colors.onPale.opacity(0.14) : Color.white.opacity(0.16)))
                        .transition(.opacity.combined(with: .scale(scale: 0.8)))
                }
            }
            .foregroundStyle(chosen ? colors.onPale : .white)
            .frame(maxWidth: .infinity).frame(height: 34)
            .background {
                ZStack {
                    Capsule().fill(Color.white.opacity(0.12))
                    if chosen { Capsule().fill(colors.pale).matchedGeometryEffect(id: "pill", in: pill) }
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(WPlainPress())
        .accessibilityLabel(count.map { "\(title), \($0) waiting" } ?? title)
        .accessibilityAddTraits(chosen ? [.isButton, .isSelected] : .isButton)
    }
}

/// The chosen list's header: how much is in it on the left, its Clear on the right.
private struct ListClear: View {
    @ObservedObject var model: AppModel
    let selection: HomeList
    /// A clear is under way: the button waits for it.
    var clearing = false
    /// Runs the clear with its sweep.
    var clear: (@escaping () -> Void) -> Void = { $0() }

    var body: some View {
        if let action {
            HStack(alignment: .firstTextBaseline) {
                Text(count).font(.wh(12, .semibold)).foregroundStyle(WTheme.secondary)
                    .contentTransition(.numericText())
                Spacer()
                Button(action.0) { clear(action.1) }.buttonStyle(WPlainPress()).disabled(clearing).font(.wh(12, .semibold)).foregroundStyle(WTheme.secondary)
                    .help(action.2 ?? action.0)
                    .accessibilityHint(action.2 ?? "")
            }
            .transition(.opacity)
        }
    }

    private var count: String {
        switch selection {
        case .recent:
            let n = RecentList.items(model).count
            return n == 1 ? "1 item" : "\(n) items"
        case .notifications:
            let n = NotificationEntry.count(model)
            return n == 1 ? "1 notification" : "\(n) notifications"
        }
    }

    private var action: (String, () -> Void, String?)? {
        switch selection {
        case .recent:
            RecentList.items(model).isEmpty ? nil : ("Clear", { model.clearActivity() }, nil)
        case .notifications:
            NotificationEntry.count(model) == 0 ? nil : ("Clear all", { model.dismissAllNotifications() }, "Also clears them on your phone")
        }
    }

}

/// One row of the Notifications tab: a phone notification or a call, newest first.
private enum NotificationEntry: Identifiable {
    case notification(PhoneNotification)
    case call(CallLogEntry)

    /// Clear all takes it (a call still ringing or under way stays).
    var clears: Bool {
        if case .call(let c) = self { return !c.outcome.isLive }
        return true
    }

    /// Notifications keep their "notif-" id: a clicked notch card scrolls to it.
    var id: String {
        switch self {
        case .notification(let n): "notif-\(n.key)"
        case .call(let c): "call-\(c.id)"
        }
    }

    var date: Date {
        switch self {
        case .notification(let n): n.time
        case .call(let c): c.date
        }
    }

    static func all(_ model: AppModel) -> [NotificationEntry] {
        (model.notifications.map(NotificationEntry.notification) + model.callLog.map(NotificationEntry.call))
            .sorted { $0.date > $1.date }
    }

    static func count(_ model: AppModel) -> Int { model.notifications.count + model.callLog.count }
}

/// A call in the Notifications list: who, how it went, and when.
private struct CallRow: View {
    @ObservedObject var model: AppModel
    let call: CallLogEntry
    @State private var hovering = false

    private var look: (symbol: String, tint: Color, text: String) {
        switch call.outcome {
        case .ringing: ("phone.fill", WTheme.success, "Incoming call…")
        case .onCall: ("phone.fill", WTheme.success, "On a call · audio on your phone")
        case .answered: ("phone.fill", WTheme.secondary, "Answered")
        case .declined: ("phone.down.fill", WTheme.secondary, "Declined")
        case .missed: ("phone.arrow.down.left", WTheme.danger, "Missed call")
        }
    }

    var body: some View {
        let look = look
        HStack(alignment: .top, spacing: RowMetrics.iconGap) {
            NudgeImage(look.symbol).font(.wh(13, .semibold)).foregroundStyle(look.tint)
                .frame(width: RowMetrics.icon, height: RowMetrics.icon).background(Circle().fill(look.tint.opacity(0.22)))
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                HStack(spacing: WSpace.xs) {
                    Text(call.caller).font(.wh(13, .semibold)).lineLimit(1)
                    Spacer(minLength: WSpace.xs)
                    Text(call.date, style: .time).font(.wh(11, .medium).monospacedDigit()).foregroundStyle(WTheme.tertiary)
                        .fixedSize()
                }
                Text([look.text, call.sim].compactMap { $0 }.joined(separator: " · "))
                    .font(.wh(12, .medium)).foregroundStyle(call.outcome == .missed ? WTheme.danger : WTheme.secondary).lineLimit(1)
            }
            // A call still ringing or under way stays until it ends.
            if !call.outcome.isLive {
                RowRemove(visible: hovering, help: "Remove") { model.removeCall(call.id) }
            }
        }
        .animation(WMotion.reduceMotion ? nil : .easeOut(duration: 0.12), value: hovering)
        .padding(.horizontal, RowMetrics.horizontal).padding(.vertical, RowMetrics.vertical)
        .contentShape(Rectangle())
        .rowHover()
        .onHover { hovering = $0 }
        .accessibilityElement(children: .combine)
        .accessibilityActions { if !call.outcome.isLive { Button("Remove") { model.removeCall(call.id) } } }
        .contextMenu { if !call.outcome.isLive { Button("Remove") { model.removeCall(call.id) } } }
    }
}

/// A list's empty state: an icon in a circle and a line on what will show up here.
private struct ListEmpty: View {
    let symbol: String
    let text: String

    var body: some View {
        VStack(spacing: WSpace.xs) {
            NudgeImage(symbol).font(.wh(22)).foregroundStyle(WTheme.secondary)
                .frame(width: 56, height: 56).background(Circle().fill(WTheme.chip))
            Text(text)
                .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.vertical, WSpace.l).padding(.horizontal, WSpace.l)
        .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
        .transition(.opacity)
    }
}

/// The newest unexpired code, big, in the contrasting colour. Already on the clipboard.
private struct LatestCodeCard: View {
    @EnvironmentObject var theme: ThemeStore
    @ObservedObject var model: AppModel
    let code: (code: String, sender: String, item: ActivityItem)
    /// A click anywhere on the card copies, with the button's own tick.
    @State private var copyTrigger = 0

    var body: some View {
        // Codes use the phone's colours too: a deep card with bright digits.
        let onClipboard = model.codeOnClipboard == code.code
        VStack(alignment: .leading, spacing: WSpace.xxs) {
            Text("Latest code").font(.wh(12, .semibold)).tracking(0.1).foregroundStyle(WTheme.codeSecondary)
            // Copy sits level with the code itself.
            HStack(spacing: WSpace.s) {
                RollingCode(code: code.code, font: .wh(30, .semibold).monospacedDigit(), tracking: 2).id(code.code)
                    .foregroundStyle(theme.theme.codeAccent)
                Spacer(minLength: WSpace.xs)
                CopyButton(style: CodePill(), label: "Copy code \(spaced(code.code))", trigger: copyTrigger) { model.copy(code.item) }
            }
            // After the minute on the clipboard it's cleared: then it only says who sent it.
            Text((code.sender.isEmpty ? "Verification code" : code.sender) + (onClipboard ? " · on your clipboard" : ""))
                .font(.wh(12, .medium)).foregroundStyle(WTheme.codeSecondary).lineLimit(1)
        }
        .padding(WSpace.m)
        .background(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).fill(theme.theme.codeCard))
        .contentShape(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous))
        .onTapGesture { copyTrigger += 1 }
        .contextMenu {
            Button("Copy code") { copyTrigger += 1 }
            Button("Remove", role: .destructive) { model.remove(code.item) }
        }
        .accessibilityElement(children: .contain)
    }
}

struct PeekPill: ButtonStyle {
    @EnvironmentObject var theme: ThemeStore
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .labelStyle(.titleAndIcon)
            .font(.wh(13, .semibold))
            .foregroundStyle(theme.theme.onPeekPale)
            .padding(.horizontal, WSpace.m).frame(height: 32)
            .background(Capsule().fill(theme.theme.peekPale))
            .modifier(WInteractive(shape: Capsule(), pressed: configuration.isPressed))
    }
}

/// What's playing on the phone.
private struct NowPlayingCard: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
    let playing: NowPlaying

    var body: some View {
        HStack(spacing: WSpace.s) {
            Group {
                if let art = model.artwork {
                    Image(nsImage: art).resizable().aspectRatio(contentMode: .fill)
                } else {
                    NudgeImage("music.note").font(.wh(18)).frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(WTheme.chip)
                }
            }
            .frame(width: 48, height: 48)
            .clipShape(RoundedRectangle(cornerRadius: WRadius.field, style: .continuous))
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                Text(playing.title.isEmpty ? "Unknown title" : playing.title).font(.wh(14, .semibold)).lineLimit(1)
                Text([playing.artist, playing.app].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(1)
            }
            Spacer(minLength: WSpace.xs)
            Button { model.media("previous") } label: { NudgeImage("backward.fill") }.buttonStyle(WRound(size: 32))
                .help("Previous").accessibilityLabel("Previous")
            Button { model.media("toggle") } label: {
                NudgeImage(playing.playing ? "pause.fill" : "play.fill")
                    .contentTransition(.symbolEffect(.replace))
                    .font(.wh(15, .semibold)).foregroundStyle(theme.theme.onPale)
                    .frame(width: 40, height: 40).background(Circle().fill(theme.theme.pale))
            }
            .buttonStyle(WShapePress(shape: Circle()))
            .help(playing.playing ? "Pause" : "Play")
            .accessibilityLabel(playing.playing ? "Pause" : "Play")
            Button { model.media("next") } label: { NudgeImage("forward.fill") }.buttonStyle(WRound(size: 32))
                .help("Next").accessibilityLabel("Next")
        }
        .padding(WSpace.m)
        .background(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).fill(theme.theme.card))
        .contextMenu {
            Button("Volume up") { model.media("volume_up") }
            Button("Volume down") { model.media("volume_down") }
        }
    }
}

private struct NotificationRow: View {
    @ObservedObject var model: AppModel
    let notification: PhoneNotification
    @State private var hovering = false
    @State private var draft = ""
    /// The row itself or one of its buttons: the buttons stay while any of them has focus.
    enum Field: Hashable { case row, reply, clear, field, send }
    @FocusState private var focus: Field?

    private var replying: Bool { model.inlineReplyKey == notification.key }
    private var showActions: Bool { !replying && (hovering || (focus != nil && focus != .field && focus != .send)) }
    private var title: String {
        model.hideNotificationText || notification.title.isEmpty ? notification.appName : notification.title
    }

    var body: some View {
        HStack(alignment: .top, spacing: RowMetrics.iconGap) {
            AppIcon(image: model.appIcons[notification.packageName], size: RowMetrics.icon)
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                HStack(spacing: WSpace.xs) {
                    Text(title).font(.wh(13, .semibold)).lineLimit(1)
                    Spacer(minLength: WSpace.xs)
                    Text(notification.time, style: .time).font(.wh(11, .medium).monospacedDigit()).foregroundStyle(WTheme.tertiary)
                        .fixedSize()
                }
                .accessibilityElement(children: .combine)
                Text(model.hideNotificationText ? "New notification" : notification.text)
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(2)
                if replying {
                    // Reply right here, in the panel.
                    HStack(spacing: WSpace.xs) {
                        TextField("Reply to \(title)…", text: $draft)
                            .textFieldStyle(.plain)
                            .font(.wh(13, .medium))
                            .padding(.horizontal, WSpace.s)
                            .frame(height: 24)
                            .background(Capsule().fill(Color.white.opacity(0.12)))
                            .focused($focus, equals: .field)
                            .onSubmit(send)
                            .accessibilityLabel("Reply to \(title)")
                        Button("Send", action: send)
                            .buttonStyle(WPill(compact: true))
                            .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                            .focused($focus, equals: .send)
                    }
                    .padding(.top, WSpace.xxs)
                    .onAppear { DispatchQueue.main.async { focus = .field } }
                }
            }
            // Under the buttons the words fade out rather than being covered.
            .mask(
                LinearGradient(stops: [.init(color: .black, location: 0),
                                       .init(color: .black, location: showActions ? 0.42 : 1),
                                       .init(color: .black.opacity(showActions ? 0 : 1), location: showActions ? 0.6 : 1)],
                               startPoint: .leading, endPoint: .trailing)
            )
        }
        // The buttons float over the row's end instead of adding a line, so the list never shifts
        // under the pointer as it moves down it.
        .overlay(alignment: .trailing) {
            if showActions {
                HStack(spacing: WSpace.xs) {
                    if notification.canReply {
                        Button("Reply", action: startReply).buttonStyle(WPill(compact: true))
                            .focused($focus, equals: .reply)
                    }
                    Button("Clear") { model.clearNotification(notification.key) }
                        .buttonStyle(WPill(prominent: false, compact: true))
                        .help("Also clears it on your phone")
                        .focused($focus, equals: .clear)
                }
                .transition(.opacity)
            }
        }
        .animation(WMotion.reduceMotion ? nil : .easeOut(duration: 0.12), value: showActions)
        .padding(.horizontal, RowMetrics.horizontal).padding(.vertical, RowMetrics.vertical)
        .contentShape(Rectangle())
        .rowHover()
        .onHover { hovering = $0 }
        // Keyboard: Tab to the row shows its buttons; Return replies. Only keyboard navigation focuses
        // it (a click doesn't), and our white ring replaces the system's blue one.
        .focusable(interactions: .activate)
        .focusEffectDisabled()
        .focused($focus, equals: .row)
        .modifier(WFocusRing(shape: RoundedRectangle(cornerRadius: WRadius.small, style: .continuous), inset: 2))
        .onKeyPress(.return) {
            guard notification.canReply, focus == .row else { return .ignored }
            startReply()
            return .handled
        }
        .accessibilityElement(children: replying ? .contain : .combine)
        .accessibilityActions {
            if notification.canReply { Button("Reply", action: startReply) }
            Button("Clear") { model.clearNotification(notification.key) }
        }
        .contextMenu {
            if notification.canReply { Button("Reply", action: startReply) }
            // Same as the row's Clear button: here and on the phone, with Undo.
            Button("Clear") { model.clearNotification(notification.key) }
            Button("Mute \(notification.appName)") { model.mute(notification) }
        }
    }

    private func startReply() {
        // A reply that didn't get through comes back to try again.
        draft = model.draft(for: notification.key)
        model.inlineReplyKey = notification.key
    }

    private func send() {
        guard !draft.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        model.reply(to: notification.key, text: draft)
        draft = ""
    }
}

/// Codes, texts, files and missed calls, newest first.
private struct RecentList: View {
    @ObservedObject var model: AppModel
    var clearing = false

    /// Activity without the code already shown in the Latest code card.
    static func items(_ model: AppModel) -> [ActivityItem] {
        let shown = model.latestCode?.item.id
        return model.activity.filter { $0.id != shown }
    }

    var body: some View {
        // Empty, or only the code that has its own card above.
        if Self.items(model).isEmpty {
            ListEmpty(symbol: "tray", text: "Codes, texts and files from your phone show up here, already on your clipboard.")
        } else {
            VStack(spacing: 0) {
                ForEach(Array(Self.items(model).enumerated()), id: \.element.id) { index, item in
                    VStack(spacing: 0) {
                        if index > 0 { Divider().overlay(Color.white.opacity(0.08)).padding(.leading, RowMetrics.textInset) }
                        RecentRow(model: model, item: item)
                    }
                    .modifier(ClearSweep(active: clearing, index: index))
                    .transition(WMotion.transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity),
                                                               removal: .opacity.combined(with: .scale(scale: 0.96)))))
                }
            }
            .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
            .clipShape(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous))
            .transition(.opacity)
        }
    }
}

private struct RecentRow: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
    let item: ActivityItem
    /// Bumped by a click on a code or text row: its Copy button copies and says "Copied".
    @State private var copyTrigger = 0
    /// Bumped when a transfer in this row fails while it's on screen: the icon shakes once.
    @State private var failShakes = 0
    @State private var hovering = false

    private var failed: Bool {
        if case .files(let batch) = item.payload { return batch.finished && batch.files.isEmpty }
        return false
    }

    /// Files still coming in: no × until they've arrived.
    private var receiving: Bool {
        if case .files(let batch) = item.payload { return !batch.finished }
        return false
    }

    private var expired: Bool {
        if case .files(let batch) = item.payload { return batch.isExpired }
        return false
    }

    var body: some View {
        // Icon and text centre on each other; the buttons line up with the title.
        HStack(alignment: .titleRow, spacing: WSpace.xs) {
            HStack(spacing: RowMetrics.iconGap) {
                leading.frame(width: RowMetrics.icon).shake(failShakes)
                VStack(alignment: .leading, spacing: WSpace.xxs) {
                    title.alignmentGuide(.titleRow) { $0[VerticalAlignment.center] }
                    Text(subtitle).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: WSpace.xs)
            // The × comes in before the row's own buttons, into the space the text leaves, so
            // Copy and Save never move under the pointer.
            RowRemove(visible: hovering && !receiving, help: "Remove from Recent") { model.remove(item) }
            trailing
        }
        .animation(WMotion.reduceMotion ? nil : .easeOut(duration: 0.12), value: hovering)
        .padding(.horizontal, RowMetrics.horizontal).padding(.vertical, RowMetrics.vertical)
        .contentShape(Rectangle())
        .rowHover()
        .onHover { hovering = $0 }
        .onChange(of: failed) { _, now in if now { failShakes += 1 } }
        // Files: double-click opens them, as in Finder. Only rows with files wait to tell a
        // double-click from a click; codes still copy on the first.
        .modifier(OpenOnDoubleClick(files: files))
        .onTapGesture { if copyable { copyTrigger += 1 } }
        .modifier(FileDrag(url: files.first))
        // Keyboard: Tab to the row; Return copies. Only keyboard navigation focuses it (a click
        // doesn't), and our white ring replaces the system's blue one.
        .focusable(interactions: .activate)
        .focusEffectDisabled()
        .modifier(WFocusRing(shape: RoundedRectangle(cornerRadius: WRadius.small, style: .continuous), inset: 2))
        .onKeyPress(.return) {
            if copyable { copyTrigger += 1; return .handled }
            if !files.isEmpty { model.copyFiles(item.id); return .handled }
            return .ignored
        }
        .accessibilityElement(children: .combine)
        .accessibilityActions {
            if copyable { Button(copyLabel) { model.copy(item) } }
            if !files.isEmpty {
                Button(copyLabel) { model.copyFiles(item.id) }
                Button(files.count == 1 ? "Open" : "Open \(files.count) files") { files.forEach(model.openFile) }
                Button("Show in Finder") { model.revealFiles(item.id) }
                if case .files(let batch) = item.payload, !batch.saved {
                    Button("Save to Downloads") { model.saveFiles(item.id) }
                }
            }
            Button("Remove from Recent") { model.remove(item) }
        }
        .contextMenu {
            if copyable {
                Button(isCode ? "Copy code" : "Copy") { model.copy(item) }
            }
            if !files.isEmpty {
                Button("Copy") { model.copyFiles(item.id) }
                Button(files.count == 1 ? "Open" : "Open \(files.count) files") { files.forEach(model.openFile) }
                Button("Show in Finder") { model.revealFiles(item.id) }
                if case .files(let batch) = item.payload, !batch.saved {
                    Button("Save to Downloads") { model.saveFiles(item.id) }
                }
            }
            Divider()
            Button("Remove") { model.remove(item) }
        }
    }

    private var isCode: Bool {
        if case .otp = item.payload { return true }
        return false
    }

    /// What the Copy button copies, for VoiceOver and its tooltip.
    private var copyLabel: String {
        switch item.payload {
        case .otp(let code, _, _): return "Copy code \(spaced(code))"
        case .text: return "Copy text"
        case .files(let batch): return "Copy \(batch.summary)"
        case .missedCall: return "Copy"
        }
    }

    /// An unexpired code or a text: clicking the row copies it.
    private var copyable: Bool {
        switch item.payload {
        case .otp(_, _, let expiresAt): return expiresAt > Date()
        case .text: return true
        default: return false
        }
    }

    /// Received files that arrived and are still here, for the menu and dragging.
    private var files: [URL] {
        if case .files(let batch) = item.payload, batch.finished, !batch.isExpired { return batch.files }
        return []
    }

    @ViewBuilder private var leading: some View {
        switch item.payload {
        case .otp: CodeIconTile(size: 32, tint: theme.theme.codeAccent, wash: 0.22)
        case .text: icon("text.bubble.fill", theme.theme.pale)
        case .files(let batch):
            if batch.isExpired { icon("clock.badge.xmark", WTheme.secondary) }
            else if batch.files.isEmpty { icon("arrow.down", batch.finished ? WTheme.danger : theme.theme.pale) } else { ThumbnailStack(urls: batch.files, size: 26, fan: 3) }
        case .missedCall: icon("phone.arrow.down.left", WTheme.danger)
        }
    }

    @ViewBuilder private var title: some View {
        switch item.payload {
        case .otp(let code, _, let expiresAt):
            Text(expiresAt > Date() ? spaced(code) : "••• •••").font(.wh(15, .semibold).monospacedDigit()).tracking(1)
        case .text(let text):
            // Not selectable: macOS scrolls selectable text to its end on click, cutting off the start.
            Text(model.hideNotificationText ? "Text from your phone" : text).font(.wh(13, .medium)).lineLimit(2).truncationMode(.tail)
        case .files(let batch):
            Text(batch.finished ? batch.summary : "Receiving \(batch.summary)…").font(.wh(13, .semibold))
        case .missedCall(let caller, _):
            Text(caller).font(.wh(13, .semibold)).lineLimit(1)
        }
    }

    private var subtitle: String {
        let time = item.date.formatted(date: .omitted, time: .shortened)
        switch item.payload {
        case .otp(_, let sender, let expiresAt):
            let name = sender.isEmpty ? "Code" : sender
            return expiresAt > Date() ? "\(name) · \(time)" : "\(name) · expired"
        case .text: return "Text · \(time)"
        case .files(let batch):
            if batch.isExpired { return Problem.expiredSubtitle }
            if batch.interrupted { return Problem.interrupted(arrived: batch.files.count, expected: max(batch.expected, batch.files.count)).title }
            if batch.failed > 0, batch.files.isEmpty { return "\(Problem.receiveFailed(batch.failure).title) · \(time)" }
            if batch.failed > 0 {
                return "\(Problem.partlyArrived(arrived: batch.files.count, expected: batch.files.count + batch.failed)) · \(Problem.receiveFailed(batch.failure).title)"
            }
            if !batch.finished { return "\(Int(batch.progress * 100))%" }
            return batch.saved ? "Saved to Downloads · \(time)" : "Kept for a day — Save to keep"
        case .missedCall(_, let sim): return ["Missed call", sim, time].compactMap { $0 }.joined(separator: " · ")
        }
    }

    @ViewBuilder private var trailing: some View {
        switch item.payload {
        case .otp(_, _, let expiresAt) where expiresAt > Date():
            CopyButton(iconOnly: true, style: WRound(size: 24), label: copyLabel, trigger: copyTrigger) { model.copy(item) }
        case .text:
            CopyButton(iconOnly: true, style: WRound(size: 24), label: copyLabel, trigger: copyTrigger) { model.copy(item) }
        case .files(let batch) where batch.finished && !batch.files.isEmpty && !batch.isExpired:
            HStack(spacing: WSpace.xs) {
                CopyButton(iconOnly: true, style: WRound(size: 24), label: copyLabel) { model.copyFiles(item.id) }
                // Saved: Save swaps for Show in place.
                ZStack(alignment: .trailing) {
                    if batch.saved {
                        Button("Show") { model.revealFiles(item.id) }.buttonStyle(WPill(prominent: false, compact: true))
                            .help("Show in Finder")
                            .transition(.swap)
                    } else {
                        Button("Save") { model.saveFiles(item.id) }.buttonStyle(WPill(compact: true))
                            .help("Save to Downloads › From Phone")
                            .transition(.swap)
                    }
                }
                .animation(WMotion.animation(WMotion.quick), value: batch.saved)
            }
        case .files(let batch) where !batch.finished:
            ProgressRing(progress: batch.progress)
        default:
            EmptyView()
        }
    }

    private func icon(_ name: String, _ tint: Color) -> some View {
        NudgeImage(name).font(.wh(13, .semibold))
            .foregroundStyle(tint)
            .frame(width: RowMetrics.icon, height: RowMetrics.icon).background(Circle().fill(tint.opacity(0.22)))
    }
}

/// A row leaving with Clear: it slides a little to the right and fades, a beat after the row
/// above it, so the list empties top to bottom like a sweep rather than all at once.
private struct ClearSweep: ViewModifier {
    let active: Bool
    let index: Int

    static let fade = 0.2
    static let stagger = 0.035
    /// Past the eighth row the rest go together: a long list doesn't make you wait.
    static let maxStaggered = 7

    /// From the click until the last row has faded.
    static func duration(rows: Int) -> Double {
        fade + Double(min(rows - 1, maxStaggered)) * stagger
    }

    func body(content: Content) -> some View {
        content
            .offset(x: active ? 28 : 0)
            .opacity(active ? 0 : 1)
            .animation(active ? .easeIn(duration: Self.fade).delay(Double(min(index, Self.maxStaggered)) * Self.stagger) : nil,
                       value: active)
    }
}

/// A row's own remove button: a small ×, before the row's buttons while the pointer is on the row.
private struct RowRemove: View {
    let visible: Bool
    let help: String
    let action: () -> Void

    var body: some View {
        if visible {
            Button(action: action) { NudgeImage("xmark") }
                .buttonStyle(WRound(size: 22))
                .help(help)
                .accessibilityHidden(true) // The row's own Remove action covers VoiceOver.
                .transition(.opacity)
        }
    }
}

/// Rows in a grouped list (Notifications, Recent): a 32 pt icon, then the text column.
enum RowMetrics {
    static let icon: CGFloat = 32
    static let iconGap = WSpace.s
    static let horizontal = WSpace.m
    static let vertical = WSpace.m
    /// Where the text starts, for the dividers between rows.
    static let textInset = horizontal + icon + iconGap
}

/// Lets a received file be dragged out of its row into Finder or another app.
private struct FileDrag: ViewModifier {
    let url: URL?

    func body(content: Content) -> some View {
        if let url {
            content.onDrag { NSItemProvider(contentsOf: url) ?? NSItemProvider() }
        } else {
            content
        }
    }
}

// MARK: - Pairing and onboarding

private struct PairingCard: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        VStack(alignment: .leading, spacing: WSpace.s) {
            Text("Pair with \(model.pairingRequest?.phoneName ?? "your phone")?")
                .font(.wh(12, .semibold)).tracking(0.1).foregroundStyle(WTheme.secondary).lineLimit(1)
            RollingCode(code: model.pairingRequest?.code ?? "", font: .wh(30, .semibold).monospacedDigit(), tracking: 3, from: .top)
                .id(model.pairingRequest?.code ?? "")
            Text("Check your phone shows the same code.").font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
            HStack(spacing: WSpace.xs) {
                Button("Pair") { model.decidePairing(true) }.buttonStyle(WPill()).keyboardShortcut(.defaultAction)
                Button("Decline") { model.decidePairing(false) }.buttonStyle(WPill(prominent: false))
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(WSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).fill(theme.theme.card))
    }
}

/// Asked once, after the first phone is paired.
private struct LoginOfferCard: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        VStack(alignment: .leading, spacing: WSpace.xxs) {
            Text("Open Lync when you log in?").font(.wh(14, .semibold))
            Text("Codes and files keep arriving without opening it each time.")
                .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: WSpace.xs) {
                Button("Turn on") { model.answerLoginOffer(true) }.buttonStyle(WPill(compact: true))
                Button("Not now") { model.answerLoginOffer(false) }.buttonStyle(WPill(prominent: false, compact: true))
            }
            .padding(.top, WSpace.xs)
        }
        .padding(WSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).fill(theme.theme.card))
        .accessibilityElement(children: .contain)
    }
}

/// Not paired yet: one object per step on black. First launch, "Lync lives here" teaches ⌥⌘L
/// (pressing it, or Continue, moves on); then the QR, waiting for the phone. Pairing ends it with
/// the "Linked with …" note.
private struct OnboardingPage: View {
    @EnvironmentObject var theme: ThemeStore
    @ObservedObject var model: AppModel

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Group {
                switch model.onboardingStep {
                case .hotkey:
                    // Shown once: counted as seen as soon as it appears, so the next launch starts
                    // at the QR even if this one was closed without pressing the keys.
                    hotkeyStep.onAppear { UserDefaults.standard.set(true, forKey: "onboardingHotkeySeen") }
                case .scan: scanStep
                }
            }
            .id(model.onboardingStep)
            .transition(WMotion.transition(.asymmetric(
                insertion: .offset(x: 24).combined(with: .opacity),
                removal: AnyTransition.offset(x: -24).combined(with: .opacity).combined(with: .collapsingHeight))))
            .frame(maxWidth: .infinity)

            Button { model.panelPage = .settings } label: { NudgeImage("gearshape") }
                .buttonStyle(WRound(size: 32))
                .opacity(0.7)
                .help("Settings").accessibilityLabel("Settings")
                .revealSection(0)
        }
        .animation(WMotion.animation(WMotion.smooth), value: model.onboardingStep)
        .padding(WSpace.m)
    }

    private var hotkeyStep: some View {
        VStack(spacing: 0) {
            LinkCatchView(connected: true, animate: false, haptic: false)
                .frame(width: 64, height: 64)
                .revealSection(0)
            Text("Lync lives here.").font(.whTitle(28))
                .padding(.top, WSpace.l)
                .accessibilityAddTraits(.isHeader)
                .revealSection(1)
            Text("Press these keys to open it from anywhere.")
                .font(.wh(15)).foregroundStyle(.white.opacity(0.56))
                .multilineTextAlignment(.center)
                .padding(.top, WSpace.xs)
                .revealSection(1)
            HStack(spacing: WSpace.xs) {
                Keycap(label: "⌥")
                Keycap(label: "⌘")
                Keycap(label: "L", accent: true, pulsing: model.panelOpen)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Option Command L")
            .padding(.top, WSpace.xl)
            .revealSection(2)
            Button("Continue") { model.finishHotkeyStep() }
                .buttonStyle(WPlainPress())
                .font(.wh(14, .medium)).foregroundStyle(.white.opacity(0.56))
                .keyboardShortcut(.defaultAction)
                .padding(.top, WSpace.xl)
                .revealSection(3)
        }
        .padding(.top, WSpace.xxl).padding(.bottom, WSpace.xs)
    }

    private var scanStep: some View {
        VStack(spacing: 0) {
            Text("Scan with your phone.").font(.whTitle(28))
                .accessibilityAddTraits(.isHeader)
                .revealSection(0)
            PairingQR(model: model, bare: true)
                .padding(.top, WSpace.l)
                .revealSection(1)
            // Where to scan from, and the one thing that has to be true: first-timers need it most.
            Text("Open Lync on your phone and tap Pair with QR code. Use the same Wi‑Fi as this Mac.")
                .font(.wh(13, .medium)).foregroundStyle(WTheme.secondary)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                .padding(.top, WSpace.m).padding(.horizontal, WSpace.l)
                .revealSection(1)
            HStack(spacing: WSpace.xs) {
                WaitingArc(spinning: model.panelOpen)
                Text("Waiting for your phone")
            }
            .font(.wh(14)).foregroundStyle(.white.opacity(0.56))
            .accessibilityElement(children: .combine)
            .padding(.top, WSpace.l)
            .revealSection(2)
            Button("Get Lync for Android") { NSWorkspace.shared.open(lyncReleasesURL) }
                .buttonStyle(WPlainPress())
                .font(.wh(13, .medium)).foregroundStyle(theme.theme.codeAccent)
                .help(lyncReleasesURL.absoluteString)
                .padding(.top, WSpace.l)
                .revealSection(3)
        }
        // Clears the settings button above the title.
        .padding(.top, WSpace.xxxl).padding(.bottom, WSpace.xxs)
    }
}

/// A small turning arc in the label's colour: "still waiting". Still with Reduce Motion.
private struct WaitingArc: View {
    var spinning: Bool
    @State private var turned = false

    var body: some View {
        Circle().trim(from: 0, to: 0.7)
            .stroke(style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
            .frame(width: 12, height: 12)
            .rotationEffect(.degrees(turned ? 360 : 0))
            .animation(turned ? .linear(duration: 0.9).repeatForever(autoreverses: false) : nil, value: turned)
            .onAppear { turned = spinning && !WMotion.reduceMotion }
            .onChange(of: spinning) { _, now in turned = now && !WMotion.reduceMotion }
            .accessibilityHidden(true)
    }
}

/// A keyboard key for "Lync lives here": a dark cap on a thicker bottom edge. The accent key has a
/// mint edge and, while `pulsing`, presses itself down three times, a couple of seconds apart
/// (never with Reduce Motion).
private struct Keycap: View {
    @EnvironmentObject var theme: ThemeStore
    let label: String
    var accent = false
    var pulsing = false
    @State private var down = false

    private static let face = Color(red: 0x0B / 255.0, green: 0x0F / 255.0, blue: 0x0D / 255.0)

    var body: some View {
        let edge = accent ? theme.theme.codeAccent : Color.white.opacity(0.16)
        // The edge shows 1 pt around the face and 4 pt under it; pressed, the face sinks onto it.
        ZStack(alignment: .top) {
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(edge)
            RoundedRectangle(cornerRadius: 13, style: .continuous).fill(Self.face)
                .padding(.horizontal, 1).padding(.top, 1).padding(.bottom, down ? 1.5 : 4)
            Text(label).font(.wh(22, .semibold)).foregroundStyle(.white)
                .frame(maxWidth: .infinity).frame(height: 54 - (down ? 1.5 : 4))
        }
        .frame(width: 54, height: down ? 51.5 : 54)
        .frame(height: 54, alignment: .bottom)
        .task(id: pulsing) {
            guard pulsing else { down = false; return }
            // Three presses show what to do; then it rests, rather than tapping away forever.
            for _ in 0..<3 where !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1.6))
                guard !Task.isCancelled, !WMotion.reduceMotion else { continue }
                withAnimation(WMotion.press) { down = true }
                try? await Task.sleep(for: .seconds(0.18))
                withAnimation(WMotion.release) { down = false }
            }
        }
    }
}

private struct AddPhonePage: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: WSpace.m) {
            PageHeader(title: "Add a phone") { model.panelPage = .home }
            PairingQR(model: model)
        }
        .padding(WSpace.m)
    }
}

private struct PageHeader: View {
    let title: String
    let back: () -> Void

    var body: some View {
        HStack(spacing: WSpace.s) {
            Button(action: back) { NudgeImage("chevron.left") }.buttonStyle(WRound(size: 32))
                .help("Back").accessibilityLabel("Back")
            Text(title).font(.whTitle(22))
            Spacer()
        }
    }
}

// MARK: - Settings

private struct SettingsPage: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        MaybeScroll {
            VStack(alignment: .leading, spacing: 0) {
                PageHeader(title: "Settings") { model.panelPage = .home }

                Text("Show Lync in").font(.wh(14, .semibold)).padding(.top, WSpace.l)
                HStack(spacing: WSpace.xs) {
                    ForEach(DisplayMode.allCases) { mode in
                        let on = model.displayMode == mode
                        let available = mode != .notch || model.hasNotch
                        Button {
                            model.displayMode = mode
                        } label: {
                            Label { Text(mode.label) } icon: { NudgeImage(mode == .notch ? "laptopcomputer" : "menubar.rectangle") }
                                .labelStyle(.titleAndIcon)
                                .font(.wh(13, .semibold))
                                .foregroundStyle(on ? theme.theme.onPale : .white)
                                .frame(maxWidth: .infinity).frame(height: 40)
                                .background(Capsule().fill(on ? theme.theme.pale : WTheme.chip))
                                .animation(WMotion.animation(WMotion.snappy), value: on)
                        }
                        .buttonStyle(WShapePress(shape: Capsule()))
                        .disabled(!available)
                        .opacity(available ? 1 : 0.4)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                .padding(.top, WSpace.xs)
                Text(model.hasNotch
                     ? (model.displayMode == .notch ? "Click the notch to open Lync. Drop files on it to send them; right-click it for more." : "Click the Lync icon in the menu bar to open Lync. Right-click it for more.")
                     : model.notchUnavailable
                        ? "The notch isn't available right now, so Lync is in the menu bar."
                        : "This Mac has no notch, so Lync lives in the menu bar.")
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).padding(.top, WSpace.xs)
                    .fixedSize(horizontal: false, vertical: true)

                group("General") {
                    if model.canLaunchAtLogin {
                        toggle("Open at login", Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) }))
                        if let problem = model.loginItemProblem {
                            HStack(alignment: .titleRow, spacing: WSpace.xs) {
                                note(problem)
                                Spacer(minLength: WSpace.xs)
                                Button("Open") { model.openLoginItemsSettings() }
                                    .buttonStyle(WPill(prominent: false, compact: true))
                                    .help("Open System Settings › General › Login Items")
                            }
                            .padding(.horizontal, WSpace.m).padding(.bottom, WSpace.s)
                        }
                        divider
                    }
                    row("Open Lync") {
                        Text(HotKey.display).font(.wh(13, .semibold)).foregroundStyle(WTheme.secondary)
                            .strikethrough(!model.hotkeyAvailable)
                            .padding(.horizontal, WSpace.xs).frame(height: 24)
                            .background(Capsule().fill(WTheme.chip))
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(model.hotkeyAvailable ? "Open Lync: Option Command L" : "Open Lync: Option Command L is taken by another app")
                    if !model.hotkeyAvailable {
                        HStack { note(Problem.hotkeyTaken); Spacer() }
                            .padding(.horizontal, WSpace.m).padding(.bottom, WSpace.s)
                    }
                    divider
                    row("Lync \(model.appVersion)") {
                        HStack(spacing: WSpace.xs) {
                            Button("Check for updates") { Updates.check() }.buttonStyle(WPill(prominent: false, compact: true))
                            Button("About") { model.showAbout() }.buttonStyle(WPill(prominent: false, compact: true))
                        }
                    }
                }

                group("Clipboard and alerts") {
                    toggle("Copy received text automatically", $model.copyTextAutomatically)
                    divider
                    toggle("Show code digits on the card", $model.showCodeOnArrival)
                    divider
                    toggle("Show phone notifications", $model.showNotifications)
                    divider
                    toggle("Hide notification and text previews", $model.hideNotificationText)
                }

                if !model.mutedApps.isEmpty {
                    group("Muted apps") {
                        ForEach(model.mutedApps.sorted(by: { $0.value < $1.value }), id: \.key) { pkg, name in
                            row(name) { Button("Unmute") { model.unmute(pkg) }.buttonStyle(WPill(prominent: false, compact: true)) }
                        }
                    }
                }

                group("Phones") {
                    let reachable = model.pairedPhones.count > 1 ? model.reachablePhoneIds() : []
                    // One phone: it's on the Home card, with its own Forget. The list only earns its
                    // place with two or more, to see them all, pick the active one or forget another.
                    ForEach(model.pairedPhones.count > 1 ? model.pairedPhones : []) { phone in
                        Group {
                            if model.confirmingForget == phone.id {
                                forgetConfirmation(phone)
                            } else {
                                phoneRow(phone, reachable: reachable)
                            }
                        }
                        .transition(.opacity)
                        divider
                    }
                    row("Add a phone") {
                        Button("Show code") { model.panelPage = .addPhone }.buttonStyle(WPill(compact: true))
                    }
                }

                HStack(spacing: WSpace.xs) {
                    Button("Received files") { model.openReceivedFolder() }.buttonStyle(WPill(prominent: false))
                    Spacer()
                    Button("Quit") { NSApp.terminate(nil) }.buttonStyle(WPill(prominent: false)).keyboardShortcut("q")
                }
                .padding(.top, WSpace.l)
                // Closes the panel and shows a sample card where codes appear.
                Button(model.displayMode == .notch && model.hasNotch ? "See how codes look in the notch" : "See how codes look") {
                    model.previewCard()
                }
                .buttonStyle(WPlainPress())
                .font(.wh(12, .semibold)).foregroundStyle(WTheme.secondary)
                .padding(.top, WSpace.m)
            }
            .padding(.horizontal, WSpace.gutter).padding(.top, WSpace.m).padding(.bottom, WSpace.l)
            // Forget's confirmation opens in place; muted apps come and go.
            .animation(WMotion.animation(WMotion.smooth), value: model.confirmingForget)
            .animation(WMotion.animation(WMotion.smooth), value: model.mutedApps.count)
            .animation(WMotion.animation(WMotion.smooth), value: ListChange(model.pairedPhones, \.id))
        }
        .frame(maxHeight: PanelSnapshots.active ? 2000 : 600)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func group<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: WSpace.s) {
            Text(title).font(.whTitle(16)).accessibilityAddTraits(.isHeader)
                .padding(.leading, WSpace.xxs)
            VStack(spacing: 0) { content() }
                .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
                .clipShape(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous))
        }
        .padding(.top, WSpace.xl)
    }

    private var divider: some View {
        Divider().overlay(Color.white.opacity(0.08)).padding(.leading, WSpace.m)
    }

    /// Why a setting didn't work, and what to do instead.
    private func note(_ problem: ProblemText) -> some View {
        VStack(alignment: .leading, spacing: WSpace.xxs) {
            Text(problem.title).font(.wh(12, .semibold))
                .alignmentGuide(.titleRow) { $0[VerticalAlignment.center] }
            if let detail = problem.detail {
                Text(detail).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    /// A paired phone: "Connected" for the one Lync shows; with several, another one that's
    /// connected in the background can be made the active one.
    private func phoneRow(_ phone: PairedPhone, reachable: Set<String>) -> some View {
        let active = model.connectedPhone?.id == phone.id
        // The buttons line up with the name.
        return HStack(alignment: .titleRow, spacing: WSpace.xs) {
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                Text(phone.name).font(.wh(13, .medium)).lineLimit(1)
                    .alignmentGuide(.titleRow) { $0[VerticalAlignment.center] }
                if active {
                    Text("Connected").font(.wh(11, .semibold)).foregroundStyle(theme.theme.codeAccent)
                } else if model.pairedPhones.count > 1 {
                    Text(reachable.contains(phone.id) ? "Connected in the background" : "Not connected")
                        .font(.wh(11, .medium)).foregroundStyle(WTheme.secondary)
                }
            }
            Spacer(minLength: WSpace.xs)
            if !active, reachable.contains(phone.id) {
                Button("Use this phone") { model.makeActive(phone) }
                    .buttonStyle(WPill(compact: true))
                    .accessibilityLabel("Use \(phone.name)")
            }
            Button("Forget") { model.confirmingForget = phone.id }
                .buttonStyle(WPill(prominent: false, compact: true))
                .accessibilityLabel("Forget \(phone.name)")
        }
        .padding(.horizontal, WSpace.m)
        .padding(.vertical, WSpace.s)
        .frame(minHeight: 48)
        .rowHover()
    }

    /// Forget needs a second click: the phone has to pair again afterwards.
    private func forgetConfirmation(_ phone: PairedPhone) -> some View {
        VStack(alignment: .leading, spacing: WSpace.xxs) {
            Text("Forget \(phone.name)?").font(.wh(13, .semibold)).lineLimit(1)
            Text("You'll need to pair again.").font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
            HStack(spacing: WSpace.xs) {
                Spacer()
                Button("Cancel") { model.confirmingForget = nil }.buttonStyle(WPill(prominent: false, compact: true))
                Button("Forget") { model.forget(phone) }.buttonStyle(WPill(compact: true, destructive: true))
                    .accessibilityLabel("Forget \(phone.name)")
            }
            .padding(.top, WSpace.xs)
        }
        .padding(WSpace.m)
        .accessibilityElement(children: .contain)
    }

    private func toggle(_ title: String, _ value: Binding<Bool>) -> some View {
        // The whole row is the target, as in System Settings: a click on the words toggles too.
        row(title) { WSwitch(isOn: value, label: title) }
            .contentShape(Rectangle())
            .onTapGesture { value.wrappedValue.toggle() }
    }

    private func row<Trailing: View>(_ title: String, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack(spacing: WSpace.xs) {
            Text(title).font(.wh(13, .medium)).lineLimit(1)
            Spacer(minLength: WSpace.xs)
            trailing()
        }
        .padding(.horizontal, WSpace.m)
        .frame(minHeight: 48)
        .rowHover()
    }
}

/// Material 3 style switch, like the phone's: pale track and dark thumb when on.
struct WSwitch: View {
    @Binding var isOn: Bool
    /// The row's title, for VoiceOver.
    var label: String
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        Button { isOn.toggle() } label: {
            ZStack(alignment: isOn ? .trailing : .leading) {
                Capsule().fill(isOn ? theme.theme.pale : Color.clear)
                    .overlay(Capsule().strokeBorder(isOn ? theme.theme.pale : Color.white.opacity(0.45), lineWidth: 2))
                Circle().fill(isOn ? theme.theme.onPale : Color.white.opacity(0.6))
                    .frame(width: isOn ? 16 : 12, height: isOn ? 16 : 12)
                    // Centred in the track: 4 pt round the thumb when on, 6 when off.
                    .padding(.horizontal, isOn ? 4 : 6)
            }
            .frame(width: 44, height: 24)
            .animation(WMotion.animation(WMotion.snappy), value: isOn)
        }
        // Focus ring and hover like the app's other buttons.
        .buttonStyle(WShapePress(shape: Capsule()))
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isToggle)
        .accessibilityValue(isOn ? "On" : "Off")
    }
}

/// A scroll view, except when rendering snapshots (ImageRenderer draws scroll views blank).
struct MaybeScroll<Content: View>: View {
    static var scrolls: Bool { !PanelSnapshots.active }
    @ViewBuilder let content: () -> Content

    /// How far it's scrolled, how tall the content is, and how tall the window onto it.
    @State private var offset: CGFloat = 0
    @State private var contentHeight: CGFloat = 0
    @State private var viewport: CGFloat = 0

    private var moreAbove: Bool { offset > 2 }
    private var moreBelow: Bool { contentHeight - offset - viewport > 2 }

    var body: some View {
        // No scroller, not even while scrolling. Instead the edge with more beyond it fades, so
        // it's plain there's more to see without a bar.
        if Self.scrolls {
            ScrollView {
                content().background(GeometryReader { g in
                    Color.clear.preference(key: ScrollMetrics.self,
                                           value: [-g.frame(in: .named("maybeScroll")).minY, g.size.height])
                })
            }
            .scrollIndicators(.never)
            .coordinateSpace(name: "maybeScroll")
            .onPreferenceChange(ScrollMetrics.self) { v in
                guard v.count == 2 else { return }
                offset = v[0]; contentHeight = v[1]
            }
            .background(GeometryReader { g in
                Color.clear.onAppear { viewport = g.size.height }
                    .onChange(of: g.size.height) { _, h in viewport = h }
            })
            .mask {
                VStack(spacing: 0) {
                    LinearGradient(colors: [moreAbove ? .clear : .black, .black], startPoint: .top, endPoint: .bottom)
                        .frame(height: 24)
                    Rectangle()
                    LinearGradient(colors: [.black, moreBelow ? .clear : .black], startPoint: .top, endPoint: .bottom)
                        .frame(height: 36)
                }
            }
            .animation(WMotion.animation(WMotion.quick), value: moreAbove)
            .animation(WMotion.animation(WMotion.quick), value: moreBelow)
        } else {
            content()
        }
    }
}

private struct ScrollMetrics: PreferenceKey {
    static let defaultValue: [CGFloat] = []
    static func reduce(value: inout [CGFloat], nextValue: () -> [CGFloat]) { let n = nextValue(); if !n.isEmpty { value = n } }
}

enum PanelSnapshots {
    /// Set by the snapshot renderer (scripts/Snapshots).
    nonisolated(unsafe) static var active = false
    /// A fixed time for the phone's lock-screen clock, so snapshots don't change with the hour.
    nonisolated(unsafe) static var clock: Date?
}

func spaced(_ code: String) -> String {
    guard code.count == 6, code.allSatisfy(\.isNumber) else { return code }
    return code.prefix(3) + " " + code.suffix(3)
}

/// A code as dots, grouped like `spaced`, for places anyone looking at the screen can see.
func masked(_ code: String) -> String {
    String(spaced(code).map { $0 == " " ? " " : "•" })
}

/// A Copy button that confirms itself: "Copied" with a checkmark for a moment after a click.
/// Icon-only buttons grow into a small "Copied" pill so the confirmation is readable.
struct CopyButton<S: ButtonStyle>: View {
    @EnvironmentObject var theme: ThemeStore
    var iconOnly = false
    let style: S
    /// What it copies, for VoiceOver and the tooltip ("Copy code 731 942", "Copy 1 photo").
    var label = "Copy"
    /// Changing it copies as if the button was clicked (e.g. a click on the whole row).
    var trigger = 0
    let action: () -> Void
    @State private var copied: Bool
    @State private var resetToken = 0

    init(iconOnly: Bool = false, style: S, label: String = "Copy", startCopied: Bool = false, trigger: Int = 0,
         action: @escaping () -> Void) {
        self.iconOnly = iconOnly
        self.style = style
        self.label = label
        self.trigger = trigger
        self.action = action
        _copied = State(initialValue: startCopied)
    }

    var body: some View {
        // Copied: the label says so ("Copy" → "Copied", in a width that fits both, so the button
        // never resizes) and the icon turns into a tick. Round icon buttons fill with the accent
        // and show the tick on it. A light tap on the trackpad confirms it too.
        Button(action: copy) {
            if iconOnly {
                ZStack {
                    Circle().fill(theme.theme.codeAccent)
                        .frame(width: 24, height: 24)
                        .scaleEffect(copied ? 1 : 0.4)
                        .opacity(copied ? 1 : 0)
                    icon(tick: theme.theme.codeCard)
                }
            } else {
                Label {
                    ZStack(alignment: .leading) {
                        Text("Copied").hidden()
                        Text("Copy").swapState(shown: !copied)
                        Text("Copied").swapState(shown: copied)
                    }
                } icon: { icon(tick: nil) }
            }
        }
        .buttonStyle(style)
        .animation(WMotion.animation(WMotion.quick), value: copied)
        .help(copied ? "Copied" : label)
        .accessibilityLabel(copied ? "Copied" : label)
        .onChange(of: trigger) { _, _ in copy() }
    }

    /// The copy icon, or a tick that pops in a little past full size and settles. `tick`: its
    /// colour on a filled round button; otherwise it keeps the button's own text colour.
    private func icon(tick: Color?) -> some View {
        ZStack {
            NudgeImage("doc.on.doc").swapState(shown: !copied)
            Group {
                // No colour of its own on a pill: the tick is the pill's text colour.
                if let tick { NudgeImage("checkmark").foregroundStyle(tick) } else { NudgeImage("checkmark") }
            }
                .fontWeight(.bold)
                .scaleEffect(copied ? 1 : 0.5)
                .swapState(shown: copied)
                .animation(WMotion.reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.55), value: copied)
        }
    }

    private func copy() {
        action()
        if !copied { NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now) }
        copied = true
        resetToken += 1
        let token = resetToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
            if token == resetToken { copied = false }
        }
    }
}

/// Double-click opens the row's files in their apps (several: each one).
private struct OpenOnDoubleClick: ViewModifier {
    let files: [URL]
    func body(content: Content) -> some View {
        if files.isEmpty {
            content
        } else {
            content.onTapGesture(count: 2) { files.forEach { NSWorkspace.shared.open($0) } }
        }
    }
}
