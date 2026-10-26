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
                    RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Color.black.opacity(0.55))
                    RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(WTheme.codeMint, lineWidth: 2).padding(6)
                    Label { Text("Drop to send to \(phone.name)") } icon: { NudgeImage("arrow.up.doc.fill") }
                        .labelStyle(.titleAndIcon)
                        .font(.wh(15, .semibold)).foregroundStyle(WTheme.codeMint)
                }
                .allowsHitTesting(false)
                .transition(.opacity)
            } else if let problem = model.panelDropProblem {
                // Files held over the panel that can't go: why, and what to do.
                ZStack {
                    RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Color.black.opacity(0.6))
                    RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(WTheme.danger, lineWidth: 2).padding(6)
                    VStack(spacing: 6) {
                        Label { Text(problem.title) } icon: { NudgeImage(problem.symbol) }
                            .labelStyle(.titleAndIcon)
                            .font(.wh(15, .semibold)).foregroundStyle(WTheme.danger)
                        if let detail = problem.detail {
                            Text(detail).font(.wh(13, .medium)).foregroundStyle(WTheme.secondary)
                        }
                    }
                    .multilineTextAlignment(.center).padding(.horizontal, 30)
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
                    .padding(.bottom, 16)
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

/// A small capsule at the bottom of the panel: how an action went, sometimes with Undo. A problem
/// has two lines (why, then what to do) and often a button that does it.
struct ToastView: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
    let toast: Toast

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: toast.detail == nil ? 18 : 16, style: .continuous)
        HStack(spacing: 10) {
            NudgeImage(toast.symbol).font(.wh(12, .semibold))
                .foregroundStyle(toast.isProblem ? WTheme.danger : .white)
            VStack(alignment: .leading, spacing: 2) {
                Text(toast.message).font(.wh(12, .semibold)).lineLimit(2)
                if let detail = toast.detail {
                    Text(detail).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(2)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            if let title = toast.actionTitle {
                Spacer(minLength: 4)
                actionButton(title)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .padding(.vertical, toast.detail == nil ? 0 : 10)
        .frame(minHeight: 36)
        .frame(maxWidth: toast.detail == nil ? nil : 340)
        .background(shape.fill(theme.theme.card))
        .overlay(shape.strokeBorder(WTheme.edge, lineWidth: 1))
        .shadow(color: .black.opacity(0.35), radius: 10, y: 4)
        // Reading it, or reaching for Undo: it waits.
        .onHover { model.toastHovered = $0 }
        .accessibilityElement(children: .combine)
    }

    /// Undo also answers ⌘Z; other buttons are only clicked.
    @ViewBuilder private func actionButton(_ title: String) -> some View {
        let button = Button(title) { model.runToastAction() }
            .buttonStyle(WPlainPress())
            .font(.wh(12, .semibold))
            .foregroundStyle(theme.theme.pale)
            .fixedSize()
        if title == "Undo" { button.keyboardShortcut("z", modifiers: .command) } else { button }
    }
}

// MARK: - Home

private struct HomePage: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
    /// 0…1, a single glow on the phone's name when it connects.
    @State private var glow: CGFloat = 0

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
                    PhoneCard(model: model, name: phone.name, glow: glow).padding(.top, 16).revealSection(0)
                }
                if model.offerLoginItem {
                    LoginOfferCard(model: model).padding(.top, 16).revealSection(1)
                        .transition(WMotion.transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top))))
                }
                if model.pairingRequest != nil {
                    PairingCard(model: model).padding(.top, 16).revealSection(1)
                        .transition(WMotion.transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top))))
                }
                sendRow.padding(.top, 18).revealSection(1)
                if let outgoing = model.outgoing {
                    ProgressView(value: outgoing.progress)
                        .progressViewStyle(.linear)
                        .tint(theme.theme.pale)
                        .animation(WMotion.animation(WMotion.smooth), value: outgoing.progress)
                        .padding(.top, 12)
                        .transition(.opacity)
                }
                Group {
                    if let code = model.latestCode {
                        LatestCodeCard(model: model, code: code).padding(.top, 18)
                            .transition(WMotion.transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top))))
                    }
                    if let playing = model.nowPlaying {
                        NowPlayingCard(model: model, playing: playing).padding(.top, 14)
                            .transition(.opacity)
                    }
                }
                .revealSection(2)
                if !model.notifications.isEmpty {
                    Group {
                        SectionTitle("Notifications", action: ("Clear all", { model.dismissAllNotifications() }),
                                     actionHelp: "Also clears them on your phone").padding(.top, 22)
                        VStack(spacing: 0) {
                            ForEach(Array(model.notifications.prefix(6).enumerated()), id: \.element.id) { index, notification in
                                VStack(spacing: 0) {
                                    if index > 0 { Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 56) }
                                    NotificationRow(model: model, notification: notification)
                                }
                                .id("notif-\(notification.key)")
                                .transition(listTransition)
                            }
                        }
                        .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
                        .clipShape(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous))
                        .padding(.top, 10)
                    }
                    .transition(.opacity)
                    .revealSection(3)
                }
                // The latest code has its own card above, so Recent leaves it out; with nothing
                // else to show, the section waits until there is.
                if model.activity.isEmpty || !RecentList.items(model).isEmpty {
                    Group {
                        SectionTitle("Recent", action: model.activity.isEmpty ? nil : ("Clear", { model.clearActivity() })).padding(.top, 22)
                        RecentList(model: model).padding(.top, 10)
                    }
                    .revealSection(4)
                }
            }
            .padding(20)
            .onChange(of: model.connectedPhone?.id) { old, new in
                guard new != nil, old == nil, model.panelOpen, !WMotion.reduceMotion else { return }
                withAnimation(.easeOut(duration: 0.25)) { glow = 1 }
                withAnimation(.easeInOut(duration: 0.9).delay(0.25)) { glow = 0 }
            }
            // Rows coming and going (and Undo bringing them back) move the rest smoothly.
            .animation(WMotion.animation(WMotion.smooth), value: ListChange(model.notifications, \.key))
            .animation(WMotion.animation(WMotion.smooth), value: ListChange(model.activity, \.id))
            .animation(WMotion.animation(WMotion.smooth), value: model.latestCode?.code)
            .animation(WMotion.animation(WMotion.smooth), value: model.pairingRequest != nil)
            .animation(WMotion.animation(WMotion.smooth), value: model.outgoing == nil)
            .animation(WMotion.animation(WMotion.smooth), value: model.offerLoginItem)
        }
        .frame(maxHeight: 600)
        .fixedSize(horizontal: false, vertical: true)
        // A clicked notification card: scroll to that notification once the panel is open.
        .onAppear { scroll(proxy) }
        .onChange(of: model.scrollTarget) { _, _ in scroll(proxy) }
        }
    }

    private func scroll(_ proxy: ScrollViewProxy) {
        guard let target = model.scrollTarget else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            withMotion(WMotion.smooth) { proxy.scrollTo(target, anchor: .center) }
            model.scrollTarget = nil
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Your phone").font(.whTitle(24))
                HStack(spacing: 7) {
                    LinkCatchView(connected: model.connectedPhone != nil, animate: model.panelOpen, shaded: false, haptic: false)
                        .frame(width: 18, height: 18)
                    Text(statusLine).font(.wh(13, model.connectionProblem == nil ? .medium : .semibold))
                        .foregroundStyle(model.connectionProblem == nil ? WTheme.secondary : .white)
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
                        Text(detail).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.leading, 25)
                            .contentTransition(.opacity)
                    }
                    if let title = problem.actionTitle, let action = problem.action {
                        Button(title, action: action).buttonStyle(WPill(compact: true)).padding(.top, 6).padding(.leading, 25)
                    }
                }
                if let note = model.connectionNote {
                    ConnectionNote(problem: note).padding(.top, 8).padding(.leading, 25)
                        .transition(.opacity)
                }
            }
            .animation(WMotion.animation(WMotion.smooth), value: model.connectionProblem?.title)
            .animation(WMotion.animation(WMotion.smooth), value: model.connectionNote?.title)
            Spacer(minLength: 4)
            if model.connectedPhone != nil {
                Button { model.ringPhone() } label: {
                    NudgeImage(model.ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right")
                }
                .buttonStyle(WRound(size: 34))
                .help(model.ringing ? "Stop ringing" : "Ring your phone to find it")
                .accessibilityLabel(model.ringing ? "Stop ringing" : "Ring phone")
            }
            Button { model.panelPage = .settings } label: { NudgeImage("gearshape") }
                .buttonStyle(WRound(size: 34))
                .help("Settings")
                .accessibilityLabel("Settings")
        }
    }

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
        return HStack(spacing: 8) {
            Button { model.sendClipboardToPhone() } label: { Label { Text("Send clipboard") } icon: { NudgeImage("doc.on.clipboard") } }
                .buttonStyle(WPill()).disabled(!connected).opacity(connected ? 1 : 0.45)
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

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            NudgeImage(problem.symbol).font(.wh(11, .semibold)).foregroundStyle(WTheme.secondary).padding(.top, 2)
            VStack(alignment: .leading, spacing: 4) {
                Text(problem.title).font(.wh(12, .semibold))
                if let detail = problem.detail {
                    Text(detail).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).fixedSize(horizontal: false, vertical: true)
                }
                if let title = problem.actionTitle, let action = problem.action {
                    Button(title, action: action).buttonStyle(WPill(prominent: false, compact: true)).padding(.top, 2)
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
    let name: String
    /// 0…1, a single glow on the name when the phone connects.
    var glow: CGFloat

    private var connected: Bool { model.connectedPhone != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .top) {
                LinearGradient(colors: [Color.white.opacity(0.07), Color.white.opacity(0.03)], startPoint: .top, endPoint: .bottom)
                MiniPhone(connected: connected, theme: theme.theme).padding(.top, 22)
            }
            .frame(height: connected ? 196 : 150, alignment: .top)
            .clipped()
            VStack(alignment: .leading, spacing: 4) {
                Text("Android phone").font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                Text(name)
                    .font(.wh(21, .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                    .shadow(color: theme.theme.pale.opacity(0.7 * glow), radius: 14)
                if connected {
                    TimelineView(.periodic(from: .now, by: 30)) { context in
                        Text(detail(now: context.date)).font(.wh(13)).foregroundStyle(WTheme.secondary)
                            .contentTransition(.numericText())
                    }
                } else {
                    HStack {
                        Text(model.bluetoothPhone != nil ? "Over Bluetooth" : "Not connected")
                            .font(.wh(13)).foregroundStyle(WTheme.secondary)
                        Spacer()
                        // Pairing again isn't the fix for a phone that's away; another phone can still be added.
                        Button("Add a phone") { model.panelPage = .addPhone }
                            .buttonStyle(WPlainPress())
                            .font(.wh(12, .semibold)).foregroundStyle(theme.theme.pale)
                            .help("Show a code to pair another phone")
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, connected ? 26 : 18)
            .background(theme.theme.card)
        }
        .clipShape(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).strokeBorder(WTheme.edge, lineWidth: 1))
        // Right-click: what you'd do with the phone, without hunting for the buttons.
        .contextMenu {
            if connected {
                Button("Ring phone") { model.ringPhone() }
                Button("Send clipboard") { model.sendClipboardToPhone() }
                Button("Send files…") { model.chooseFilesToSend() }
                Button("Texts") { model.openMessages() }
                Divider()
            }
            Button("Add a phone") { model.panelPage = .addPhone }
            Button("Settings") { model.panelPage = .settings }
        }
        .accessibilityElement(children: .combine)
        .animation(WMotion.animation(WMotion.smooth), value: connected)
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

/// "Title" with an optional quiet action on the right.
private struct SectionTitle: View {
    let title: String
    var action: (String, () -> Void)?
    var actionHelp: String?

    init(_ title: String, action: (String, () -> Void)? = nil, actionHelp: String? = nil) {
        self.title = title
        self.action = action
        self.actionHelp = actionHelp
    }

    var body: some View {
        HStack {
            Text(title).font(.whTitle(18)).accessibilityAddTraits(.isHeader)
            Spacer()
            if let action {
                Button(action.0, action: action.1).buttonStyle(WPlainPress()).font(.wh(12, .semibold)).foregroundStyle(WTheme.secondary)
                    .help(actionHelp ?? action.0)
                    .accessibilityHint(actionHelp ?? "")
            }
        }
    }
}

/// The newest unexpired code, big, in the contrasting colour. Already on the clipboard.
private struct LatestCodeCard: View {
    @ObservedObject var model: AppModel
    let code: (code: String, sender: String, item: ActivityItem)
    /// A click anywhere on the card copies, with the button's own tick.
    @State private var copyTrigger = 0

    var body: some View {
        // Codes use Lync's fixed Forest and Mint, not the phone's accent.
        let onClipboard = model.codeOnClipboard == code.code
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Latest code").font(.wh(12, .semibold)).tracking(0.1).foregroundStyle(WTheme.codeSecondary)
                RollingCode(code: code.code, font: .wh(30, .semibold).monospacedDigit(), tracking: 2).id(code.code)
                    .foregroundStyle(WTheme.codeMint)
                // After the minute on the clipboard it's cleared: then it only says who sent it.
                Text((code.sender.isEmpty ? "Verification code" : code.sender) + (onClipboard ? " · on your clipboard" : ""))
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.codeSecondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            CopyButton(style: CodePill(), label: "Copy code \(spaced(code.code))", trigger: copyTrigger) { model.copy(code.item) }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).fill(WTheme.codeForest))
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
            .padding(.horizontal, 16).frame(height: 36)
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
        HStack(spacing: 12) {
            Group {
                if let art = model.artwork {
                    Image(nsImage: art).resizable().aspectRatio(contentMode: .fill)
                } else {
                    NudgeImage("music.note").font(.wh(18)).frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(WTheme.chip)
                }
            }
            .frame(width: 52, height: 52)
            .clipShape(RoundedRectangle(cornerRadius: WRadius.field, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(playing.title.isEmpty ? "Unknown title" : playing.title).font(.wh(14, .semibold)).lineLimit(1)
                Text([playing.artist, playing.app].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            Button { model.media("previous") } label: { NudgeImage("backward.fill") }.buttonStyle(WRound(size: 32))
                .help("Previous").accessibilityLabel("Previous")
            Button { model.media("toggle") } label: {
                NudgeImage(playing.playing ? "pause.fill" : "play.fill")
                    .contentTransition(.symbolEffect(.replace))
                    .font(.wh(15, .semibold)).foregroundStyle(theme.theme.onPale)
                    .frame(width: 42, height: 42).background(Circle().fill(theme.theme.pale))
            }
            .buttonStyle(WShapePress(shape: Circle()))
            .help(playing.playing ? "Pause" : "Play")
            .accessibilityLabel(playing.playing ? "Pause" : "Play")
            Button { model.media("next") } label: { NudgeImage("forward.fill") }.buttonStyle(WRound(size: 32))
                .help("Next").accessibilityLabel("Next")
        }
        .padding(12)
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
        HStack(alignment: .top, spacing: 12) {
            AppIcon(image: model.appIcons[notification.packageName], size: 32)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(title).font(.wh(13, .semibold)).lineLimit(1)
                    Spacer()
                    Text(notification.time, style: .time).font(.wh(11, .medium)).foregroundStyle(WTheme.tertiary)
                }
                .accessibilityElement(children: .combine)
                Text(model.hideNotificationText ? "New notification" : notification.text)
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(2)
                if replying {
                    // Reply right here, in the panel.
                    HStack(spacing: 8) {
                        TextField("Reply to \(title)…", text: $draft)
                            .textFieldStyle(.plain)
                            .font(.wh(13, .medium))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(Capsule().fill(Color.white.opacity(0.12)))
                            .focused($focus, equals: .field)
                            .onSubmit(send)
                            .accessibilityLabel("Reply to \(title)")
                        Button("Send", action: send)
                            .buttonStyle(WPill(compact: true))
                            .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                            .focused($focus, equals: .send)
                    }
                    .padding(.top, 8)
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
                HStack(spacing: 6) {
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
        .padding(12)
        .contentShape(Rectangle())
        .rowHover()
        .onHover { hovering = $0 }
        // Keyboard: Tab to the row shows its buttons; Return replies. Only keyboard navigation focuses
        // it (a click doesn't), and our white ring replaces the system's blue one.
        .focusable(interactions: .activate)
        .focusEffectDisabled()
        .focused($focus, equals: .row)
        .modifier(WFocusRing(shape: RoundedRectangle(cornerRadius: 14, style: .continuous), inset: 2))
        .onKeyPress(.return) {
            guard notification.canReply, focus == .row else { return .ignored }
            startReply()
            return .handled
        }
        .accessibilityElement(children: replying ? .contain : .combine)
        .accessibilityActions {
            if notification.canReply { Button("Reply", action: startReply) }
            Button("Clear on phone") { model.dismissNotification(notification.key) }
        }
        .contextMenu {
            if notification.canReply { Button("Reply", action: startReply) }
            Button("Clear on phone") { model.dismissNotification(notification.key) }
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

    /// Activity without the code already shown in the Latest code card.
    static func items(_ model: AppModel) -> [ActivityItem] {
        let shown = model.latestCode?.item.id
        return model.activity.filter { $0.id != shown }
    }

    var body: some View {
        if model.activity.isEmpty {
            VStack(spacing: 10) {
                NudgeImage("tray").font(.wh(22)).foregroundStyle(WTheme.secondary)
                    .frame(width: 56, height: 56).background(Circle().fill(WTheme.chip))
                Text("Codes, texts and files from your phone show up here, already on your clipboard.")
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 22).padding(.horizontal, 20)
            .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
            .transition(.opacity)
        } else {
            VStack(spacing: 0) {
                ForEach(Array(Self.items(model).prefix(12).enumerated()), id: \.element.id) { index, item in
                    VStack(spacing: 0) {
                        if index > 0 { Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 56) }
                        RecentRow(model: model, item: item)
                    }
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

    private var failed: Bool {
        if case .files(let batch) = item.payload { return batch.finished && batch.files.isEmpty }
        return false
    }

    private var expired: Bool {
        if case .files(let batch) = item.payload { return batch.isExpired }
        return false
    }

    var body: some View {
        HStack(spacing: 12) {
            leading.frame(width: 32).shake(failShakes)
            VStack(alignment: .leading, spacing: 2) {
                title
                Text(subtitle).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            trailing
        }
        .padding(12)
        .contentShape(Rectangle())
        .rowHover()
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
        .modifier(WFocusRing(shape: RoundedRectangle(cornerRadius: 14, style: .continuous), inset: 2))
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
        case .otp: CodeIconTile(size: 32, tint: WTheme.codeMint, wash: 0.22)
        case .text: icon("text.bubble.fill", theme.theme.pale)
        case .files(let batch):
            if batch.isExpired { icon("clock.badge.xmark", WTheme.secondary) }
            else if batch.files.isEmpty { icon("arrow.down", batch.finished ? WTheme.danger : theme.theme.pale) } else { ThumbnailStack(urls: batch.files, size: 28) }
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
            CopyButton(iconOnly: true, style: WRound(size: 30), label: copyLabel, trigger: copyTrigger) { model.copy(item) }
        case .text:
            CopyButton(iconOnly: true, style: WRound(size: 30), label: copyLabel, trigger: copyTrigger) { model.copy(item) }
        case .files(let batch) where batch.finished && !batch.files.isEmpty && !batch.isExpired:
            HStack(spacing: 6) {
                CopyButton(iconOnly: true, style: WRound(size: 30), label: copyLabel) { model.copyFiles(item.id) }
                if batch.saved {
                    Button("Show") { model.revealFiles(item.id) }.buttonStyle(WPill(prominent: false, compact: true))
                        .help("Show in Finder")
                } else {
                    Button("Save") { model.saveFiles(item.id) }.buttonStyle(WPill(compact: true))
                        .help("Save to Downloads › From Phone")
                }
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
            .frame(width: 32, height: 32).background(Circle().fill(tint.opacity(0.22)))
    }
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
        VStack(alignment: .leading, spacing: 12) {
            Text("Pair with \(model.pairingRequest?.phoneName ?? "your phone")?")
                .font(.wh(12, .semibold)).tracking(0.1).foregroundStyle(WTheme.secondary).lineLimit(1)
            RollingCode(code: model.pairingRequest?.code ?? "", font: .wh(30, .semibold).monospacedDigit(), tracking: 3, from: .top)
                .id(model.pairingRequest?.code ?? "")
            Text("Check your phone shows the same code.").font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
            HStack(spacing: 8) {
                Button("Pair") { model.decidePairing(true) }.buttonStyle(WPill()).keyboardShortcut(.defaultAction)
                Button("Decline") { model.decidePairing(false) }.buttonStyle(WPill(prominent: false))
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).fill(theme.theme.card))
    }
}

/// Asked once, after the first phone is paired.
private struct LoginOfferCard: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Open Lync when you log in?").font(.wh(14, .semibold))
            Text("Codes and files keep arriving without opening it each time.")
                .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Button("Turn on") { model.answerLoginOffer(true) }.buttonStyle(WPill(compact: true))
                Button("Not now") { model.answerLoginOffer(false) }.buttonStyle(WPill(prominent: false, compact: true))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).fill(theme.theme.card))
        .accessibilityElement(children: .contain)
    }
}

/// Not paired yet: one object per step on black. First launch, "Lync lives here" teaches ⌥⌘L
/// (pressing it, or Continue, moves on); then the QR, waiting for the phone. Pairing ends it with
/// the "Linked with …" note.
private struct OnboardingPage: View {
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
                .buttonStyle(WRound(size: 30))
                .opacity(0.7)
                .help("Settings").accessibilityLabel("Settings")
                .revealSection(0)
        }
        .animation(WMotion.animation(WMotion.smooth), value: model.onboardingStep)
        .padding(20)
    }

    private var hotkeyStep: some View {
        VStack(spacing: 0) {
            LinkCatchView(connected: true, animate: false, haptic: false)
                .frame(width: 64, height: 64)
                .revealSection(0)
            Text("Lync lives here.").font(.whTitle(28))
                .padding(.top, 20)
                .accessibilityAddTraits(.isHeader)
                .revealSection(1)
            Text("Press these keys to open it from anywhere.")
                .font(.wh(15)).foregroundStyle(.white.opacity(0.56))
                .multilineTextAlignment(.center)
                .padding(.top, 8)
                .revealSection(1)
            HStack(spacing: 10) {
                Keycap(label: "⌥")
                Keycap(label: "⌘")
                Keycap(label: "L", accent: true, pulsing: model.panelOpen)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Option Command L")
            .padding(.top, 28)
            .revealSection(2)
            Button("Continue") { model.finishHotkeyStep() }
                .buttonStyle(WPlainPress())
                .font(.wh(14, .medium)).foregroundStyle(.white.opacity(0.56))
                .keyboardShortcut(.defaultAction)
                .padding(.top, 32)
                .revealSection(3)
        }
        .padding(.top, 36).padding(.bottom, 8)
    }

    private var scanStep: some View {
        VStack(spacing: 0) {
            Text("Scan with your phone.").font(.whTitle(28))
                .accessibilityAddTraits(.isHeader)
                .revealSection(0)
            PairingQR(model: model, bare: true)
                .padding(.top, 24)
                .revealSection(1)
            HStack(spacing: 8) {
                WaitingArc(spinning: model.panelOpen)
                Text("Waiting for your phone")
            }
            .font(.wh(14)).foregroundStyle(.white.opacity(0.56))
            .accessibilityElement(children: .combine)
            .padding(.top, 20)
            .revealSection(2)
            Button("Get Lync for Android") { NSWorkspace.shared.open(lyncReleasesURL) }
                .buttonStyle(WPlainPress())
                .font(.wh(13, .medium)).foregroundStyle(WTheme.codeMint)
                .help(lyncReleasesURL.absoluteString)
                .padding(.top, 24)
                .revealSection(3)
        }
        // Clears the settings button above the title.
        .padding(.top, 44).padding(.bottom, 4)
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
    let label: String
    var accent = false
    var pulsing = false
    @State private var down = false

    private static let face = Color(red: 0x0B / 255.0, green: 0x0F / 255.0, blue: 0x0D / 255.0)

    var body: some View {
        let edge = accent ? WTheme.codeMint : Color.white.opacity(0.16)
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
        VStack(alignment: .leading, spacing: 14) {
            PageHeader(title: "Add a phone") { model.panelPage = .home }
            PairingQR(model: model)
        }
        .padding(20)
    }
}

private struct PageHeader: View {
    let title: String
    let back: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: back) { NudgeImage("chevron.left") }.buttonStyle(WRound(size: 34))
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

                Text("Show Lync in").font(.wh(14, .semibold)).padding(.top, 20)
                HStack(spacing: 8) {
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
                .padding(.top, 10)
                Text(model.hasNotch
                     ? (model.displayMode == .notch ? "Click the notch to open Lync. Drop files on it to send them; right-click it for more." : "Click the Lync icon in the menu bar to open Lync. Right-click it for more.")
                     : model.notchUnavailable
                        ? "The notch isn't available right now, so Lync is in the menu bar."
                        : "This Mac has no notch, so Lync lives in the menu bar.")
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).padding(.top, 8)
                    .fixedSize(horizontal: false, vertical: true)

                group("General") {
                    if model.canLaunchAtLogin {
                        toggle("Open at login", Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) }))
                        if let problem = model.loginItemProblem {
                            HStack(alignment: .top, spacing: 8) {
                                note(problem)
                                Spacer(minLength: 4)
                                Button("Open") { model.openLoginItemsSettings() }
                                    .buttonStyle(WPill(prominent: false, compact: true))
                                    .help("Open System Settings › General › Login Items")
                            }
                            .padding(.horizontal, 14).padding(.bottom, 12)
                        }
                        divider
                    }
                    row("Open Lync") {
                        Text(HotKey.display).font(.wh(13, .semibold)).foregroundStyle(WTheme.secondary)
                            .strikethrough(!model.hotkeyAvailable)
                            .padding(.horizontal, 10).frame(height: 26)
                            .background(Capsule().fill(WTheme.chip))
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(model.hotkeyAvailable ? "Open Lync: Option Command L" : "Open Lync: Option Command L is taken by another app")
                    if !model.hotkeyAvailable {
                        HStack { note(Problem.hotkeyTaken); Spacer() }
                            .padding(.horizontal, 14).padding(.bottom, 12)
                    }
                    divider
                    row("Lync \(model.appVersion)") {
                        Button("About") { model.showAbout() }.buttonStyle(WPill(prominent: false, compact: true))
                    }
                }

                group("Clipboard and alerts") {
                    toggle("Copy received text automatically", $model.copyTextAutomatically)
                    divider
                    toggle("Show phone notifications", $model.showNotifications)
                    divider
                    toggle("Hide notification and text previews", $model.hideNotificationText)
                }

                group("Privacy") {
                    toggle("Hide Lync in screenshots and screen sharing", $model.hideFromCapture)
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
                    ForEach(model.pairedPhones) { phone in
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

                HStack(spacing: 8) {
                    Button("Received files") { model.openReceivedFolder() }.buttonStyle(WPill(prominent: false))
                    Spacer()
                    Button("Quit") { NSApp.terminate(nil) }.buttonStyle(WPill(prominent: false)).keyboardShortcut("q")
                }
                .padding(.top, 22)
                // Closes the panel and shows a sample card where codes appear.
                Button(model.displayMode == .notch && model.hasNotch ? "See how codes look in the notch" : "See how codes look") {
                    model.previewCard()
                }
                .buttonStyle(WPlainPress())
                .font(.wh(12, .semibold)).foregroundStyle(WTheme.secondary)
                .padding(.top, 14)
            }
            .padding(20)
            // Forget's confirmation opens in place; muted apps come and go.
            .animation(WMotion.animation(WMotion.smooth), value: model.confirmingForget)
            .animation(WMotion.animation(WMotion.smooth), value: model.mutedApps.count)
            .animation(WMotion.animation(WMotion.smooth), value: ListChange(model.pairedPhones, \.id))
        }
        .frame(maxHeight: 600)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func group<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.whTitle(16)).accessibilityAddTraits(.isHeader)
            VStack(spacing: 0) { content() }
                .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
                .clipShape(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous))
        }
        .padding(.top, 22)
    }

    private var divider: some View {
        Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 14)
    }

    /// Why a setting didn't work, and what to do instead.
    private func note(_ problem: ProblemText) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(problem.title).font(.wh(12, .semibold))
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
        return HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(phone.name).font(.wh(13, .medium)).lineLimit(1)
                if active {
                    Text("Connected").font(.wh(11, .semibold)).foregroundStyle(WTheme.codeMint)
                } else if model.pairedPhones.count > 1 {
                    Text(reachable.contains(phone.id) ? "Connected in the background" : "Not connected")
                        .font(.wh(11, .medium)).foregroundStyle(WTheme.secondary)
                }
            }
            Spacer(minLength: 8)
            if !active, reachable.contains(phone.id) {
                Button("Use this phone") { model.makeActive(phone) }
                    .buttonStyle(WPill(compact: true))
                    .accessibilityLabel("Use \(phone.name)")
            }
            Button("Forget") { model.confirmingForget = phone.id }
                .buttonStyle(WPill(prominent: false, compact: true))
                .accessibilityLabel("Forget \(phone.name)")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(minHeight: 48)
        .rowHover()
    }

    /// Forget needs a second click: the phone has to pair again afterwards.
    private func forgetConfirmation(_ phone: PairedPhone) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Forget \(phone.name)?").font(.wh(13, .semibold)).lineLimit(1)
            Text("You'll need to pair again.").font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
            HStack(spacing: 8) {
                Spacer()
                Button("Cancel") { model.confirmingForget = nil }.buttonStyle(WPill(prominent: false, compact: true))
                Button("Forget") { model.forget(phone) }.buttonStyle(WPill(compact: true, destructive: true))
                    .accessibilityLabel("Forget \(phone.name)")
            }
        }
        .padding(14)
        .accessibilityElement(children: .contain)
    }

    private func toggle(_ title: String, _ value: Binding<Bool>) -> some View {
        // The whole row is the target, as in System Settings: a click on the words toggles too.
        row(title) { WSwitch(isOn: value, label: title) }
            .contentShape(Rectangle())
            .onTapGesture { value.wrappedValue.toggle() }
    }

    private func row<Trailing: View>(_ title: String, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack {
            Text(title).font(.wh(13, .medium)).lineLimit(1)
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 14)
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
                    .frame(width: isOn ? 18 : 12, height: isOn ? 18 : 12)
                    .padding(.horizontal, isOn ? 4 : 7)
            }
            .frame(width: 44, height: 26)
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

    var body: some View {
        // The system's overlay scroller: it shows only while scrolling, so there's a sign of more
        // below without a permanent bar.
        if Self.scrolls { ScrollView(content: content).scrollIndicators(.automatic) } else { content() }
    }
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

/// A Copy button that confirms itself: "Copied" with a checkmark for a moment after a click.
/// Icon-only buttons grow into a small "Copied" pill so the confirmation is readable.
struct CopyButton<S: ButtonStyle>: View {
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
        // The only feedback is the icon: it crossfades into a tick (blur, 90 % → 100 %), then
        // softly back. Nothing else changes, so the button never moves or resizes.
        Button(action: copy) {
            if iconOnly {
                icon
            } else {
                Label { Text("Copy") } icon: { icon }
            }
        }
        .buttonStyle(style)
        .help(copied ? "Copied" : label)
        .accessibilityLabel(copied ? "Copied" : label)
        .onChange(of: trigger) { _, _ in copy() }
    }

    private var icon: some View {
        ZStack {
            NudgeImage("doc.on.doc").swapState(shown: !copied)
            NudgeImage("checkmark").foregroundStyle(WTheme.codeMint).swapState(shown: copied)
        }
        .animation(WMotion.animation(WMotion.quick), value: copied)
    }

    private func copy() {
        action()
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
