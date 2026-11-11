import AVFoundation
import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Liftoff: Lync's link taking off while files go across, the same as on the phone.
/// When a send starts it crouches, then flies on its thrust with speed lines streaming back past
/// it for as long as the send lasts. When the send lands it blasts off along its own axis and
/// drops back in, with a glint running along the tube; then, at rest, it stops drawing. Geometry is the app icon's, in a 256 box, turned 45°,
/// so "forward" is up and to the right.
struct LiftoffView: View {
    /// A send is under way.
    var sending: Bool
    /// The send has landed: the finale plays once.
    var done: Bool
    /// It failed: the link jolts and comes undone.
    var failed = false
    /// It was cancelled: the link powers down and fades.
    var cancelled = false
    /// Its colours: the phone's wallpaper's, like the rest of Lync (Lync mint until a phone connects).
    var palette: LinkPalette = .brand

    @State private var engine = LiftoffEngine()
    /// Nothing is moving: the timeline stops until the send's state changes.
    @State private var still = false

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 60, paused: WMotion.reduceMotion || still)) { context in
            Canvas { ctx, size in
                engine.palette = palette
                engine.step(now: context.date, sending: sending, done: done, failed: failed, cancelled: cancelled)
                engine.draw(ctx, size: size)
                if engine.isStill && !still { DispatchQueue.main.async { still = true } }
            }
        }
        .onChange(of: sending) { _, _ in still = false }
        .onChange(of: done) { _, _ in still = false }
        .onChange(of: failed) { _, _ in still = false }
        .onChange(of: cancelled) { _, _ in still = false }
        .accessibilityHidden(true)
    }
}

/// The animation's state between frames: which stage it's in and the speed lines on screen.
final class LiftoffEngine {
    private enum Stage { case idle, launch, send, done, fail, cancel }
    private struct Streak { var off: CGFloat; var d: CGFloat; let len: CGFloat; let w: CGFloat; let v: CGFloat; var life: CGFloat }

