import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// The pairing QR: scan it with Wallhop on the phone and the two pair without any codes.
/// Each QR works once and for two minutes; a fresh one appears automatically.
struct PairingQR: View {
    @ObservedObject var model: AppModel
    @State private var image: NSImage?
    @State private var refresh = Timer.publish(every: 110, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 10) {
            Group {
                if let image {
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 180, height: 180)
                } else {
                    ProgressView().frame(width: 180, height: 180)
                }
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color.white))

            Text("Scan with Lync on your phone")
                .font(.system(size: 13, weight: .semibold))
            Text("Lync › Pair with QR. Same Wi‑Fi as this Mac.")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .onAppear(perform: makeCode)
        .onReceive(refresh) { _ in makeCode() }
        .onDisappear { model.server.cancelInvite() }
    }

    private func makeCode() {
        guard let url = model.server.makeInvite() else {
            // The listener isn't up yet; try again shortly.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { makeCode() }
            return
        }
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
