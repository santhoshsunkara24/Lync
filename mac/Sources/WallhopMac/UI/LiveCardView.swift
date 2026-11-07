import SwiftUI

/// The content of a live moment. Always drawn on black so it blends with the notch.
struct LiveCardView: View {
    @EnvironmentObject var theme: ThemeStore
    @ObservedObject var model: AppModel
    let card: LiveCard

    var body: some View {
        Group {
            switch card {
            case .otp(let code, let sender):
                otp(code: code, sender: sender)
            case .text(let text):
                // "Copied" only when it really was; otherwise a Copy button.
                if model.copyTextAutomatically {
                    row(icon: "text.bubble.fill", title: "Ready to paste", subtitle: text, trailing: copiedBadge)
                } else {
                    row(icon: "text.bubble.fill", title: "From your phone", subtitle: text,
                        trailing: CopyButton(style: WPill(prominent: false, compact: true), label: "Copy text") { Clipboard.copy(text) })
                }
            case .receiving(let id):
                receiving(id)
            case .added(let id):
                added(id)
            case .saved(let id):
                saved(id)
            case .pairing(let id):
                pairing(id)
            case .quiet(let symbol, let text):
                row(icon: symbol, title: text, subtitle: nil, trailing: EmptyView())
            case .dropZone:
                dropZone
            case .sending(let id):
                sending(id)
            case .info(let symbol, let title, let subtitle):
                row(icon: symbol, title: title, subtitle: subtitle, trailing: EmptyView())
            case .problem(let problem):
                // Why, what to do, and the button that does it.
                row(icon: problem.symbol, title: problem.title, subtitle: problem.detail, tint: WTheme.danger,
                    trailing: problemButton(problem))
            case .call:
                if let call = model.call { CallCard(model: model, call: call) }
            case .notification(let key):
                if let notification = model.notification(key) {
                    NotificationCard(model: model, notification: notification)
                } else {
                    row(icon: "bell.slash", title: "Notification cleared", subtitle: nil, trailing: EmptyView())
                }
            }
        }
        .foregroundStyle(.white)
    }

    // MARK: - Cards

