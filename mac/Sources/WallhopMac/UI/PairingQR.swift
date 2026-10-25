import Combine
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// The pairing QR: scan it with Lync on the phone and the two pair without any codes.
/// Each QR works once and for two minutes; a fresh one appears automatically while it's on screen.
struct PairingQR: View {
    @ObservedObject var model: AppModel
    /// The screen lists the steps itself, so the QR leaves out its own how-to line.
    var stepsShown = false
    /// Onboarding: only the QR, on a larger near-white tile; the page says the rest itself.
    /// A failure still says why and offers Try again.
    var bare = false
    @State private var image: NSImage?
    /// Nothing to put in a QR yet (no listener or no network address) after ~10 s of trying.
    @State private var failed = false
    @State private var visible = false
    @State private var attempts = 0
    @State private var refresh = Timer.publish(every: 110, on: .main, in: .common).autoconnect()
    /// Bumped for each new QR, so the old one turns into the new one.
    @State private var generation = 0
    /// The invite this QR shows, so leaving cancels only that one.
    @State private var inviteURL: URL?
    /// What to type on the phone instead, when its camera can't be used.
    @State private var address: String?

    private var side: CGFloat { bare ? 196 : 180 }

    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                if let image, !failed {
                    // Fades and grows in when ready; "New code" turns the old one over into the new.
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: side, height: side)
                        .id(generation)
                        .transition(WMotion.transition(.asymmetric(
                            insertion: .modifier(active: QRFlip(angle: generation > 1 ? -40 : 0, scale: 0.92, opacity: 0),
                                                 identity: QRFlip(angle: 0, scale: 1, opacity: 1)),
                            removal: .modifier(active: QRFlip(angle: 40, scale: 0.92, opacity: 0),
                                               identity: QRFlip(angle: 0, scale: 1, opacity: 1))
                        )))
                } else if failed {
                    // The real reason: offline, Local Network off, or the listener failing.
                    VStack(spacing: 10) {
                        NudgeImage(problem.symbol).font(.wh(26, .medium)).foregroundStyle(WTheme.ink.opacity(0.6))
                        Text(problem.title)
                            .font(.wh(12, .semibold)).foregroundStyle(WTheme.ink.opacity(0.75))
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 12)
                    .frame(width: side, height: side)
                } else {
                    ProgressView().controlSize(.small).environment(\.colorScheme, .light).frame(width: side, height: side)
                        .transition(.opacity)
                }
            }
            .animation(WMotion.animation(WMotion.smooth), value: generation)
            .animation(WMotion.animation(WMotion.smooth), value: failed)
            .padding(bare ? 16 : 10)
            .background(RoundedRectangle(cornerRadius: bare ? 22 : WRadius.field, style: .continuous)
                .fill(bare ? Color(white: 0.96) : Color.white))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(failed ? "No pairing code" : bare ? "Pairing code" : "Pairing QR code")

            if !bare {
                Text("Scan with Lync on your phone")
                    .font(.wh(13, .semibold))
            }
            if !bare || failed {
                Text(failed ? problem.detail ?? ""
                     : stepsShown ? "Code expires in 2 minutes." : "Lync › Pair with QR code. Same Wi‑Fi as this Mac. Code expires in 2 minutes.")
                    .font(.wh(11, .medium))
                    .foregroundStyle(WTheme.secondary)
                    .multilineTextAlignment(.center)
                if !failed, let address {
                    // The phone's "Enter address" when its camera can't be used.
                    Text("Or type \(address) on your phone")
                        .font(.wh(11, .medium))
                        .foregroundStyle(WTheme.secondary)
                        .textSelection(.enabled)
                }
                HStack(spacing: 8) {
                    if failed, let title = problem.actionTitle, let action = problem.action {
                        Button(title, action: action).buttonStyle(WPill(compact: true))
                    }
                    Button(failed ? "Try again" : "New code") { restart() }
                        .buttonStyle(WPill(prominent: failed && problem.action == nil, compact: true))
                        .help(failed ? "Look for a network address again" : "Make a new QR code; the old one stops working")
                }
            }
        }
        .frame(maxWidth: .infinity)
        .onAppear {
            visible = model.panelOpen
            if visible { restart() }
        }
        .onReceive(refresh) { _ in if visible { makeCode() } }
        // A scan of an old code was turned away: a fresh one, at once.
        .onChange(of: model.qrRefresh) { _, _ in if visible { restart() } }
        // A hidden panel doesn't remove its views, so stop offering a code while it's closed.
        .onChange(of: model.panelOpen) { _, open in
            if open { visible = true; restart() } else { stop() }
        }
        .onDisappear { stop() }
    }

    private func stop() {
        visible = false
        model.server.cancelInvite(inviteURL)
        inviteURL = nil
    }

    /// Why there's no code: Local Network off, this Mac offline, or the listener failing.
    private var problem: Problem {
        if model.listenerProblem == .localNetworkDenied { return .localNetworkOff }
        if !model.macOnline || model.listenerProblem == .offline || NetworkInfo.localIPv4s().isEmpty { return .macOffline }
        // Its own Try again (below) restarts the listener too.
        var failed = Problem.cantListen {}
        failed.actionTitle = nil
        failed.action = nil
        return failed
    }

    private func restart() {
        attempts = 0
        failed = false
        // The listener itself failed: start it again too.
        if model.listenFailed { model.retryListening() }
        makeCode()
    }

    private func makeCode() {
        guard visible else { return }
        guard let url = model.server.makeInvite() else {
            // The listener isn't up yet, or there's no network: try again for a few seconds.
            attempts += 1
            if attempts >= 10 {
                failed = true
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { makeCode() }
            return
        }
        failed = false
        inviteURL = url
        address = model.server.manualAddress()
        image = Self.qr(url.absoluteString)
        generation += 1
    }

    /// Part of a turn around the vertical axis, with a fade: the old QR flipping over into the new one.
    private struct QRFlip: ViewModifier {
        let angle: Double
        let scale: CGFloat
        let opacity: Double
        func body(content: Content) -> some View {
            content
                .rotation3DEffect(.degrees(angle), axis: (x: 0, y: 1, z: 0), perspective: 0.4)
                .scaleEffect(scale)
                .opacity(opacity)
        }
    }

    static func qr(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
