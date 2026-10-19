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
    @State private var image: NSImage?
    /// Nothing to put in a QR yet (no listener or no network address) after ~10 s of trying.
    @State private var failed = false
    @State private var visible = false
    @State private var attempts = 0
    @State private var refresh = Timer.publish(every: 110, on: .main, in: .common).autoconnect()
    /// Bumped for each new QR, so the old one turns into the new one.
    @State private var generation = 0
    /// This Mac's address, for typing on the phone instead of scanning.
    @State private var address: String?

    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                if let image, !failed {
                    // Fades and grows in when ready; "New code" turns the old one over into the new.
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 180, height: 180)
                        .id(generation)
                        .transition(WMotion.transition(.asymmetric(
                            insertion: .modifier(active: QRFlip(angle: generation > 1 ? -40 : 0, scale: 0.92, opacity: 0),
                                                 identity: QRFlip(angle: 0, scale: 1, opacity: 1)),
                            removal: .modifier(active: QRFlip(angle: 40, scale: 0.92, opacity: 0),
                                               identity: QRFlip(angle: 0, scale: 1, opacity: 1))
                        )))
                } else if failed {
                    VStack(spacing: 10) {
                        Image(systemName: "wifi.exclamationmark").font(.wh(26, .medium)).foregroundStyle(WTheme.ink.opacity(0.6))
                        Text("Can't find this Mac's network address — check Wi‑Fi")
                            .font(.wh(12, .medium)).foregroundStyle(WTheme.ink.opacity(0.75))
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 12)
                    .frame(width: 180, height: 180)
                } else {
                    ProgressView().controlSize(.small).frame(width: 180, height: 180)
                        .transition(.opacity)
                }
            }
            .animation(WMotion.animation(WMotion.smooth), value: generation)
            .animation(WMotion.animation(WMotion.smooth), value: failed)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: WRadius.field, style: .continuous).fill(Color.white))
            .accessibilityLabel(failed ? "No pairing code" : "Pairing QR code")

            Text("Scan with Lync on your phone")
                .font(.wh(13, .semibold))
            Text(failed ? "Join a Wi‑Fi network, then try again."
                 : stepsShown ? "Code expires in 2 minutes." : "Lync › Pair with QR code. Same Wi‑Fi as this Mac. Code expires in 2 minutes.")
                .font(.wh(11, .medium))
                .foregroundStyle(WTheme.secondary)
                .multilineTextAlignment(.center)
            if let address, !failed {
                Text("No camera? On the phone, tap \u{201C}Enter the Mac's address\u{201D} and type \(address)")
                    .font(.wh(11, .medium))
                    .foregroundStyle(WTheme.secondary)
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)
            }
            Button(failed ? "Try again" : "New code") { restart() }
                .buttonStyle(WPill(prominent: failed, compact: true))
                .help(failed ? "Look for a network address again" : "Make a new QR code; the old one stops working")
        }
        .frame(maxWidth: .infinity)
        .onAppear {
            visible = true
            restart()
        }
        .onReceive(refresh) { _ in if visible { makeCode() } }
        .onDisappear {
            visible = false
            model.server.cancelInvite()
        }
    }

    private func restart() {
        attempts = 0
        failed = false
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
