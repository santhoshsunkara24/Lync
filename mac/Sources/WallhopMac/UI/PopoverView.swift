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

    var body: some View {
        VStack(spacing: 0) {
            if fromNotch { Color.clear.frame(height: max(model.notchHeight, 0)) }
            content
        }
        .frame(width: 380)
        .background(background)
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

    @ViewBuilder private var content: some View {
        switch model.panelPage {
        case .settings: SettingsPage(model: model)
        case .addPhone: AddPhonePage(model: model)
        case .home:
            if !model.isPaired && model.pairingRequest == nil {
                OnboardingPage(model: model)
            } else {
                HomePage(model: model)
            }
        }
    }
}

// MARK: - Home

private struct HomePage: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore

    var body: some View {
        MaybeScroll {
            VStack(alignment: .leading, spacing: 0) {
                header
                if model.pairingRequest != nil {
                    PairingCard(model: model).padding(.top, 16)
                }
                sendRow.padding(.top, 18)
                if let outgoing = model.outgoing {
                    ProgressView(value: outgoing.progress)
                        .progressViewStyle(.linear)
                        .tint(theme.theme.pale)
                        .padding(.top, 12)
                }
                if let code = model.latestCode {
                    LatestCodeCard(model: model, code: code).padding(.top, 18)
                }
                if let playing = model.nowPlaying {
                    NowPlayingCard(model: model, playing: playing).padding(.top, 14)
                }
                if !model.notifications.isEmpty {
                    SectionTitle("Notifications", action: ("Clear all", { model.dismissAllNotifications() })).padding(.top, 22)
                    Group {
                        VStack(spacing: 0) {
                            ForEach(Array(model.notifications.prefix(6).enumerated()), id: \.element.id) { index, notification in
                                if index > 0 { Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 56) }
                                NotificationRow(model: model, notification: notification)
                            }
                        }
                    }
                    .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(WTheme.surface))
                    .padding(.top, 10)
                }
                SectionTitle("Recent", action: model.activity.isEmpty ? nil : ("Clear", { model.clearActivity() })).padding(.top, 22)
                RecentList(model: model).padding(.top, 10)
            }
            .padding(20)
        }
        .frame(maxHeight: 600)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text(model.connectedPhone == nil ? "Your phone" : "Connected")
                    .font(.wh(12, .semibold)).tracking(0.1).foregroundStyle(WTheme.secondary)
                Text(model.connectedPhone?.name ?? model.pairedPhones.first?.name ?? "Your phone")
                    .font(.wh(24, .medium)).lineLimit(1)
                Text(statusLine).font(.wh(13, .medium)).foregroundStyle(WTheme.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            if model.connectedPhone != nil {
                Button { model.ringPhone() } label: {
                    Image(systemName: model.ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right")
                }
                .buttonStyle(WRound(size: 34))
                .help(model.ringing ? "Stop ringing" : "Ring your phone to find it")
            }
            Button { model.panelPage = .settings } label: { Image(systemName: "gearshape") }
                .buttonStyle(WRound(size: 34))
                .help("Settings")
        }
    }

    private var statusLine: String {
        if model.connectedPhone != nil {
            var parts = ["Over Wi‑Fi"]
            if let battery = model.battery { parts.insert("\(battery)%" + (model.charging ? " charging" : ""), at: 0) }
            return parts.joined(separator: " · ")
        }
        if !model.listening { return model.statusMessage }
        return "Waiting for your phone"
    }

    private var sendRow: some View {
        let connected = model.connectedPhone != nil
        return HStack(spacing: 8) {
            Button { model.sendClipboardToPhone() } label: { Label("Send clipboard", systemImage: "doc.on.clipboard") }
                .buttonStyle(WPill()).disabled(!connected).opacity(connected ? 1 : 0.45)
            Button { pickFiles() } label: { Label("Files", systemImage: "paperplane") }
                .buttonStyle(WPill(prominent: false)).disabled(!connected).opacity(connected ? 1 : 0.45)
            Button { model.openMessages() } label: { Label("Texts", systemImage: "message") }
                .buttonStyle(WPill(prominent: false))
        }
        .labelStyle(.titleAndIcon)
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send to Phone"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { model.sendFilesToPhone(panel.urls) }
    }
}

