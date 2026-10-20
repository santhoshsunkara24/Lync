import SwiftUI

/// The content of a live moment. Always drawn on black so it blends with the notch.
struct LiveCardView: View {
    @ObservedObject var model: AppModel
    @EnvironmentObject var theme: ThemeStore
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
            if model.codeOnClipboard == code {
                copiedBadge
            } else {
                CopyButton(style: WPill(prominent: false, compact: true), label: "Copy code \(spaced(code))") { model.copyOTP(code) }
            }
        }
    }

    /// Files dragged near the notch: drop them here. Thumbnails of the photos when there are some.
    private var dropZone: some View {
        let drag = model.fileDrag
        return HStack(spacing: 14) {
            if let images = drag?.images, !images.isEmpty {
                ThumbnailStack(urls: images)
            } else {
                iconTile("arrow.up.doc.fill", tint: WTheme.codeMint)
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

    private func sending(_ id: String) -> some View {
        let progress = model.outgoing?.id == id ? model.outgoing?.progress ?? 0 : 1
        return HStack(spacing: 14) {
            iconTile("arrow.up", tint: WTheme.codeMint)
            VStack(alignment: .leading, spacing: 2) {
                Text("Sending to \(model.connectedPhone?.name ?? "your phone")").font(.wh(14, .semibold)).lineLimit(1)
                Text("\(Int(progress * 100))%").font(.wh(12, .medium).monospacedDigit()).foregroundStyle(.white.opacity(0.7))
            }
            Spacer(minLength: 8)
            ProgressRing(progress: progress)
        }
        .accessibilityElement(children: .combine)
    }

    private func receiving(_ id: String) -> some View {
        let batch = model.batch(id)
        return HStack(spacing: 14) {
            if let files = batch?.files, !files.isEmpty {
                ThumbnailStack(urls: files)
            } else {
                iconTile("arrow.down", tint: theme.theme.pale)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Receiving \(batch?.summary ?? "files")")
                    .font(.wh(14, .semibold))
                Text(ByteCountFormatter.string(fromByteCount: batch?.receivedBytes ?? 0, countStyle: .file)
                     + " of " + ByteCountFormatter.string(fromByteCount: batch?.totalBytes ?? 0, countStyle: .file))
                    .font(.wh(12, .medium).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.6))
            }
            Spacer(minLength: 8)
            ProgressRing(progress: batch?.progress ?? 0)
        }
    }

    /// Files arrived: they're on the clipboard already. Save keeps them in Downloads.
    private func added(_ id: String) -> some View {
        let batch = model.batch(id)
        let files = batch?.files ?? []
        let interrupted = batch?.interrupted == true
        return HStack(spacing: 14) {
            if files.isEmpty {
                iconTile("exclamationmark.triangle.fill", tint: WTheme.danger)
                    .modifier(ShakeOnAppear())
            } else {
                // The transfer finished: a check pops onto the files.
                ThumbnailStack(urls: files)
                    .overlay(alignment: .bottomTrailing) {
                        if !interrupted {
                            Image(systemName: "checkmark")
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
                Text(interrupted ? "Transfer interrupted" : files.isEmpty ? "Transfer failed" : "\(batch?.summary ?? "Files") ready to paste")
                    .font(.wh(14, .semibold))
                    .lineLimit(1)
                Text(files.isEmpty ? "Nothing arrived"
                     : interrupted ? "\(batch?.summary ?? "") arrived · kept for a day"
                     : "Kept for a day — Save to keep")
                    .font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.7))
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if !files.isEmpty {
                if interrupted {
                    CopyButton(iconOnly: true, style: WRound(size: 30), label: "Copy \(batch?.summary ?? "files")") { model.copyFiles(id) }
                } else {
                    CopyButton(style: WPill(prominent: false, compact: true), label: "Copy \(batch?.summary ?? "files")") { model.copyFiles(id) }
                }
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

    private func row<Trailing: View>(icon: String, title: String, subtitle: String?, trailing: Trailing) -> some View {
        HStack(spacing: 14) {
            iconTile(icon, tint: .white)
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

    private func iconTile(_ name: String, tint: Color) -> some View {
        Image(systemName: name)
            .font(.wh(15, .semibold))
            .foregroundStyle(tint)
            .frame(width: 34, height: 34)
            .background(Circle().fill(tint.opacity(0.16)))
    }

    private var copiedBadge: some View {
        Label("Copied", systemImage: "checkmark")
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
                .animation(WMotion.animation(WMotion.smooth), value: progress)
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
                Button {
                    model.dismissNotification(notification.key)
                } label: {
                    Image(systemName: "xmark")
                        .font(.wh(11, .bold))
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(Color.white.opacity(0.12)))
                }
                .buttonStyle(WShapePress(shape: Circle()))
                .help("Clear on phone")
                .accessibilityLabel("Clear on phone")
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
                .onAppear { DispatchQueue.main.async { focused = true } }
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
                Image(systemName: "app.badge")
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
            // Decline leads; audio always stays on the phone, so Answer says where.
            if call.state == .ringing {
                HStack(spacing: 8) {
                    CallButton(title: "Decline", symbol: "phone.down.fill", fill: WTheme.danger) { model.declineCall() }
                        .keyboardShortcut(.cancelAction)
                    CallButton(title: "Answer on phone", symbol: "phone.fill", fill: WTheme.success) { model.answerCall() }
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
            Label(title, systemImage: symbol)
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
