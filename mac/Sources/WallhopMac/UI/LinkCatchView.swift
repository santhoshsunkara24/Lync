import AppKit
import SwiftUI

/// Lync's link (the app icon's two hooks) as a live connection state, the same as on the phone.
/// Searching: the hooks wait apart, still, the second one dim. Connecting: a small wind-up,
/// then they slide together on a playful spring and catch with a squash on the exact frame they
/// meet (with a trackpad tap, when one can be felt), the dim hook lights up and a glint runs along
/// both. Geometry is in a 256 box, like the icon. Only the catch animates: the rest of the time
/// the timeline is paused and nothing redraws.
struct LinkCatchView: View {
    /// Pass as a card's symbol to show the link catching instead of an SF Symbol.
    static let symbol = "lync.link"

    var connected: Bool
    /// Off while the panel or card isn't on screen: nothing animates then.
    var animate: Bool = true
    var shaded: Bool = true
    /// Play the catch when the view appears already connected (the "connected" note).
    var playOnAppear: Bool = false
    /// Tap the trackpad when the hooks meet. Off where another view (the "connected" note) already
    /// plays the catch, so one connection is felt once.
    var haptic: Bool = true

    @State private var catchStart: Date?

    private static let r: CGFloat = 31, sw: CGFloat = 19, half: CGFloat = 62, long: CGFloat = 82, short: CGFloat = 15, apart: CGFloat = 46
    private static let windUp = 0.08, springDuration = 0.55, bounce = 0.42
    /// When the hooks first meet: the spring has closed to 14 units.
    private static let contact: Double = {
        var t = 0.0
        while t < 2 { if Self.offset(at: t) <= 14 { return t }; t += 0.002 }
        return 0.24
    }()

    private static let hookA: Path = {
        var p = Path(); let ax = 128 - half
        p.move(to: CGPoint(x: ax + long, y: 128 - r)); p.addLine(to: CGPoint(x: ax, y: 128 - r))
        p.addRelativeArc(center: CGPoint(x: ax, y: 128), radius: r, startAngle: .degrees(-90), delta: .degrees(-180))
        p.addLine(to: CGPoint(x: ax + short, y: 128 + r)); return p
    }()
    private static let hookB: Path = {
        var p = Path(); let bx = 128 + half
        p.move(to: CGPoint(x: bx - long, y: 128 + r)); p.addLine(to: CGPoint(x: bx, y: 128 + r))
        p.addRelativeArc(center: CGPoint(x: bx, y: 128), radius: r, startAngle: .degrees(90), delta: .degrees(-180))
        p.addLine(to: CGPoint(x: bx - short, y: 128 - r)); return p
    }()
    private static let hookLength = long + .pi * r + short

    /// Hook offset from its resting place, in link units, t seconds into the catch.
    private static func offset(at t: Double) -> CGFloat {
        if t < 0 { return apart }
        if t < windUp { return apart + 8 * CGFloat(1 - pow(1 - t / windUp, 3)) }
        let s = t - windUp, z = 1 - bounce, w = 2 * Double.pi / springDuration, wd = w * sqrt(1 - z * z)
        let k = 1 - exp(-z * w * s) * (cos(wd * s) + (z * w / wd) * sin(wd * s))
        return (apart + 8) * CGFloat(1 - k)
    }

    var body: some View {
        // Waiting apart used to breathe by ~0.2 pt at header size: not worth 60 redraws a second.
        TimelineView(.animation(minimumInterval: 1 / 60, paused: !animate || WMotion.reduceMotion || catchStart == nil)) { context in
            Canvas { ctx, size in draw(ctx, size, now: context.date) }
        }
        .onChange(of: connected) { _, now in
            if now { startCatch() } else { catchStart = nil }
        }
        // Snapshots draw one frame: show the link caught, not the first frame of the catch.
        .onAppear { if playOnAppear && connected && !PanelSnapshots.active { startCatch() } }
        .accessibilityHidden(true)
    }

