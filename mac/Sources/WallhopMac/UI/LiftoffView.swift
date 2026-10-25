import AVFoundation
import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Liftoff: Lync's link taking off while files go across, the same as on the phone.
/// Waiting it hovers. When a send starts it crouches, then hovers on its thrust while speed lines
/// stream back past it. When the send lands it blasts off along its own axis and drops back in,
/// with a glint running along the tube. Geometry is the app icon's, in a 256 box, turned 45°,
/// so "forward" is up and to the right.
struct LiftoffView: View {
    /// A send is under way.
    var sending: Bool
    /// The send has landed: the finale plays once.
    var done: Bool

    @State private var engine = LiftoffEngine()

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 60, paused: WMotion.reduceMotion)) { context in
            Canvas { ctx, size in
                engine.step(now: context.date, sending: sending, done: done)
                engine.draw(ctx, size: size)
            }
        }
        .accessibilityHidden(true)
    }
}

/// The animation's state between frames: which stage it's in and the speed lines on screen.
final class LiftoffEngine {
    private enum Stage { case idle, launch, send, done }
    private struct Streak { var off: CGFloat; var d: CGFloat; let len: CGFloat; let w: CGFloat; let v: CGFloat; var life: CGFloat }

    private static let launchMs: Double = 260, doneMs: Double = 1150
    private static let pathA: Path = {
        var p = Path(); p.move(to: CGPoint(x: 120.22, y: 91.94)); p.addLine(to: CGPoint(x: 62.24, y: 149.92))
        p.addRelativeArc(center: CGPoint(x: 84.16, y: 171.84), radius: 31, startAngle: .degrees(-135), delta: .degrees(-180))
        p.addLine(to: CGPoint(x: 116.69, y: 183.15)); return p
    }()
    private static let pathB: Path = {
        var p = Path(); p.move(to: CGPoint(x: 135.78, y: 164.06)); p.addLine(to: CGPoint(x: 193.76, y: 106.08))
        p.addRelativeArc(center: CGPoint(x: 171.84, y: 84.16), radius: 31, startAngle: .degrees(45), delta: .degrees(-180))
        p.addLine(to: CGPoint(x: 139.31, y: 72.85)); return p
    }()
    private static let u = CGPoint(x: 0.7071, y: -0.7071)   // forward
    private static let p = CGPoint(x: 0.7071, y: 0.7071)    // across

