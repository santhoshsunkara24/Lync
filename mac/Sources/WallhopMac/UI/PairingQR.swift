import Combine
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// The pairing QR: scan it with Lync on the phone and the two pair without any codes.
/// Each QR works once and for two minutes; a fresh one appears automatically while it's on screen.
struct PairingQR: View {
    @ObservedObject var model: AppModel
    @State private var image: NSImage?
    /// Nothing to put in a QR yet (no listener or no network address) after ~10 s of trying.
    @State private var failed = false
    @State private var visible = false
    @State private var attempts = 0
    @State private var refresh = Timer.publish(every: 110, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 10) {
            Group {
                if let image, !failed {
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 180, height: 180)
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
                }
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: WRadius.field, style: .continuous).fill(Color.white))
            .accessibilityLabel(failed ? "No pairing code" : "Pairing QR code")

            Text("Scan with Lync on your phone")
                .font(.wh(13, .semibold))
            Text(failed ? "Join a Wi‑Fi network, then try again." : "Lync › Pair with QR. Same Wi‑Fi as this Mac. Code expires in 2 minutes.")
                .font(.wh(11, .medium))
                .foregroundStyle(WTheme.secondary)
                .multilineTextAlignment(.center)
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
        image = Self.qr(url.absoluteString)
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