/// "Title" with an optional quiet action on the right.
private struct SectionTitle: View {
    let title: String
    var action: (String, () -> Void)?

    init(_ title: String, action: (String, () -> Void)? = nil) {
        self.title = title
        self.action = action
    }

    var body: some View {
        HStack {
            Text(title).font(.wh(18, .medium))
            Spacer()
            if let action {
                Button(action.0, action: action.1).buttonStyle(.plain).font(.wh(12, .semibold)).foregroundStyle(WTheme.secondary)
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
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Latest code").font(.wh(12, .semibold)).tracking(0.1).foregroundStyle(.white.opacity(0.8))
                Text(spaced(code.code)).font(.wh(30, .semibold).monospacedDigit()).tracking(2)
                Text((code.sender.isEmpty ? "Verification code" : code.sender) + " · on your clipboard")
                    .font(.wh(12, .medium)).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
            }
            Spacer(minLength: 0)
            CopyButton(style: PeekPill()) { model.copy(code.item) }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(theme.theme.peek))
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
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
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
                    Image(systemName: "music.note").font(.system(size: 18)).frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(WTheme.chip)
                }
            }
            .frame(width: 52, height: 52)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(playing.title.isEmpty ? "Unknown title" : playing.title).font(.wh(14, .semibold)).lineLimit(1)
                Text([playing.artist, playing.app].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            Button { model.media("previous") } label: { Image(systemName: "backward.fill") }.buttonStyle(WRound(size: 32))
            Button { model.media("toggle") } label: {
                Image(systemName: playing.playing ? "pause.fill" : "play.fill")
                    .font(.system(size: 15, weight: .semibold)).foregroundStyle(theme.theme.onPale)
                    .frame(width: 42, height: 42).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(theme.theme.pale))
            }
            .buttonStyle(.plain)
            Button { model.media("next") } label: { Image(systemName: "forward.fill") }.buttonStyle(WRound(size: 32))
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(theme.theme.card))
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

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AppIcon(image: model.appIcons[notification.packageName], size: 32)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(notification.title.isEmpty ? notification.appName : notification.title).font(.wh(13, .semibold)).lineLimit(1)
                    Spacer()
                    Text(notification.time, style: .time).font(.wh(11, .medium)).foregroundStyle(WTheme.tertiary)
                }
                Text(model.hideNotificationText ? "New notification" : notification.text)
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(2)
                if hovering {
                    HStack(spacing: 6) {
                        if notification.canReply {
                            Button("Reply") { model.startReply(notification.key) }.buttonStyle(WPill(compact: true))
                        }
                        Button("Clear") { model.dismissNotification(notification.key) }.buttonStyle(WPill(prominent: false, compact: true))
                    }
                    .padding(.top, 6)
                }
            }
        }
        .padding(12)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .contextMenu {
            if notification.canReply { Button("Reply") { model.startReply(notification.key) } }
            Button("Clear on phone") { model.dismissNotification(notification.key) }
            Button("Mute \(notification.appName)") { model.mute(notification) }
        }
    }
}

/// Codes, texts, files and missed calls, newest first.
private struct RecentList: View {
    @ObservedObject var model: AppModel

    var body: some View {
        if model.activity.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: "tray").font(.system(size: 22)).foregroundStyle(WTheme.secondary)
                    .frame(width: 56, height: 56).background(Circle().fill(WTheme.chip))
                Text("Codes, texts and files from your phone show up here, already on your clipboard.")
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 22).padding(.horizontal, 20)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(WTheme.surface))
        } else {
            VStack(spacing: 0) {
                ForEach(Array(model.activity.prefix(12).enumerated()), id: \.element.id) { index, item in
                    if index > 0 { Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 56) }
                    RecentRow(model: model, item: item)
                }
            }
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(WTheme.surface))
        }
    }
}

private struct RecentRow: View {
    @ObservedObject var model: AppModel
    let item: ActivityItem

