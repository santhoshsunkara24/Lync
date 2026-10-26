import SwiftUI

/// The content of a live moment. Always drawn on black so it blends with the notch.
struct LiveCardView: View {
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
        HStack(spacing: 14) {
            CodeIconTile(size: 34, tint: WTheme.codeMint, wash: 0.16)
            VStack(alignment: .leading, spacing: 2) {
                RollingCode(code: code, font: .wh(26, .semibold).monospacedDigit(), tracking: 1)
                Text(sender.isEmpty ? "Verification code" : sender)
                    .font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.6))
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            // Copy crossfades into "Copied" (and back once the code leaves the clipboard).
            ZStack(alignment: .trailing) {
                if model.codeOnClipboard == code {
                    copiedBadge.transition(.swap)
                } else {
                    CopyButton(style: WPill(prominent: false, compact: true), label: "Copy code \(spaced(code))") { model.copyOTP(code) }
                        .transition(.swap)
                }
            }
            .animation(WMotion.animation(WMotion.quick), value: model.codeOnClipboard == code)
        }
    }

    /// Files dragged near the notch: drop them here. Thumbnails of the photos when there are some.
    private var dropZone: some View {
        let drag = model.fileDrag
        return HStack(spacing: 14) {
            if let images = drag?.images, !images.isEmpty {
                ThumbnailStack(urls: images)
            } else {
                iconTile("arrow.up.doc.fill", tint: model.themeStore.theme.glow)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Drop to send to \(model.connectedPhone?.name ?? "your phone")")
                    .font(.wh(14, .semibold)).lineLimit(1)
                if let drag {
                    Text(drag.summary).font(.wh(12, .medium)).foregroundStyle(.white.opacity(0.7))
                }
            }
            Spacer(minLength: 8)
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
        HStack(spacing: 12) {
            LiftoffView(sending: !failed && !cancelled, done: liftoffDone, failed: failed, cancelled: cancelled)
                .frame(width: 84, height: 60)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(red: 0.047, green: 0.067, blue: 0.059)))
            TransferThumb(urls: urls, size: 34)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.wh(14, .semibold)).lineLimit(1)
                Text(name).font(.wh(12, .medium).monospacedDigit()).foregroundStyle(.white.opacity(0.65)).lineLimit(1)
                // A fine mint line instead of a ring: quieter beside the link.
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.1))
                        // Full width, scaled from the leading edge: progress moves a transform, not layout.
                        Capsule().fill(LinearGradient(colors: [Color(red: 0.075, green: 0.529, blue: 0.353), WTheme.codeMint], startPoint: .leading, endPoint: .trailing))
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
        return HStack(spacing: 14) {
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
            VStack(alignment: .leading, spacing: 2) {
                // Just what came ("3 photos"), or why not: with Copy and Save beside it there's no room for more.
                Text(text.title)
                    .font(.wh(14, .semibold))
                    .lineLimit(files.isEmpty ? 2 : 1)
                    .truncationMode(.middle)
                Text(text.subtitle)
                    .font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.7))
                    .lineLimit(files.isEmpty ? 2 : 1)
            }
            Spacer(minLength: 8)
            if !files.isEmpty {
                // Icon only, so Save keeps its word.
                CopyButton(iconOnly: true, style: WRound(size: 30), label: "Copy \(batch?.summary ?? "files")") { model.copyFiles(id) }
                Button("Save") { model.saveFiles(id) }
                    .buttonStyle(WPill(compact: true))
                    .help("Save to Downloads › From Phone")
            }
        }
    }

    private func saved(_ id: String) -> some View {
        let batch = model.batch(id)
        return HStack(spacing: 14) {
            ThumbnailStack(urls: batch?.files ?? [])
            VStack(alignment: .leading, spacing: 2) {
                Text("Saved to Downloads").font(.wh(14, .semibold))
                Text("\(batch?.summary ?? "") · From Phone folder").font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.65)).lineLimit(1)
            }
            Spacer(minLength: 8)
            if !(batch?.files.isEmpty ?? true) {
                CopyButton(iconOnly: true, style: WRound(size: 30), label: "Copy \(batch?.summary ?? "files")") { model.copyFiles(id) }
            }
            Button("Show") { model.revealFiles(id) }.buttonStyle(WPill(compact: true)).help("Show in Finder")
        }
    }

    private func pairing(_ id: UUID) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                iconTile("iphone.gen3", tint: .white)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Pair with \(model.pairingRequest?.phoneName ?? "phone")?")
                        .font(.wh(14, .semibold))
                        .lineLimit(1)
                    Text("Check this code matches your phone")
                        .font(.wh(12, .medium))
                        .foregroundStyle(.white.opacity(0.6))
                }
            }
            HStack {
                RollingCode(code: model.pairingRequest?.code ?? "······", font: .wh(28, .semibold).monospacedDigit(),
                            tracking: 2, from: .top)
                    .id(model.pairingRequest?.code ?? "")
                Spacer()
                Button("Decline") { model.decidePairing(false) }
                    .buttonStyle(WPill(prominent: false, compact: true))
                    .keyboardShortcut(.cancelAction)
                Button("Pair") { model.decidePairing(true) }
                    .buttonStyle(WPill(compact: true))
                    .keyboardShortcut(.defaultAction)
            }
        }
    }

    // MARK: - Building blocks

    private func row<Trailing: View>(icon: String, title: String, subtitle: String?, tint: Color = .white, trailing: Trailing) -> some View {
        HStack(spacing: 14) {
            iconTile(icon, tint: tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.wh(14, .semibold))
                    .lineLimit(1)
                if let subtitle {
                    Text(subtitle)
                        .font(.wh(12, .medium))
                        .foregroundStyle(.white.opacity(0.6))
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            trailing
        }
    }

    @ViewBuilder private func iconTile(_ name: String, tint: Color) -> some View {
        if name == LinkCatchView.symbol {
            // "Connected": Lync's link catches inside the tile.
            LinkCatchView(connected: true, playOnAppear: true)
                .frame(width: 26, height: 26)
                .frame(width: 34, height: 34)
                .background(Circle().fill(tint.opacity(0.16)))
        } else {
            plainIconTile(name, tint: tint)
        }
    }

    private func plainIconTile(_ name: String, tint: Color) -> some View {
        NudgeImage(name)
            .font(.wh(15, .semibold))
            .foregroundStyle(tint)
            .frame(width: 34, height: 34)
            .background(Circle().fill(tint.opacity(0.16)))
    }

    private var copiedBadge: some View {
        Label { Text("Copied") } icon: { NudgeImage("checkmark") }
            .font(.wh(11, .semibold))
            .labelStyle(.titleAndIcon)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
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
        .frame(width: 22, height: 22)
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
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                AppIcon(image: model.appIcons[notification.packageName], size: 34)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(notification.appName)
                        Text("·")
                        // "now" for a fresh one rather than "0 sec".
                        Text(notification.time, format: .relative(presentation: .named))
                    }
                    .font(.wh(11, .medium))
                    .foregroundStyle(.white.opacity(0.6))
                    .lineLimit(1)
                    if !notification.title.isEmpty, !model.hideNotificationText {
                        Text(notification.title)
                            .font(.wh(14, .semibold))
                            .lineLimit(1)
                    }
                    Text(model.hideNotificationText ? "New notification" : notification.text)
                        .font(.wh(13, .medium))
                        .foregroundStyle(.white.opacity(0.8))
                        .lineLimit(replying ? 2 : 3)
                }
                Spacer(minLength: 4)
                // ✕ means the same on every card: put this card away. Clearing it on the phone
                // is a separate, named choice (right-click, or Clear in the panel).
                Button {
                    model.hideLive()
                } label: {
                    Image(systemName: "xmark")
                        .font(.wh(11, .bold))
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(Color.white.opacity(0.12)))
                        .padding(2)
                        .contentShape(Circle())
                }
                .buttonStyle(WShapePress(shape: Circle()))
                .help("Dismiss")
                .accessibilityLabel("Dismiss")
            }
            .contextMenu {
                Button("Dismiss") { model.hideLive() }
                Button("Clear on phone") { model.dismissNotification(notification.key) }
            }

            if replying {
                HStack(spacing: 8) {
                    TextField("Reply to \(notification.title.isEmpty ? notification.appName : notification.title)…", text: $draft)
                        .textFieldStyle(.plain)
                        .font(.wh(13, .medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
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
            } else if notification.canReply || !otherActions.isEmpty {
                HStack(spacing: 8) {
                    Spacer()
                    ForEach(otherActions.prefix(2)) { action in
                        Button(action.title) { model.performAction(action, on: notification) }
                            .buttonStyle(WPill(prominent: false, compact: true))
                            .lineLimit(1)
                    }
                    if notification.canReply {
                        Button("Reply") { model.startReply(notification.key) }
                            .buttonStyle(WPill(compact: true))
                    }
                }
            }
        }
    }

    private func send() {
        model.reply(to: notification.key, text: draft)
        draft = ""
    }
}