    private static let launchMs: Double = 260, doneMs: Double = 1150
    /// Landed and at rest with no speed lines left: nothing to draw until the next send.
    var isStill: Bool {
        lines.isEmpty && (stage == .idle || (stage == .fail && tp > 900) || (stage == .cancel && tp > 600))
    }
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
    /// The colours it draws with (see `LinkPalette`).
    var palette: LinkPalette = .brand
    private static let edgeStroke = round(19), tubeStroke = round(16), highlightStroke = round(3)
    private static func round(_ w: CGFloat) -> StrokeStyle { StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round) }
    private static let glintDash: [CGFloat] = [24, 600]


    private var stage = Stage.idle
    private var tp: Double = 0
    private var vt: Double = 0
    private var last: Date?
    private var dt: Double = 16
    private var acc: CGFloat = 0
    private var lines: [Streak] = []
    /// The lane the next speed line takes (see `lanes`).
    private var nextLane = 0
    /// Speed lines run in five fixed lanes across the link, taken in this order, so two never
    /// sit side by side or cross: clean, even streaks rather than a scatter.
    private static let lanes: [CGFloat] = [0, -0.34, 0.17, -0.17, 0.34]
    /// How far behind the link a speed line goes before it has faded out (in link heights):
    /// inside the glow, clear of anything below.
    private static let reach: CGFloat = 1.1

    func step(now: Date, sending: Bool, done: Bool, failed: Bool = false, cancelled: Bool = false) {
        let d = last.map { min(50, now.timeIntervalSince($0) * 1000) } ?? 16
        last = now; dt = d; tp += d; vt += d
        if cancelled { if stage != .cancel { stage = .cancel; tp = 0 }; return }
        if failed { if stage != .fail { stage = .fail; tp = 0 }; return }
        switch stage {
        case .fail, .cancel: stage = .idle; tp = 0
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
        // How far the two hooks have come apart along the link, in points.
        var apart: CGFloat = 0
        var bright = stage == .send
        switch stage {
        case .idle: break
        case .launch:
            let t = tp / Self.launchMs
            back = -10 * CGFloat(Self.easeOut(t)); stretch = 1 - 0.07 * CGFloat(Self.easeOut(t))
        case .send:
            back = -10 + 16 * CGFloat(Self.easeOut(tp / 220)); stretch = 1.03
            hover = CGFloat(sin(vt / 70)) * 0.9
            rate = 9; speed = linkSize * 2.2
        case .fail:
            // A jolt, as if it hit something, then the hooks part and the light goes down.
            hover = CGFloat(sin(tp / 26) * 7 * exp(-tp / 160)) * (unit < 120 ? 0.5 : 1)
            back = CGFloat(-8 * Self.easeOut(tp / 90) * exp(-tp / 260))
            apart = linkSize * 0.07 * CGFloat(Self.easeOut((tp - 90) / 420))
            alpha = CGFloat(1 - 0.35 * Self.clamp(tp / 400))
            bright = false
        case .cancel:
            // The thrust cuts out: it sinks back the way it came, loosening as it fades.
            back = -unit * 0.16 * CGFloat(Self.easeIn(tp / 420))
            apart = linkSize * 0.05 * CGFloat(Self.easeOut(tp / 300))
            alpha = CGFloat(1 - Self.clamp((tp - 120) / 320))
        case .done:
            if tp < 420 {
                let k = CGFloat(Self.easeIn(tp / 420)); back = 6 + k * unit * 1.2; stretch = 1 + 0.4 * k; rate = 18; speed = linkSize * 3.6
            } else {
                let r = CGFloat(Self.spring((tp - 420) / 1000, 0.55, 0.32))
                back = -unit * 0.55 * (1 - r); alpha = CGFloat(Self.clamp((tp - 420) / 160)); glint = (tp - 560) / 520
            }
        }
        // Speed lines, emitted just behind the link and streaming back, one lane after another,
        // all the same width and speed so none overtakes or crosses another.
        acc += rate * CGFloat(dt) / 1000
        while acc >= 1 {
            acc -= 1
            let lane = Self.lanes[nextLane % Self.lanes.count]
            nextLane += 1
            lines.append(Streak(off: lane * linkSize, d: linkSize * 0.42,
                                len: linkSize * (0.22 + 0.1 * CGFloat(nextLane % 3) / 2), w: linkSize * 0.032,
                                v: speed, life: 1))
        }
        // Move the lines on and drop the spent ones in the same pass, in place (no new array):
        // gone once they've reached the edge of the glow.
        var kept = 0
        for i in lines.indices {
            var l = lines[i]
            l.d += l.v * CGFloat(dt) / 1000
            l.life = min(l.life, 1 - (l.d - linkSize * 0.42) / (linkSize * Self.reach))
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
            // In quickly, then fading the further it gets from the link.
            c.opacity = 0.7 * min(1, (1 - l.life) * 8) * l.life
            c.stroke(line, with: .color(palette.streak), style: StrokeStyle(lineWidth: l.w, lineCap: .round))
        }
        drawLink(ctx, x: bx, y: by, s: linkSize, stretch: stretch, alpha: alpha, glint: glint, sending: bright, apart: apart)
    }

    /// The link as a lit tube in the phone's colour, like the app icon, centred at (x, y) and `s` points tall.
    private func drawLink(_ ctx: GraphicsContext, x: CGFloat, y: CGFloat, s: CGFloat, stretch: CGFloat = 1, alpha: CGFloat = 1, glint: Double = -1, sending: Bool = false, apart: CGFloat = 0) {
        guard alpha > 0.002 else { return }
        var c = ctx
        c.opacity = alpha
        // A soft glow under it.
        let r = s * 0.75
        c.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)),
               with: .radialGradient(sending ? palette.glowSend : palette.glowRest,
                                     center: CGPoint(x: x, y: y), startRadius: 0, endRadius: r))
        let k = s / 256
        c.translateBy(x: x, y: y)
        if stretch != 1 {
            c.rotate(by: .degrees(-45)); c.scaleBy(x: stretch, y: 1 / sqrt(stretch)); c.rotate(by: .degrees(45))
        }
        c.scaleBy(x: k, y: k)
        c.translateBy(x: -128, y: -128)
        // The lower hook falls back, the upper one forward: the link opening along its length.
        let shift = apart / k
        var a = c; a.translateBy(x: -Self.u.x * shift, y: -Self.u.y * shift)
        var b = c; b.translateBy(x: Self.u.x * shift, y: Self.u.y * shift)
        hook(a, Self.pathA, style: palette.hookA, glint: glint)
        hook(b, Self.pathB, style: palette.hookB, glint: glint)
    }

    private func hook(_ ctx: GraphicsContext, _ path: Path, style: LinkPalette.Hook, glint: Double) {
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
    @EnvironmentObject var theme: ThemeStore
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
                                .padding(.horizontal, WSpace.xxs).padding(.vertical, 1)
                                .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
                                .padding(WSpace.xxs)
                        }
                    }
                    .task(id: url) { duration = await Self.length(of: url) }
            } else {
                RoundedRectangle(cornerRadius: size * 0.25, style: .continuous).fill(Color.white.opacity(0.08))
                    .overlay(NudgeImage("doc.fill").foregroundStyle(theme.theme.codeAccent))
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

/// Liftoff's colours, in the phone's wallpaper hue like the rest of Lync: the same recipe as the
/// phone's `LinkColors` (LinkColors.kt). `brand` is Lync's own mint, exactly as it always was.
struct LinkPalette: Equatable {
    /// One hook's colours: its edge, the lit tube (top to bottom) and the fine highlight.
    struct Hook: Equatable {
        let edge: Color
        let tube: Gradient
        let highlight: Color
    }
    let streak: Color
    let glowSend: Gradient
    let glowRest: Gradient
    let hookA: Hook
    let hookB: Hook

    private init(glow: Color, streak: Color, edgeA: Color, edgeB: Color, tubeA: [Color], tubeB: [Color]) {
        self.streak = streak
        glowSend = Gradient(colors: [glow.opacity(0.32 * 0.9), .clear])
        glowRest = Gradient(colors: [glow.opacity(0.32 * 0.6), .clear])
        hookA = Hook(edge: edgeA, tube: Gradient(colors: tubeA), highlight: .white.opacity(0.9))
        hookB = Hook(edge: edgeB, tube: Gradient(colors: tubeB), highlight: .white.opacity(0.6))
    }

    static let brand = LinkPalette(
        glow: Color(red: 0.373, green: 0.851, blue: 0.627),
        streak: Color(red: 0.886, green: 0.925, blue: 0.906),
        edgeA: Color(red: 0.624, green: 0.902, blue: 0.765),
        edgeB: Color(red: 0.498, green: 0.835, blue: 0.682),
        tubeA: [.white, Color(red: 0.910, green: 1, blue: 0.957), Color(red: 0.725, green: 0.933, blue: 0.827)],
        tubeB: [Color(red: 0.839, green: 0.984, blue: 0.914), Color(red: 0.561, green: 0.867, blue: 0.722)]
    )

    /// The recipe in `hue`; `vivid` 1 for a colourful wallpaper, 0 for a monochrome phone (greys).
    init(hue: Double, vivid: Double) {
        func c(_ s: Double, _ l: Double) -> Color {
            let v = WTheme.rgb(hue, min(1, max(0, s * vivid)), l)
            return Color(red: v.r, green: v.g, blue: v.b)
        }
        self.init(glow: c(0.62, 0.61), streak: c(0.15, 0.91), edgeA: c(0.58, 0.76), edgeB: c(0.52, 0.67),
                  tubeA: [.white, c(1, 0.955), c(0.61, 0.83)], tubeB: [c(0.84, 0.91), c(0.56, 0.71)])
    }
}