    var body: some View {
        HStack(spacing: 12) {
            leading.frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                title
                Text(subtitle).font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            trailing
        }
        .padding(12)
        .contentShape(Rectangle())
        .contextMenu { Button("Remove") { model.remove(item) } }
    }

    @ViewBuilder private var leading: some View {
        switch item.payload {
        case .otp: icon("lock.fill", .green)
        case .text: icon("text.bubble.fill", .blue)
        case .files(let batch):
            if batch.files.isEmpty { icon("arrow.down", .orange) } else { ThumbnailStack(urls: batch.files, size: 28) }
        case .missedCall: icon("phone.arrow.down.left", .red)
        }
    }

    @ViewBuilder private var title: some View {
        switch item.payload {
        case .otp(let code, _, let expiresAt):
            Text(expiresAt > Date() ? spaced(code) : "••• •••").font(.wh(15, .semibold).monospacedDigit()).tracking(1)
        case .text(let text):
            // Not selectable: macOS scrolls selectable text to its end on click, cutting off the start.
            Text(text).font(.wh(13, .medium)).lineLimit(2).truncationMode(.tail)
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
            if batch.failed > 0 { return "\(batch.failed) failed · \(time)" }
            if !batch.finished { return "\(Int(batch.progress * 100))%" }
            return batch.saved ? "Saved to Downloads · \(time)" : "On your clipboard · \(time)"
        case .missedCall(_, let sim): return ["Missed call", sim, time].compactMap { $0 }.joined(separator: " · ")
        }
    }

    @ViewBuilder private var trailing: some View {
        switch item.payload {
        case .otp(_, _, let expiresAt) where expiresAt > Date():
            CopyButton(iconOnly: true, style: WRound(size: 30)) { model.copy(item) }
        case .text:
            CopyButton(iconOnly: true, style: WRound(size: 30)) { model.copy(item) }
        case .files(let batch) where batch.finished && !batch.files.isEmpty:
            HStack(spacing: 6) {
                CopyButton(iconOnly: true, style: WRound(size: 30)) { model.copyFiles(item.id) }
                if batch.saved {
                    Button("Show") { model.revealFiles(item.id) }.buttonStyle(WPill(prominent: false, compact: true))
                } else {
                    Button("Save") { model.saveFiles(item.id) }.buttonStyle(WPill(compact: true))
                        .help("Save to Downloads › From Phone")
                }
            }
        case .files(let batch) where !batch.finished:
            ProgressView(value: batch.progress).progressViewStyle(.circular).controlSize(.small)
        default:
            EmptyView()
        }
    }