    private func startCatch() {
        // Nothing on screen, nothing to feel.
        guard animate else { catchStart = nil; return }
        guard !WMotion.reduceMotion else { catchStart = nil; tap(); return }
        let start = Date()
        catchStart = start
        // Only if this catch is still playing (not disconnected or replaced since).
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.contact) { if catchStart == start { tap() } }
        // Once it has settled, drop back to the still picture so nothing redraws.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.25) { if catchStart == start { catchStart = nil } }
    }

    private func tap() {
        guard haptic else { return }
        NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
    }

    private func draw(_ ctx: GraphicsContext, _ size: CGSize, now: Date) {
        let k = min(size.width, size.height) / 256
        var dx: CGFloat, lit: Double, squash: Double = 0, glint: Double = -1
        if !connected {
            dx = Self.apart; lit = 0
        } else if let start = catchStart {
            let u = now.timeIntervalSince(start)
            dx = Self.offset(at: u)
            let c = Self.contact
            let imp = (u - c) / 0.145
            if imp > 0 && imp < 1 { squash = sin(imp * .pi) }
            lit = max(0, min(1, (u - c) / 0.35))
            lit = 1 - pow(1 - lit, 3)
            glint = (u - c - 0.05) / 0.55
        } else {
            dx = 0; lit = 1
        }
        var c = ctx
        c.translateBy(x: (size.width - 256 * k) / 2, y: (size.height - 256 * k) / 2)
        c.scaleBy(x: k, y: k)
        c.translateBy(x: 128, y: 128); c.rotate(by: .degrees(-45))
        c.scaleBy(x: 1 + 0.05 * squash, y: 1 - 0.06 * squash)
        c.translateBy(x: -128, y: -128)
        hook(c, Self.hookB, dx: dx, soft: 1 - lit, alpha: 1, glint: glint)
        hook(c, Self.hookA, dx: -dx, soft: connected ? 0 : 0.55, alpha: 1, glint: glint)
    }

    // Lync mint, as in the app icon: a deep body, a lit top, a fine highlight. A hook that hasn't
    // caught yet is a muted grey-green, so lighting up reads as the moment of connection.
    private static let mintDeep = (0.075, 0.529, 0.353), mintBody = (0.184, 0.710, 0.494)
    private static let mintLight = (0.498, 0.890, 0.690), mintTop = (0.706, 0.961, 0.839)
    private static let muted = (0.310, 0.420, 0.376), mutedTop = (0.490, 0.604, 0.557)
    private static func mix(_ a: (Double, Double, Double), _ b: (Double, Double, Double), _ t: Double) -> Color {
        Color(red: a.0 + (b.0 - a.0) * t, green: a.1 + (b.1 - a.1) * t, blue: a.2 + (b.2 - a.2) * t)
    }

    private func hook(_ ctx: GraphicsContext, _ path: Path, dx: CGFloat, soft: Double, alpha: Double, glint: Double) {
        var c = ctx
        c.translateBy(x: dx, y: 0)
        c.opacity = alpha
        let round = { (w: CGFloat) in StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round) }
        guard shaded else {
            // Small sizes: one solid stroke, mint once caught, muted while waiting.
            c.stroke(path, with: .color(Self.mix(Self.mintLight, Self.muted, soft)), style: round(Self.sw + 2))
            return
        }
        var s = c; s.translateBy(x: 3, y: 5); s.addFilter(.blur(radius: 5))
        s.stroke(path, with: .color(.black.opacity(0.4)), style: round(Self.sw + 2))
        c.stroke(path, with: .color(Self.mix(Self.mintDeep, Self.muted, soft)), style: round(Self.sw + 1))
        var t = c; t.translateBy(x: -1, y: -1.5)
        t.stroke(path, with: .linearGradient(Gradient(stops: [
            .init(color: Self.mix(Self.mintTop, Self.mutedTop, soft), location: 0),
            .init(color: Self.mix(Self.mintLight, Self.muted, soft), location: 0.5),
            .init(color: Self.mix(Self.mintBody, Self.muted, soft), location: 1),
        ]), startPoint: CGPoint(x: 0, y: 128 - Self.r * 1.25), endPoint: CGPoint(x: 0, y: 128 + Self.r * 1.25)), style: round(Self.sw - 4))
        var h = c; h.translateBy(x: -3, y: -3.5)
        h.stroke(path, with: .color(.white.opacity(0.75 - 0.4 * soft)), style: round(2.5))
        if glint > 0 && glint < 1 {
            c.stroke(path, with: .color(.white.opacity(0.9 * sin(glint * .pi))),
                     style: StrokeStyle(lineWidth: 6, lineCap: .round, dash: [22, Self.hookLength * 2],
                                        dashPhase: -glint * (Self.hookLength + 22) + 22))
        }
    }
}