    private func otp(code: String, sender: String) -> some View {
        HStack(alignment: .top, spacing: WSpace.s) {
            CodeIconTile(size: 32, tint: theme.theme.codeAccent, wash: 0.16)
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                HStack(spacing: WSpace.xs) {
                    // The notch is on show to anyone looking at the screen (or a screen share), so the
                    // code itself stays hidden here: it's on the clipboard, and in the popover.
                    RollingCode(code: masked(code), font: .wh(26, .semibold).monospacedDigit(), tracking: 1)
                        .accessibilityLabel(model.codeOnClipboard == code ? "Code copied" : "Code hidden")
                    Spacer(minLength: WSpace.xs)
                    // Copy crossfades into "Copied" (and back once the code leaves the clipboard).
                    ZStack(alignment: .trailing) {
                        if model.codeOnClipboard == code {
                            copiedBadge.transition(.swap)
                        } else {
                            CopyButton(style: WPill(prominent: false, compact: true), label: "Copy code") { model.copyOTP(code) }
                                .transition(.swap)
                        }
                    }
                    .animation(WMotion.animation(WMotion.quick), value: model.codeOnClipboard == code)
                }
                Text(sender.isEmpty ? "Verification code" : sender)
                    .font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.6))
                    .lineLimit(1)
            }
        }
    }

    /// Files dragged near the notch: drop them here. Thumbnails of the photos when there are some.
    private var dropZone: some View {
        let drag = model.fileDrag
        return HStack(spacing: WSpace.s) {
            if let images = drag?.images, !images.isEmpty {
                ThumbnailStack(urls: images)
            } else {
                iconTile("arrow.up.doc.fill", tint: model.themeStore.theme.glow)
            }
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                Text("Drop to send to \(model.connectedPhone?.name ?? "your phone")")
                    .font(.wh(14, .semibold)).lineLimit(1)
                if let drag {
                    Text(drag.summary).font(.wh(12, .medium)).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                }
            }
            Spacer(minLength: WSpace.xs)
        }
        .accessibilityElement(children: .combine)
    }

    /// Files on their way to the phone: Liftoff, what's going, and how far along.
    private func sending(_ id: String) -> some View {
        let progress = model.outgoing?.id == id ? model.outgoing?.progress ?? 0 : 1
        let files = model.outgoingFiles
        return transferCard(
            liftoffDone: model.outgoingLanded == id,
            failed: model.outgoingFailed == id,
            urls: files,
            title: model.outgoingLanded == id ? "On your phone" : "Sending to \(model.connectedPhone?.name ?? "your phone")",
            name: Self.describe(files),
            progress: progress
        )
    }

    /// Files arriving from the phone: Liftoff, what's coming (thumbnails as each file lands), and how far along.
    private func receiving(_ id: String) -> some View {
        let batch = model.batch(id)
        let size = ByteCountFormatter.string(fromByteCount: batch?.totalBytes ?? 0, countStyle: .file)
        return transferCard(
            liftoffDone: batch?.finished == true && batch?.interrupted != true,
            failed: batch?.interrupted == true && (batch?.files.isEmpty ?? true),
            cancelled: batch?.cancelled == true,
            urls: batch?.files ?? [],
            title: batch?.finished == true ? "Copied · ⌘V to paste" : "From \(model.connectedPhone?.name ?? "your phone")",
            name: (batch?.files.isEmpty ?? true) ? "\(batch?.summary ?? "Files") · \(size)" : "\(Self.describe(batch?.files ?? [])) · \(size)",
            progress: batch?.progress ?? 0
        )
    }

    private func transferCard(liftoffDone: Bool, failed: Bool = false, cancelled: Bool = false,
                              urls: [URL], title: String, name: String, progress: Double) -> some View {
        HStack(spacing: WSpace.s) {
            LiftoffView(sending: !failed && !cancelled, done: liftoffDone, failed: failed, cancelled: cancelled)
                .frame(width: 84, height: 60)
                .background(RoundedRectangle(cornerRadius: WRadius.field, style: .continuous).fill(Color(red: 0.047, green: 0.067, blue: 0.059)))
            TransferThumb(urls: urls, size: 32)
            VStack(alignment: .leading, spacing: WSpace.xxs) {
                Text(title).font(.wh(14, .semibold)).lineLimit(1)
                Text(name).font(.wh(12, .medium).monospacedDigit()).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
                // A fine line in the code colour instead of a ring: quieter beside the link.
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.1))
                        // Full width, scaled from the leading edge: progress moves a transform, not layout.
                        Capsule().fill(LinearGradient(colors: [theme.theme.codeAccent.opacity(0.5), theme.theme.codeAccent], startPoint: .leading, endPoint: .trailing))
                            .scaleEffect(x: max(3 / max(geo.size.width, 3), min(progress, 1)), anchor: .leading)
                            .animation(WMotion.animation(WMotion.quick), value: progress)
                    }
                }
                .frame(height: 3)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title), \(name), \(Int(progress * 100)) percent")
    }

    /// "Goa trip.mp4", "3 photos", "Report.pdf and 1 more".
    private static func describe(_ urls: [URL]) -> String {
        guard let first = urls.first else { return "Files" }
        if urls.count == 1 { return first.lastPathComponent }
        if urls.allSatisfy({ $0.isImage }) { return "\(urls.count) photos" }
        return "\(first.lastPathComponent) and \(urls.count - 1) more"
    }

    @ViewBuilder private func problemButton(_ problem: Problem) -> some View {
        if let title = problem.actionTitle {
            Button(title) { model.runProblemAction(problem) }
                .buttonStyle(WPill(compact: true))
                .fixedSize()
        }
    }

    /// What the files card says: what came (or why it didn't), then what to do.
    private func addedText(_ batch: FileBatch?) -> (title: String, subtitle: String) {
        guard let batch else { return ("Files", "") }
        if batch.interrupted {
            let text = Problem.interrupted(arrived: batch.files.count, expected: max(batch.expected, batch.files.count))
            return (text.title, text.detail ?? "")
        }
        if batch.files.isEmpty {
            let text = Problem.receiveFailed(batch.failure)
            return (text.title, text.detail ?? "")
        }
        if batch.failed > 0 {
            // Partly: what arrived is ready, and why the rest didn't.
            let total = batch.files.count + batch.failed
            return (Problem.partlyArrived(arrived: batch.files.count, expected: total), Problem.receiveFailed(batch.failure).title)
        }
        return (batch.summary, "Copied · ⌘V to paste")
    }

    /// Files arrived: they're on the clipboard already, ready for ⌘V. Save keeps them in Downloads.
    private func added(_ id: String) -> some View {
        let batch = model.batch(id)
        let files = batch?.files ?? []
        let interrupted = batch?.interrupted == true || (batch?.failed ?? 0) > 0
        let text = addedText(batch)
        return HStack(alignment: .top, spacing: WSpace.s) {
            if files.isEmpty {
                iconTile("exclamationmark.triangle.fill", tint: WTheme.danger)
                    .modifier(ShakeOnAppear())
            } else {
                // The transfer finished: a check pops onto the files.
                ThumbnailStack(urls: files)
                    .overlay(alignment: .bottomTrailing) {
                        if !interrupted {
                            NudgeImage("checkmark")
                                .font(.system(size: 8, weight: .heavy))
                                .foregroundStyle(WTheme.ink)
                                .frame(width: 16, height: 16)
                                .background(Circle().fill(WTheme.success))
                                .overlay(Circle().strokeBorder(Color.black, lineWidth: 1.5))
                                .offset(x: 4, y: 4)
                                .modifier(PopIn(delay: 0.05))
                                .accessibilityHidden(true)
                        }
                    }
            }
            // Just what came ("3 photos"), or why not: with Copy and Save beside it there's no room for more.
            titleBlock(subtitleLines: files.isEmpty ? 2 : 1) {
                Text(text.title)
                    .font(.wh(14, .semibold))
                    .lineLimit(files.isEmpty ? 2 : 1)
                    .truncationMode(.middle)
            } subtitle: {
                Text(text.subtitle)
                    .font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.7))
            } trailing: {
                if !files.isEmpty {
                    // Icon only, so Save keeps its word.
                    CopyButton(iconOnly: true, style: WRound(size: 24), label: "Copy \(batch?.summary ?? "files")") { model.copyFiles(id) }
                    Button("Save") { model.saveFiles(id) }
                        .buttonStyle(WPill(compact: true))
                        .help("Save to Downloads › From Phone")
                }
            }
        }
    }

    private func saved(_ id: String) -> some View {
        let batch = model.batch(id)
        return HStack(alignment: .top, spacing: WSpace.s) {
            ThumbnailStack(urls: batch?.files ?? [])
            titleBlock {
                Text("Saved to Downloads").font(.wh(14, .semibold)).lineLimit(1)
            } subtitle: {
                Text("\(batch?.summary ?? "") · From Phone folder").font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.65))
            } trailing: {
                if !(batch?.files.isEmpty ?? true) {
                    CopyButton(iconOnly: true, style: WRound(size: 24), label: "Copy \(batch?.summary ?? "files")") { model.copyFiles(id) }
                }
                Button("Show") { model.revealFiles(id) }.buttonStyle(WPill(compact: true)).help("Show in Finder")
            }
        }
    }

    private func pairing(_ id: UUID) -> some View {
        VStack(alignment: .leading, spacing: WSpace.s) {
            HStack(spacing: WSpace.s) {
                iconTile("iphone.gen3", tint: .white)
                VStack(alignment: .leading, spacing: WSpace.xxs) {
                    Text("Pair with \(model.pairingRequest?.phoneName ?? "phone")?")
                        .font(.wh(14, .semibold))
                        .lineLimit(1)
                    Text("Check this code matches your phone")
                        .font(.wh(12, .medium))
                        .foregroundStyle(.white.opacity(0.6))
                }
            }
            // The code starts under the text; the buttons end at the card's edge.
            HStack(spacing: WSpace.xs) {
                RollingCode(code: model.pairingRequest?.code ?? "······", font: .wh(28, .semibold).monospacedDigit(),
                            tracking: 2, from: .top)
                    .id(model.pairingRequest?.code ?? "")
                Spacer(minLength: WSpace.xs)
                Button("Decline") { model.decidePairing(false) }
                    .buttonStyle(WPill(prominent: false, compact: true))
                    .keyboardShortcut(.cancelAction)
                Button("Pair") { model.decidePairing(true) }
                    .buttonStyle(WPill(compact: true))
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.leading, Self.textInset)
        }
    }

    // MARK: - Building blocks

    /// Where a card's text starts: past the icon tile and the gap after it.
    static let textInset = Self.tileSize + WSpace.s
    static let tileSize: CGFloat = 32

    private func row<Trailing: View>(icon: String, title: String, subtitle: String?, tint: Color = .white, trailing: Trailing) -> some View {
        // With a subtitle the icon sits at the top, beside the title; alone, the two centre on each other.
        HStack(alignment: subtitle == nil ? .center : .top, spacing: WSpace.s) {
            iconTile(icon, tint: tint)
            titleBlock(subtitleLines: 2) {
                Text(title)
                    .font(.wh(14, .semibold))
                    .lineLimit(1)
            } subtitle: {
                if let subtitle {
                    Text(subtitle)
                        .font(.wh(12, .medium))
                        .foregroundStyle(.white.opacity(0.6))
                }
            } trailing: {
                trailing
            }
        }
    }

    /// Title and subtitle in one text column. Buttons go on the title's row, level with it and
    /// against the card's edge, so a long title truncates rather than pushing them off.
    private func titleBlock<Title: View, Subtitle: View, Trailing: View>(
        subtitleLines: Int = 1,
        @ViewBuilder title: () -> Title,
        @ViewBuilder subtitle: () -> Subtitle,
        @ViewBuilder trailing: () -> Trailing
    ) -> some View {
        VStack(alignment: .leading, spacing: WSpace.xxs) {
            HStack(spacing: WSpace.xs) {
                title()
                Spacer(minLength: WSpace.xs)
                HStack(spacing: WSpace.xs) { trailing() }
                    .fixedSize()
            }
            subtitle()
                .lineLimit(subtitleLines)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder private func iconTile(_ name: String, tint: Color) -> some View {
        if name == LinkCatchView.symbol {
            // "Connected": Lync's link catches inside the tile.
            LinkCatchView(connected: true, playOnAppear: true)
                .frame(width: 24, height: 24)
                .frame(width: Self.tileSize, height: Self.tileSize)
                .background(Circle().fill(tint.opacity(0.16)))
        } else {
            plainIconTile(name, tint: tint)
        }
    }

    private func plainIconTile(_ name: String, tint: Color) -> some View {
        NudgeImage(name)
            .font(.wh(15, .semibold))
            .foregroundStyle(tint)
            .frame(width: Self.tileSize, height: Self.tileSize)
            .background(Circle().fill(tint.opacity(0.16)))
    }

    /// The same height as the compact Copy pill it replaces, so nothing moves.
    private var copiedBadge: some View {
        Label { Text("Copied") } icon: { NudgeImage("checkmark") }
            .font(.wh(11, .semibold))
            .labelStyle(.titleAndIcon)
            .padding(.horizontal, WSpace.xs)
            .frame(height: 24)
            .background(Capsule().fill(Color.white.opacity(0.12)))
    }
}

struct ProgressRing: View {
    let progress: Double

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.15), lineWidth: 3)
            Circle()
                .trim(from: 0, to: max(0.02, progress))
                .stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(WMotion.animation(WMotion.quick), value: progress)
        }
        .frame(width: 24, height: 24)
    }
}

