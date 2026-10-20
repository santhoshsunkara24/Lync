import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Which page the panel shows.
enum PanelPage: Equatable {
    case home
    case settings
    case addPhone
}

/// Wallhop's panel, in the phone app's style (DESIGN.md "Current direction"): the phone's colour
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
                    Label("Drop to send to \(phone.name)", systemImage: "arrow.up.doc.fill")
                        .labelStyle(.titleAndIcon)
                        .font(.wh(15, .semibold)).foregroundStyle(WTheme.codeMint)
                }
                .allowsHitTesting(false)
                .transition(.opacity)
            }
        }
        .animation(WMotion.animation(WMotion.snappy), value: model.panelDropHover)
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

    private var background: some View {
        let t = theme.theme
        return LinearGradient(
            stops: fromNotch
                ? [.init(color: .black, location: 0), .init(color: t.top, location: 0.22), .init(color: t.mid, location: 0.5), .init(color: WTheme.ink, location: 0.85)]
                : [.init(color: t.top, location: 0), .init(color: t.mid, location: 0.36), .init(color: WTheme.ink, location: 0.8)],
            startPoint: .top, endPoint: .bottom
        )
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

/// A small capsule at the bottom of the panel: how an action went, sometimes with Undo.
struct ToastView: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
    let toast: Toast

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: toast.symbol).font(.wh(12, .semibold))
            Text(toast.message).font(.wh(12, .semibold)).lineLimit(1)
            if let title = toast.actionTitle {
                Button(title) { model.runToastAction() }
                    .buttonStyle(WPlainPress())
                    .font(.wh(12, .semibold))
                    .foregroundStyle(theme.theme.pale)
                    .padding(.leading, 4)
                    .keyboardShortcut("z", modifiers: .command)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .frame(height: 36)
        .background(Capsule().fill(theme.theme.card))
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.12), lineWidth: 1))
        .shadow(color: .black.opacity(0.35), radius: 10, y: 4)
        .accessibilityElement(children: .combine)
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
            .animation(WMotion.animation(WMotion.smooth), value: model.notifications.map(\.key))
            .animation(WMotion.animation(WMotion.smooth), value: model.activity.map(\.id))
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
                Text("Your phone").font(.wh(24, .medium))
                HStack(spacing: 7) {
                    if model.connectedPhone != nil {
                        Circle().fill(WTheme.success).frame(width: 8, height: 8).transition(.scale.combined(with: .opacity))
                    }
                    Text(statusLine).font(.wh(13, .medium)).foregroundStyle(WTheme.secondary)
                        .lineLimit(model.listening ? 1 : 2).fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                    if looking && model.panelOpen {
                        LookingDots().foregroundStyle(WTheme.secondary).transition(.opacity)
                    }
                }
                .animation(WMotion.animation(WMotion.smooth), value: statusLine)
                if model.listenFailed && model.connectedPhone == nil {
                    Button("Try again") { model.retryListening() }
                        .buttonStyle(WPill(compact: true)).padding(.top, 6)
                }
            }
            Spacer(minLength: 4)
            if model.connectedPhone != nil {
                Button { model.ringPhone() } label: {
                    Image(systemName: model.ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right")
                }
                .buttonStyle(WRound(size: 34))
                .help(model.ringing ? "Stop ringing" : "Ring your phone to find it")
                .accessibilityLabel(model.ringing ? "Stop ringing" : "Ring phone")
            }
            Button { model.panelPage = .settings } label: { Image(systemName: "gearshape") }
                .buttonStyle(WRound(size: 34))
                .help("Settings")
                .accessibilityLabel("Settings")
        }
    }

    /// Listening, no phone yet: the only state with a running indicator.
    private var looking: Bool { model.listening && model.connectedPhone == nil }

    private var statusLine: String {
        if model.connectedPhone != nil { return "Connected" }
        if !model.listening { return model.statusMessage }
        return "Waiting for your phone"
    }

    private var sendRow: some View {
        let connected = model.connectedPhone != nil
        return HStack(spacing: 8) {
            Button { model.sendClipboardToPhone() } label: { Label("Send clipboard", systemImage: "doc.on.clipboard") }
                .buttonStyle(WPill()).disabled(!connected).opacity(connected ? 1 : 0.45)
            Button { model.chooseFilesToSend() } label: { Label("Files", systemImage: "paperplane") }
                .buttonStyle(WPill(prominent: false)).disabled(!connected).opacity(connected ? 1 : 0.45)
            Button { model.openMessages() } label: { Label("Texts", systemImage: "message") }
                .buttonStyle(WPill(prominent: false))
        }
        .labelStyle(.titleAndIcon)
    }
}