    private func icon(_ name: String, _ tint: Color) -> some View {
        Image(systemName: name).font(.system(size: 13, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: 32, height: 32).background(Circle().fill(tint.opacity(0.22)))
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
            Text(spaced(model.pairingRequest?.code ?? "")).font(.wh(30, .semibold).monospacedDigit()).tracking(3)
            Text("Check your phone shows the same code.").font(.wh(12, .medium)).foregroundStyle(WTheme.secondary)
            HStack(spacing: 8) {
                Button("Pair") { model.decidePairing(true) }.buttonStyle(WPill()).keyboardShortcut(.defaultAction)
                Button("Decline") { model.decidePairing(false) }.buttonStyle(WPill(prominent: false))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(theme.theme.card))
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
            }
            PairingQR(model: model)
            VStack(alignment: .leading, spacing: 10) {
                step(1, "Install Lync on your Android phone")
                step(2, "Join the same Wi‑Fi as this Mac")
                step(3, "Tap Pair with QR code and scan this")
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(WTheme.surface))
        }
        .padding(20)
    }

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
                            Label(mode.label, systemImage: mode == .notch ? "rectangle.topthird.inset.filled" : "menubar.rectangle")
                                .labelStyle(.titleAndIcon)
                                .font(.wh(13, .semibold))
                                .foregroundStyle(on ? theme.theme.onPale : .white)
                                .frame(maxWidth: .infinity).frame(height: 40)
                                .background(Capsule().fill(on ? theme.theme.pale : WTheme.chip))
                        }
                        .buttonStyle(.plain)
                        .disabled(!available)
                        .opacity(available ? 1 : 0.4)
                    }
                }
                .padding(.top, 10)
                Text(model.hasNotch
                     ? (model.displayMode == .notch ? "Hover or click the notch to open Lync. Drop files on it to send them." : "Click the phone icon in the menu bar to open Lync.")
                     : "This Mac has no notch, so Lync lives in the menu bar.")
                    .font(.wh(12, .medium)).foregroundStyle(WTheme.secondary).padding(.top, 8)

                group("Clipboard and alerts") {
                    toggle("Copy received text automatically", $model.copyTextAutomatically)
                    toggle("Show phone notifications", $model.showNotifications)
                    toggle("Hide notification text", $model.hideNotificationText)
                    if model.canLaunchAtLogin {
                        toggle("Open at login", Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) }))
                    }
                }

                if !model.mutedApps.isEmpty {
                    group("Muted apps") {
                        ForEach(model.mutedApps.sorted(by: { $0.value < $1.value }), id: \.key) { pkg, name in
                            row(name) { Button("Unmute") { model.unmute(pkg) }.buttonStyle(WPill(prominent: false, compact: true)) }
                        }
                    }
                }

                group("Phones") {
                    ForEach(model.pairedPhones) { phone in
                        row(phone.name) {
                            Button("Forget") { model.forget(phone) }.buttonStyle(WPill(prominent: false, compact: true))
                        }
                    }
                    row("Add a phone") {
                        Button("Show code") { model.panelPage = .addPhone }.buttonStyle(WPill(compact: true))
                    }
                }

                HStack(spacing: 8) {
                    Button("Preview notch") { model.show(.otp(code: "482913", sender: "Preview"), for: 4) }
                        .buttonStyle(WPill(prominent: false))
                    Button("Received files") { model.openReceivedFolder() }.buttonStyle(WPill(prominent: false))
                    Spacer()
                    Button("Quit") { NSApp.terminate(nil) }.buttonStyle(WPill(prominent: false)).keyboardShortcut("q")
                }
                .padding(.top, 22)
            }
            .padding(20)
        }
        .frame(maxHeight: 600)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func group<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.wh(14, .semibold))
            VStack(spacing: 0) { content() }
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(WTheme.surface))
        }
        .padding(.top, 22)
    }

    private func toggle(_ title: String, _ value: Binding<Bool>) -> some View {
        row(title) { WSwitch(isOn: value) }
    }

    private func row<Trailing: View>(_ title: String, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack {
            Text(title).font(.wh(13, .medium)).lineLimit(1)
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
    }
}

/// Material 3 style switch, like the phone's: pale track and dark thumb when on.
struct WSwitch: View {
    @Binding var isOn: Bool
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
            .animation(.spring(response: 0.25, dampingFraction: 0.8), value: isOn)
        }
        .buttonStyle(.plain)
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
    let action: () -> Void
    @State private var copied: Bool
    @State private var resetToken = 0

    init(iconOnly: Bool = false, style: S, startCopied: Bool = false, action: @escaping () -> Void) {
        self.iconOnly = iconOnly
        self.style = style
        self.action = action
        _copied = State(initialValue: startCopied)
    }

    var body: some View {
        Group {
            if copied && iconOnly {
                Button(action: copy) { Label("Copied", systemImage: "checkmark") }
                    .buttonStyle(WPill(prominent: false, compact: true))
                    .transition(.scale(scale: 0.8).combined(with: .opacity))
            } else if iconOnly {
                Button(action: copy) { Image(systemName: "doc.on.doc") }.buttonStyle(style)
                    .transition(.scale(scale: 0.8).combined(with: .opacity))
            } else {
                Button(action: copy) {
                    Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                        .contentTransition(.symbolEffect(.replace))
                }
                .buttonStyle(style)
            }
        }
        .help(copied ? "Copied" : "Copy")
        .accessibilityLabel(copied ? "Copied" : "Copy")
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: copied)
    }

    private func copy() {
        action()
        copied = true
        resetToken += 1
        let token = resetToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            if token == resetToken { copied = false }
        }
    }
}