/// A phone notification with Reply and Dismiss.
private struct NotificationCard: View {
    @ObservedObject var model: AppModel
    let notification: PhoneNotification
    @State private var draft = ""
    @FocusState private var focused: Bool

    private var replying: Bool { model.replyingTo == notification.key }
    private var otherActions: [NotificationAction] { notification.actions.filter { !$0.isReply } }

    var body: some View {
        VStack(alignment: .leading, spacing: WSpace.s) {
            HStack(alignment: .top, spacing: WSpace.s) {
                AppIcon(image: model.appIcons[notification.packageName], size: 32)
                VStack(alignment: .leading, spacing: WSpace.xxs) {
                    // The ✕ sits on the first row, level with it, so the text below can run the
                    // full width of the card.
                    HStack(spacing: WSpace.xs) {
                        HStack(spacing: WSpace.xxs) {
                            if !notification.appName.isEmpty {
                                Text(notification.appName)
                                Text("·")
                            }
                            // "now" for a fresh one rather than "0 sec".
                            Text(notification.time, format: .relative(presentation: .named))
                                .layoutPriority(1)
                        }
                        .font(.wh(11, .medium))
                        .foregroundStyle(.white.opacity(0.6))
                        .lineLimit(1)
                        Spacer(minLength: WSpace.xs)
                        // ✕ means the same on every card: put this card away. Clearing it on the phone
                        // is a separate, named choice (right-click, or Clear in the panel).
                        Button {
                            model.hideLive()
                        } label: {
                            IconImage("xmark")
                                .font(.wh(11, .bold))
                                .frame(width: 24, height: 24)
                                .background(Circle().fill(Color.white.opacity(0.12)))
                                // A little more to aim at than it shows.
                                .contentShape(Circle().inset(by: -WSpace.xxs))
                        }
                        .buttonStyle(WShapePress(shape: Circle()))
                        .help("Dismiss")
                        .accessibilityLabel("Dismiss")
                    }
                    if !notification.title.isEmpty, !model.hideNotificationText {
                        Text(notification.title)
                            .font(.wh(14, .semibold))
                            .lineLimit(1)
                    }
                    if model.hideNotificationText || !notification.text.isEmpty {
                        Text(model.hideNotificationText ? "New notification" : notification.text)
                            .font(.wh(13, .medium))
                            .foregroundStyle(.white.opacity(0.8))
                            .lineLimit(replying ? 2 : 3)
                    }
                }
            }
            .contextMenu {
                Button("Dismiss") { model.hideLive() }
                Button("Clear on phone") { model.dismissNotification(notification.key) }
            }

            // Reply and the phone's actions start under the text, not under the icon.
            Group {
                if replying {
                    replyField
                } else if notification.canReply || !otherActions.isEmpty {
                    HStack(spacing: WSpace.xs) {
                        ForEach(otherActions.prefix(2)) { action in
                            Button(action.title) { model.performAction(action, on: notification) }
                                .buttonStyle(WPill(prominent: false, compact: true))
                                .lineLimit(1)
                        }
                        if notification.canReply {
                            Button("Reply") { model.startReply(notification.key) }
                                .buttonStyle(WPill(compact: true))
                                .layoutPriority(1)
                        }
                    }
                }
            }
            .padding(.leading, LiveCardView.textInset)
        }
    }