struct AppIcon: View {
    let image: NSImage?
    var size: CGFloat = 34

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
        VStack(alignment: .leading, spacing: 12) {
            header
            // A button that didn't work: the card stays and says what to do instead.
            if let problem = model.callProblem {
                Label { Text([problem.title, problem.detail].compactMap { $0 }.joined(separator: " · ")) } icon: { NudgeImage("exclamationmark.circle.fill") }
                    .labelStyle(.titleAndIcon)
                    .font(.wh(12, .medium))
                    .foregroundStyle(WTheme.danger)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            // Decline leads; audio always stays on the phone, so Answer says where.
            if call.state == .ringing {
                HStack(spacing: 8) {
                    CallButton(title: "Decline", symbol: "phone.down.fill", fill: WTheme.danger) { model.declineCall() }
                        .keyboardShortcut(.cancelAction)
                    CallButton(title: "Answer on phone", symbol: "phone.fill", fill: WTheme.success) { model.answerCall() }
                        // Return answers, Esc declines, once the card has the keyboard (⌥⌘L).
                        .keyboardShortcut(.defaultAction)
                    Spacer(minLength: 0)
                }
            } else if call.state == .active {
                HStack {
                    CallButton(title: "End call", symbol: "phone.down.fill", fill: WTheme.danger) { model.endCall() }
                    Spacer(minLength: 0)
                }
            }
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(Color.white.opacity(0.14))
                Text(initials)
                    .font(.wh(15, .semibold))
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 2) {
                Text(call.displayName)
                    .font(.wh(15, .semibold))
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(subtitle)
                    if let sim = call.sim {
                        Text(sim)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(Color.white.opacity(0.14)))
                    }
                }
                .font(.wh(11, .medium))
                .foregroundStyle(.white.opacity(0.6))
                .lineLimit(1)
            }
            Spacer(minLength: 8)
        }
    }

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
                .padding(.horizontal, 14)
                .frame(height: 34)
                .background(Capsule().fill(fill))
        }
        .buttonStyle(WShapePress(shape: Capsule()))
        .help(title)
    }
}