    // Everything that doesn't change between frames, made once rather than 60 times a second.
    private static let streakColor = Color(red: 0.886, green: 0.925, blue: 0.906)
    private static let glowSend = glowGradient(0.9), glowRest = glowGradient(0.6)
    private static func glowGradient(_ glow: Double) -> Gradient {
        Gradient(colors: [Color(red: 0.373, green: 0.851, blue: 0.627).opacity(0.32 * glow), .clear])
    }
    private static let edgeStroke = round(19), tubeStroke = round(16), highlightStroke = round(3)
    private static func round(_ w: CGFloat) -> StrokeStyle { StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round) }
    private static let glintDash: [CGFloat] = [24, 600]

    /// One hook's colours: its edge, the lit tube (top to bottom) and the fine highlight.
    private struct HookStyle {
        let edge: Color
        let tube: Gradient
        let highlight: Color
        init(edge: (Double, Double, Double), tube: [(Double, Double, Double)], highlight: Double) {
            self.edge = Color(red: edge.0, green: edge.1, blue: edge.2)
            self.tube = Gradient(colors: tube.map { Color(red: $0.0, green: $0.1, blue: $0.2) })
            self.highlight = .white.opacity(highlight)
        }
    }
    private static let styleA = HookStyle(edge: (0.624, 0.902, 0.765), tube: [(1, 1, 1), (0.910, 1, 0.957), (0.725, 0.933, 0.827)], highlight: 0.9)
    private static let styleB = HookStyle(edge: (0.498, 0.835, 0.682), tube: [(0.839, 0.984, 0.914), (0.561, 0.867, 0.722)], highlight: 0.6)

    private var stage = Stage.idle
    private var tp: Double = 0
    private var vt: Double = 0
    private var last: Date?
    private var dt: Double = 16
    private var acc: CGFloat = 0
    private var lines: [Streak] = []

    func step(now: Date, sending: Bool, done: Bool) {
        let d = last.map { min(50, now.timeIntervalSince($0) * 1000) } ?? 16
        last = now; dt = d; tp += d; vt += d
        switch stage {
        case .launch where done, .send where done: stage = .done; tp = 0
        case .idle where sending && !done:
            stage = .launch; tp = 0
            NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now)
        case .launch where tp >= Self.launchMs: stage = .send; tp = 0
        case .done where tp >= Self.doneMs: stage = .idle; tp = 0
        default: break
        }
    }

    private static func clamp(_ v: Double) -> Double { min(1, max(0, v)) }
    private static func easeOut(_ t: Double) -> Double { 1 - pow(1 - clamp(t), 3) }
    private static func easeIn(_ t: Double) -> Double { pow(clamp(t), 2.4) }
    private static func spring(_ t: Double, _ dur: Double, _ bounce: Double) -> Double {
        guard t > 0 else { return 0 }
        let z = 1 - bounce, w = 2 * Double.pi / dur, wd = w * sqrt(1 - z * z)
        return 1 - exp(-z * w * t) * (cos(wd * t) + (z * w / wd) * sin(wd * t))
    }

    func draw(_ ctx: GraphicsContext, size: CGSize) {
        let unit = min(size.width, size.height)
        // In the notch's small tile the link takes a bigger share, so it still reads.
        let cx = size.width / 2, cy = size.height / 2, linkSize = unit * (unit < 120 ? 0.64 : 0.5)
        if WMotion.reduceMotion { drawLink(ctx, x: cx, y: cy, s: linkSize); return }
        var back: CGFloat = 0, stretch: CGFloat = 1, alpha: CGFloat = 1, glint: Double = -1, hover: CGFloat = 0
        var rate: CGFloat = 0, speed = unit * 2.4
        switch stage {
        case .idle: hover = CGFloat(sin(vt / 900)) * 2
        case .launch:
            let t = tp / Self.launchMs
            back = -10 * CGFloat(Self.easeOut(t)); stretch = 1 - 0.07 * CGFloat(Self.easeOut(t))
        case .send:
            back = -10 + 16 * CGFloat(Self.easeOut(tp / 220)); stretch = 1.03
            hover = CGFloat(sin(vt / 70)) * 0.9
            rate = 26; speed = unit * (2.2 + 1.2 * CGFloat(0.5 + 0.5 * sin(vt / 900)))
        case .done:
            if tp < 420 {
                let k = CGFloat(Self.easeIn(tp / 420)); back = 6 + k * unit * 1.2; stretch = 1 + 0.4 * k; rate = 40; speed = unit * 4
            } else {
                let r = CGFloat(Self.spring((tp - 420) / 1000, 0.55, 0.32))
                back = -unit * 0.55 * (1 - r); alpha = CGFloat(Self.clamp((tp - 420) / 160)); glint = (tp - 560) / 520
            }
        }
        // Speed lines, emitted just behind the link and streaming back.
        acc += rate * CGFloat(dt) / 1000
        while acc >= 1 {
            acc -= 1
            lines.append(Streak(off: CGFloat.random(in: -0.5...0.5) * linkSize * 0.9, d: linkSize * 0.42,
                                len: linkSize * CGFloat.random(in: 0.18...0.4), w: linkSize * CGFloat.random(in: 0.032...0.052),
                                v: speed * CGFloat.random(in: 0.8...1.2), life: 1))
        }
        // Move the lines on and drop the spent ones in the same pass, in place (no new array).
        var kept = 0
        for i in lines.indices {
            var l = lines[i]
            l.d += l.v * CGFloat(dt) / 1000; l.life -= CGFloat(dt) / 520
            if l.life > 0 { lines[kept] = l; kept += 1 }
        }
        lines.removeLast(lines.count - kept)
        let u = Self.u, p = Self.p
        let bx = cx + u.x * back + p.x * hover, by = cy + u.y * back + p.y * hover
        for l in lines {
            let x = bx - u.x * l.d + p.x * l.off, y = by - u.y * l.d + p.y * l.off
            var line = Path(); line.move(to: CGPoint(x: x, y: y)); line.addLine(to: CGPoint(x: x - u.x * l.len, y: y - u.y * l.len))
            // One stroke at the context's opacity looks the same as the colour at that opacity.
            var c = ctx
            c.opacity = 0.85 * min(1, l.life * 1.6)
            c.stroke(line, with: .color(Self.streakColor), style: StrokeStyle(lineWidth: l.w, lineCap: .round))
        }
        drawLink(ctx, x: bx, y: by, s: linkSize, stretch: stretch, alpha: alpha, glint: glint, sending: stage == .send)
    }

    /// The link as a lit mint tube, like the app icon, centred at (x, y) and `s` points tall.
    private func drawLink(_ ctx: GraphicsContext, x: CGFloat, y: CGFloat, s: CGFloat, stretch: CGFloat = 1, alpha: CGFloat = 1, glint: Double = -1, sending: Bool = false) {
        guard alpha > 0.002 else { return }
        var c = ctx
        c.opacity = alpha
        // A soft mint glow under it.
        let r = s * 0.75
        c.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)),
               with: .radialGradient(sending ? Self.glowSend : Self.glowRest,
                                     center: CGPoint(x: x, y: y), startRadius: 0, endRadius: r))
        let k = s / 256
        c.translateBy(x: x, y: y)
        if stretch != 1 {
            c.rotate(by: .degrees(-45)); c.scaleBy(x: stretch, y: 1 / sqrt(stretch)); c.rotate(by: .degrees(45))
        }
        c.scaleBy(x: k, y: k)
        c.translateBy(x: -128, y: -128)
        hook(c, Self.pathA, style: Self.styleA, glint: glint)
        hook(c, Self.pathB, style: Self.styleB, glint: glint)
    }

    private func hook(_ ctx: GraphicsContext, _ path: Path, style: HookStyle, glint: Double) {
        ctx.stroke(path, with: .color(style.edge), style: Self.edgeStroke)
        var t = ctx; t.translateBy(x: -1, y: -1)
        t.stroke(path, with: .linearGradient(style.tube, startPoint: CGPoint(x: 0, y: 60), endPoint: CGPoint(x: 0, y: 196)),
                 style: Self.tubeStroke)
        var h = ctx; h.translateBy(x: -3, y: -3)
        h.stroke(path, with: .color(style.highlight), style: Self.highlightStroke)
        if glint > 0 && glint < 1 {
            ctx.stroke(path, with: .color(.white.opacity(0.95 * sin(glint * .pi))),
                       style: StrokeStyle(lineWidth: 7, lineCap: .round, dash: Self.glintDash, dashPhase: 24 - glint * 230))
        }
    }
}