    private var replyField: some View {
        HStack(spacing: WSpace.xs) {
            TextField("Reply to \(notification.title.isEmpty ? notification.appName : notification.title)…", text: $draft)
                .textFieldStyle(.plain)
                .font(.wh(13, .medium))
                .padding(.horizontal, WSpace.s)
                .frame(height: 24)
                .background(Capsule().fill(Color.white.opacity(0.12)))
                .focused($focused)
                .onSubmit(send)
                .onExitCommand { model.cancelReply() }
                .accessibilityLabel("Reply to \(notification.appName)")
            Button("Send", action: send)
                .buttonStyle(WPill(compact: true))
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .onAppear {
            // A reply that didn't get through comes back to try again.
            if draft.isEmpty { draft = model.draft(for: notification.key) }
            DispatchQueue.main.async { focused = true }
        }
    }

    private func send() {
        model.reply(to: notification.key, text: draft)
        draft = ""
    }
}

struct AppIcon: View {
    let image: NSImage?
    var size: CGFloat = 32

    var body: some View {
        Group {
            if let image {
                Image(nsImage: image).resizable().interpolation(.high)
            } else {
                NudgeImage("app.badge")
                    .font(.wh(size * 0.45))
                    .frame(width: size, height: size)
                    .background(RoundedRectangle(cornerRadius: size * 0.24).fill(Color.white.opacity(0.12)))
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous))
    }
}

/// Incoming, active or missed call. Audio always stays on the phone.
private struct CallCard: View {
    @ObservedObject var model: AppModel
    let call: PhoneCall

