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
                row(icon: "text.bubble.fill", title: "From your phone", subtitle: text, trailing: copiedBadge)
            case .receiving(let id):
                receiving(id)
            case .added(let id):
                added(id)
            case .saved(let id):
                saved(id)
            case .pairing(let id):
                pairing(id)
            case .connected(let name):
                row(icon: "iphone.radiowaves.left.and.right", title: name, subtitle: "Connected", trailing: EmptyView())
            case .sentToPhone(let message):
                row(icon: "arrow.up.right.circle.fill", title: message, subtitle: nil, trailing: EmptyView())
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
            iconTile("lock.fill", tint: WTheme.success)
            VStack(alignment: .leading, spacing: 2) {
                RollingCode(code: code, font: .wh(26, .semibold).monospacedDigit(), tracking: 1)
                Text(sender.isEmpty ? "Verification code" : sender)
                    .font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.6))
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            copiedBadge
        }
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
                Text(interrupted ? "Transfer interrupted" : files.isEmpty ? "Transfer failed" : "Copied to clipboard")
                    .font(.wh(14, .semibold))
                    .lineLimit(1)
                Text(files.isEmpty ? "Nothing arrived"
                     : interrupted ? "\(batch?.summary ?? "") arrived · copied"
                     : "\(batch?.summary ?? "") · paste anywhere")
                    .font(.wh(12, .medium))
                    .foregroundStyle(.white.opacity(0.65))
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if !files.isEmpty {
                if interrupted {
                    CopyButton(iconOnly: true, style: WRound(size: 30)) { model.copyFiles(id) }
                } else {
                    CopyButton(style: WPill(prominent: false, compact: true)) { model.copyFiles(id) }
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
                        Text(notification.time, style: .relative)
                    }
                    .font(.wh(11, .medium))
                    .foregroundStyle(.white.opacity(0.5))
                    .lineLimit(1)
                    if !notification.title.isEmpty {
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
                        .font(.wh(10, .bold))
                        .frame(width: 22, height: 22)
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
            if call.state == .ringing {
                CircleButton(symbol: "phone.down.fill", tint: WTheme.danger, help: "Decline") {
                    model.declineCall()
                }
                CircleButton(symbol: "phone.fill", tint: WTheme.success, help: "Answer on phone") {
                    model.answerCall()
                }
            } else if call.state == .active {
                CircleButton(symbol: "phone.down.fill", tint: WTheme.danger, help: "End call") {
                    model.endCall()
                }
            }
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

private struct CircleButton: View {
    let symbol: String
    let tint: Color
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.wh(15, .semibold))
                .foregroundStyle(.white)
                .frame(width: 38, height: 38)
                .background(Circle().fill(tint))
        }
        .buttonStyle(WShapePress(shape: Circle()))
        .help(help)
        .accessibilityLabel(help)
    }
}