/// The phone as a little device (the Mac card on the phone, the other way round): its screen in
/// the phone's own colours with the time, cut off by the details below it.
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
            .frame(height: 196, alignment: .top)
            .clipped()
            VStack(alignment: .leading, spacing: 4) {
                Text("Android phone").font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                Text(name)
                    .font(.wh(21, .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                    .shadow(color: theme.theme.pale.opacity(0.7 * glow), radius: 14)
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    Text(detail(now: context.date)).font(.wh(13)).foregroundStyle(WTheme.secondary)
                        .contentTransition(.numericText())
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 26)
            .background(theme.theme.card)
        }
        .clipShape(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).strokeBorder(Color.white.opacity(0.06), lineWidth: 1))
        .accessibilityElement(children: .combine)
        .animation(WMotion.animation(WMotion.smooth), value: connected)
    }

    private func detail(now: Date) -> String {
        guard connected else { return "Not connected" }
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
                VStack(spacing: 2 * u) {
                    Text(context.date, format: .dateTime.hour(.defaultDigits(amPM: .omitted)).minute())
                        .font(.system(size: 34 * u, weight: .light, design: .rounded))
                        .monospacedDigit()
                    Text(context.date, format: .dateTime.weekday(.abbreviated).day().month(.abbreviated))
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
            Text(title).font(.wh(18, .medium)).accessibilityAddTraits(.isHeader)
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
    @EnvironmentObject var theme: ThemeStore
    let code: (code: String, sender: String, item: ActivityItem)

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
            CopyButton(style: CodePill(), label: "Copy code \(spaced(code.code))") { model.copy(code.item) }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: WRadius.card, style: .continuous).fill(WTheme.codeForest))
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
                    Image(systemName: "music.note").font(.wh(18)).frame(maxWidth: .infinity, maxHeight: .infinity)
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
            Button { model.media("previous") } label: { Image(systemName: "backward.fill") }.buttonStyle(WRound(size: 32))
                .help("Previous").accessibilityLabel("Previous")
            Button { model.media("toggle") } label: {
                Image(systemName: playing.playing ? "pause.fill" : "play.fill")
                    .contentTransition(.symbolEffect(.replace))
                    .font(.wh(15, .semibold)).foregroundStyle(theme.theme.onPale)
                    .frame(width: 42, height: 42).background(Circle().fill(theme.theme.pale))
            }
            .buttonStyle(WShapePress(shape: Circle()))
            .help(playing.playing ? "Pause" : "Play")
            .accessibilityLabel(playing.playing ? "Pause" : "Play")
            Button { model.media("next") } label: { Image(systemName: "forward.fill") }.buttonStyle(WRound(size: 32))
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
                } else if hovering || focus != nil {
                    HStack(spacing: 6) {
                        if notification.canReply {
                            Button("Reply", action: startReply).buttonStyle(WPill(compact: true))
                                .focused($focus, equals: .reply)
                        }
                        Button("Clear") { model.dismissNotification(notification.key) }
                            .buttonStyle(WPill(prominent: false, compact: true))
                            .help("Also clears it on your phone")
                            .focused($focus, equals: .clear)
                    }
                    .padding(.top, 6)
                }
            }
        }
        .padding(12)
        .contentShape(Rectangle())
        .rowHover()
        .onHover { hovering = $0 }
        // Keyboard: Tab to the row shows its buttons; Return replies.
        .focusable()
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
        draft = ""
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
                Image(systemName: "tray").font(.wh(22)).foregroundStyle(WTheme.secondary)
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
        .onTapGesture { if copyable { copyTrigger += 1 } }
        .modifier(FileDrag(url: files.first))
        // Keyboard: Tab to the row; Return copies.
        .focusable()
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

    /// Received files that arrived, for the menu and dragging.
    private var files: [URL] {
        if case .files(let batch) = item.payload, batch.finished { return batch.files }
        return []
    }

    @ViewBuilder private var leading: some View {
        switch item.payload {
        case .otp: CodeIconTile(size: 32, tint: WTheme.codeMint, wash: 0.22)
        case .text: icon("text.bubble.fill", theme.theme.pale)
        case .files(let batch):
            if batch.files.isEmpty { icon("arrow.down", batch.finished ? WTheme.danger : theme.theme.pale) } else { ThumbnailStack(urls: batch.files, size: 28) }
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
            if batch.interrupted { return "Transfer interrupted · \(time)" }
            if batch.failed > 0 { return "\(batch.failed) failed · \(time)" }
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
        case .files(let batch) where batch.finished && !batch.files.isEmpty:
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
        Image(systemName: name).font(.wh(13, .semibold))
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

private struct OnboardingPage: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Lync").font(.wh(12, .semibold)).tracking(0.1).foregroundStyle(WTheme.secondary)
                    Text("Connect your phone").font(.wh(24, .medium))
                }
                Spacer()
                Button { model.panelPage = .settings } label: { Image(systemName: "gearshape") }.buttonStyle(WRound(size: 34))
                    .help("Settings").accessibilityLabel("Settings")
            }
            .revealSection(0)
            // The numbered steps below already say how; the QR only adds its expiry.
            PairingQR(model: model, stepsShown: true).revealSection(1)
            VStack(alignment: .leading, spacing: 10) {
                step(1, "Install Lync on your Android phone")
                // Where to get it: a button, and a small QR to scan with the phone's camera.
                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Button {
                            NSWorkspace.shared.open(OnboardingPage.downloadURL)
                        } label: {
                            Label("Get the Android app", systemImage: "arrow.down.circle")
                        }
                        .buttonStyle(WPill(compact: true))
                        .labelStyle(.titleAndIcon)
                        .help(OnboardingPage.downloadURL.absoluteString)
                        Text("Or scan this with your phone's camera")
                            .font(.wh(11, .medium)).foregroundStyle(WTheme.secondary)
                    }
                    Spacer(minLength: 0)
                    if let qr = OnboardingPage.downloadQR {
                        Image(nsImage: qr).interpolation(.none).resizable()
                            .frame(width: 56, height: 56)
                            .padding(5)
                            .background(RoundedRectangle(cornerRadius: WRadius.small, style: .continuous).fill(Color.white))
                            .accessibilityLabel("QR code to download Lync for Android")
                    }
                }
                .padding(.leading, 34)
                step(2, "Join the same Wi‑Fi as this Mac")
                step(3, "Tap Pair with QR code and scan this")
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
            .revealSection(2)
            HStack {
                Text(model.displayMode == .notch && model.hasNotch
                     ? "Codes and files appear in the notch." : "Codes and files appear under the menu bar icon.")
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                Spacer(minLength: 8)
                Button("See how it looks") { model.previewCard() }
                    .buttonStyle(WPill(prominent: false, compact: true))
            }
            .revealSection(3)
        }
        .padding(20)
    }

    static let downloadURL = URL(string: "https://github.com/santhoshsunkara24/Lync/releases")!
    static let downloadQR = PairingQR.qr(downloadURL.absoluteString)

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(spacing: 10) {
            Text("\(n)").font(.wh(12, .semibold)).frame(width: 24, height: 24).background(Circle().fill(WTheme.chip))
            Text(text).font(.wh(13, .medium))
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
            Button(action: back) { Image(systemName: "chevron.left") }.buttonStyle(WRound(size: 34))
                .help("Back").accessibilityLabel("Back")
            Text(title).font(.wh(22, .medium))
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
                            Label(mode.label, systemImage: mode == .notch ? "laptopcomputer" : "menubar.rectangle")
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
                        if let note = model.loginItemNote {
                            HStack(alignment: .top, spacing: 8) {
                                Text(note).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 4)
                                if model.loginItemNeedsApproval {
                                    Button("Open") { model.openLoginItemsSettings() }
                                        .buttonStyle(WPill(prominent: false, compact: true))
                                        .help("Open System Settings › General › Login Items")
                                }
                            }
                            .padding(.horizontal, 14).padding(.bottom, 12)
                        }
                        divider
                    }
                    row("Open Lync") {
                        Text(HotKey.display).font(.wh(13, .semibold)).foregroundStyle(WTheme.secondary)
                            .padding(.horizontal, 10).frame(height: 26)
                            .background(Capsule().fill(WTheme.chip))
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Open Lync: Option Command L")
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
            .animation(WMotion.animation(WMotion.smooth), value: model.pairedPhones.map(\.id))
        }
        .frame(maxHeight: 600)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func group<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.wh(14, .semibold))
            VStack(spacing: 0) { content() }
                .background(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous).fill(WTheme.surface))
                .clipShape(RoundedRectangle(cornerRadius: WRadius.group, style: .continuous))
        }
        .padding(.top, 22)
    }

    private var divider: some View {
        Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 14)
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
        row(title) { WSwitch(isOn: value, label: title) }
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
        if Self.scrolls { ScrollView(showsIndicators: false, content: content) } else { content() }
    }
}