    var body: some View {
        // On a call it stays up until the call ends, so it shrinks to one short row: whatever is
        // under the notch stays visible.
        if call.state == .active, model.callProblem == nil { onCall } else { full }
    }

    private var onCall: some View {
        HStack(spacing: WSpace.xs) {
            ZStack {
                Circle().fill(WTheme.success.opacity(0.22))
                NudgeImage("phone.fill").font(.wh(10, .semibold)).foregroundStyle(WTheme.success)
            }
            .frame(width: 24, height: 24)
            // A long name truncates; "On phone" and End stay.
            Text(call.displayName).font(.wh(13, .semibold)).lineLimit(1)
            Text("On phone").font(.wh(12, .medium)).foregroundStyle(.white.opacity(0.6)).lineLimit(1).fixedSize()
            Spacer(minLength: WSpace.xs)
            Button { model.endCall() } label: {
                NudgeImage("phone.down.fill").font(.wh(11, .semibold)).foregroundStyle(WTheme.ink)
                    .frame(width: 40, height: 24)
                    .background(Capsule().fill(WTheme.danger))
            }
            .buttonStyle(WShapePress(shape: Capsule()))
            .help("End call")
            .accessibilityLabel("End call")
        }
        .accessibilityElement(children: .contain)
    }

    private var full: some View {
        VStack(alignment: .leading, spacing: WSpace.s) {
            header
            // A button that didn't work: the card stays and says what to do instead.
            if let problem = model.callProblem {
                Label { Text([problem.title, problem.detail].compactMap { $0 }.joined(separator: " · ")) } icon: { NudgeImage("exclamationmark.circle.fill") }
                    .labelStyle(.titleAndIcon)
                    .font(.wh(12, .medium))
                    .foregroundStyle(WTheme.danger)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, Self.textInset)
                    .transition(.opacity)
            }
            // Decline leads; audio always stays on the phone, so Answer says where. The buttons
            // start under the name, like the text.
            if call.state == .ringing {
                HStack(spacing: WSpace.xs) {
                    CallButton(title: "Decline", symbol: "phone.down.fill", fill: WTheme.danger) { model.declineCall() }
                        .keyboardShortcut(.cancelAction)
                    CallButton(title: "Answer on phone", symbol: "phone.fill", fill: WTheme.success) { model.answerCall() }
                        // Return answers, Esc declines, once the card has the keyboard (⌥⌘L).
                        .keyboardShortcut(.defaultAction)
                    Spacer(minLength: 0)
                }
                .padding(.leading, Self.textInset)
            } else if call.state == .active {
                HStack {
                    CallButton(title: "End call", symbol: "phone.down.fill", fill: WTheme.danger) { model.endCall() }
                    Spacer(minLength: 0)
                }
                .padding(.leading, Self.textInset)
            }
        }
    }