/// What's being sent, at a glance: Quick Look thumbnails (a fanned stack for several), with a play
/// mark and the length on a video.
struct TransferThumb: View {
    let urls: [URL]
    var size: CGFloat = 36

    @State private var duration: String?

    var body: some View {
        Group {
            if urls.count > 1 {
                ThumbnailStack(urls: urls, size: size - 4)
            } else if let url = urls.first {
                Thumbnail(url: url, size: size, corner: size * 0.25)
                    .overlay {
                        if isMovie(url) {
                            ZStack {
                                Circle().fill(.black.opacity(0.4)).frame(width: size * 0.42, height: size * 0.42)
                                NudgeImage("play.fill").font(.system(size: size * 0.18)).foregroundStyle(.white)
                            }
                        }
                    }
                    .overlay(alignment: .bottomTrailing) {
                        if let duration {
                            Text(duration).font(.wh(8, .semibold).monospacedDigit()).foregroundStyle(.white)
                                .padding(.horizontal, 3).padding(.vertical, 1)
                                .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 3))
                                .padding(3)
                        }
                    }
                    .task(id: url) { duration = await Self.length(of: url) }
            } else {
                RoundedRectangle(cornerRadius: size * 0.25, style: .continuous).fill(Color.white.opacity(0.08))
                    .overlay(NudgeImage("doc.fill").foregroundStyle(WTheme.codeMint))
                    .frame(width: size, height: size)
            }
        }
        .accessibilityHidden(true)
    }

    private func isMovie(_ url: URL) -> Bool {
        (UTType(filenameExtension: url.pathExtension)?.conforms(to: .movie)) == true
    }

    private static func length(of url: URL) async -> String? {
        guard (UTType(filenameExtension: url.pathExtension)?.conforms(to: .movie)) == true,
              let time = try? await AVURLAsset(url: url).load(.duration) else { return nil }
        let s = Int(CMTimeGetSeconds(time).rounded())
        guard s > 0 else { return nil }
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}
