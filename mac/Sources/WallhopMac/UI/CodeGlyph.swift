import SwiftUI

/// Lync's icon for one-time codes: a pill with six dots in two groups of three ("••• •••"),
/// the shape of a hidden code. Drawn on a 256 grid; the same geometry as the Android icon.
struct CodeGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 256
        let ox = rect.midX - 128 * s, oy = rect.midY - 128 * s
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: ox + x * s, y: oy + y * s) }
        var path = Path()
        // The pill: 12…244 by 76…180, fully rounded ends.
        path.addRoundedRect(in: CGRect(origin: p(12, 76), size: CGSize(width: 232 * s, height: 104 * s)),
                            cornerSize: CGSize(width: 52 * s, height: 52 * s))
        // Six dots, radius 13, 34 apart, with a wider 42 gap between the two groups of three.
        for x: CGFloat in [39, 73, 107, 149, 183, 217] {
            path.addEllipse(in: CGRect(origin: p(x - 13, 115), size: CGSize(width: 26 * s, height: 26 * s)))
        }
        return path
    }
}

/// The code glyph in a round tinted tile, sized like the SF Symbol tiles next to it.
struct CodeIconTile: View {
    var size: CGFloat
    var tint: Color
    var wash: Double

    var body: some View {
        CodeGlyph()
            .fill(tint, style: FillStyle(eoFill: true))
            .frame(width: size * 0.56, height: size * 0.56)
            .frame(width: size, height: size)
            .background(Circle().fill(tint.opacity(wash)))
            .accessibilityHidden(true)
    }
}