    private var header: some View {
        HStack(spacing: WSpace.s) {
            ZStack {
                Circle().fill(Color.white.opacity(0.14))
                Text(initials)
                    .font(.wh(15, .semibold))
            }
            .frame(width: Self.avatarSize, height: Self.avatarSize)

            VStack(alignment: .leading, spacing: WSpace.xxs) {
                Text(call.displayName)
                    .font(.wh(15, .semibold))
                    .lineLimit(1)
                HStack(spacing: WSpace.xs) {
                    Text(subtitle)
                    if let sim = call.sim {
                        Text(sim)
                            .padding(.horizontal, WSpace.xs)
                            .frame(height: 16)
                            .background(Capsule().fill(Color.white.opacity(0.14)))
                            .fixedSize()
                    }
                }
                .font(.wh(11, .medium))
                .foregroundStyle(.white.opacity(0.6))
                .lineLimit(1)
            }
            Spacer(minLength: WSpace.xs)
        }
    }

    private static let avatarSize: CGFloat = 40
    /// Where the name starts: past the avatar and the gap after it.
    private static let textInset = avatarSize + WSpace.s

    private var subtitle: String {
        switch call.state {
        case .ringing: return "Incoming call"
        case .active: return "On call · audio on your phone"
        case .ended: return "Call ended"
        case .missed: return "Missed call"
        }
    }

    private var initials: String {
        guard let name = call.name else { return "#" }
        let letters = name.split(separator: " ").prefix(2).compactMap { $0.first }
        return letters.isEmpty ? "#" : String(letters).uppercased()
    }
}

/// A call button: a light red or green fill with dark text and icon (white on these fills
/// would be under 3:1).
private struct CallButton: View {
    let title: String
    let symbol: String
    let fill: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label { Text(title) } icon: { NudgeImage(symbol) }
                .labelStyle(.titleAndIcon)
                .font(.wh(13, .semibold))
                .foregroundStyle(WTheme.ink)
                .padding(.horizontal, WSpace.m)
                .frame(height: 32)
                .background(Capsule().fill(fill))
        }
        .buttonStyle(WShapePress(shape: Capsule()))
        .help(title)
    }
}