enum PanelSnapshots {
    /// Set by the snapshot renderer (scripts/Snapshots).
    nonisolated(unsafe) static var active = false
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
    /// Bumped on each copy: the checkmark bounces once.
    @State private var bounces = 0

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
        Group {
            if copied && iconOnly {
                Button(action: copy) {
                    Label { Text("Copied") } icon: { check }
                }
                .buttonStyle(WPill(prominent: false, compact: true))
                .transition(WMotion.transition(.scale(scale: 0.8).combined(with: .opacity)))
            } else if iconOnly {
                Button(action: copy) { Image(systemName: "doc.on.doc") }.buttonStyle(style)
                    .transition(WMotion.transition(.scale(scale: 0.8).combined(with: .opacity)))
            } else {
                Button(action: copy) {
                    Label {
                        // The label crossfades; the icon morphs into a check and bounces.
                        Text(copied ? "Copied" : "Copy").contentTransition(.interpolate)
                    } icon: {
                        Image(systemName: copied ? "checkmark" : "doc.on.doc")
                            .contentTransition(.symbolEffect(.replace))
                            .symbolEffect(.bounce, value: bounces)
                    }
                }
                .buttonStyle(style)
            }
        }
        .help(copied ? "Copied" : label)
        .accessibilityLabel(copied ? "Copied" : label)
        .animation(WMotion.animation(WMotion.snappy), value: copied)
        .onChange(of: trigger) { _, _ in copy() }
    }

    private var check: some View {
        Image(systemName: "checkmark").symbolEffect(.bounce, value: bounces)
    }

    private func copy() {
        action()
        copied = true
        // A beat later, so the checkmark is on screen when it bounces.
        if !WMotion.reduceMotion { DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) { bounces += 1 } }
        resetToken += 1
        let token = resetToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            if token == resetToken { copied = false }
        }
    }
}
