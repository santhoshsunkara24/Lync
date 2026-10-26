import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// The phone just scanned this Mac's QR: the code on screen breaks into mint dots that stream into
/// the two hooks, which clasp (LinkCatchView's spring), light up, a glint runs along one and a
/// ring of light goes round; then "You're linked." The same moment the phone plays, with the same
/// timeline, so both screens close the link together. Laid out like the onboarding QR step, so
/// the code starts exactly where it was.
struct QrLinkingView: View {
    let qrText: String
    let phoneName: String
    /// Snapshots: one still frame, this many seconds in.
    var frozenAt: Double? = nil
    var onFinished: () -> Void = {}

    @State private var start = Date()
    @State private var finished = false

    private static let tile: CGFloat = 292
    /// Points per link unit: the link a little smaller than the 340 pt canvas, as on the phone.
    private static let canvas: CGFloat = 340
    private static let k: CGFloat = canvas / 256 * 0.86

    var body: some View {
        let reduce = WMotion.reduceMotion
        TimelineView(.animation(minimumInterval: 1 / 60, paused: finished)) { context in
            let t = frozenAt ?? (reduce ? Self.clasp + Self.done : context.date.timeIntervalSince(start))
            let linked = t >= Self.clasp + 0.75
            VStack(spacing: 0) {
                ZStack {
                    Text("Scan with your phone.").opacity(linked ? 0 : 1 - Self.seg(t, 0.1, 0.5))
                    Text("You're linked.").opacity(Self.seg(t, Self.clasp + 0.75, Self.clasp + 1.1))
                }
                .font(.whTitle(28))
                .accessibilityAddTraits(.isHeader)
                Color.clear
                    .frame(width: Self.tile, height: Self.tile)
                    .overlay(
                        Canvas { ctx, size in Self.draw(ctx, size, geo: geo, t: t, reduce: reduce) }
                            .frame(width: Self.canvas, height: Self.canvas)
                    )
                    .padding(.top, 24)
                HStack(spacing: 8) {
                    Circle().fill(Self.mint).frame(width: 8, height: 8)
                    Text(phoneName)
                }
                .font(.wh(14)).foregroundStyle(.white.opacity(0.56))
                .opacity(Self.seg(t, Self.clasp + 0.9, Self.clasp + 1.25))
                .padding(.top, 20)
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.top, 44).padding(.bottom, 4).padding(20)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Linked with \(phoneName)")
        .onAppear {
            start = Date()
            guard frozenAt == nil else { return }
            let c = Self.clasp
            if reduce {
                Self.feel(.levelChange)
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { finish() }
                return
            }
            // A tap as the hooks meet; Glass as they light up.
            DispatchQueue.main.asyncAfter(deadline: .now() + c + Self.contact) { Self.feel(.levelChange) }
            DispatchQueue.main.asyncAfter(deadline: .now() + c + Self.lit) { NSSound(named: "Glass")?.play() }
            DispatchQueue.main.asyncAfter(deadline: .now() + c + Self.done) { finish() }
        }
    }

    /// Worked out once per code: the panel redraws this view whenever the model changes.
    @MainActor private static var cached: (text: String, geo: Geometry)?
    private var geo: Geometry {
        if let c = Self.cached, c.text == qrText { return c.geo }
        let g = Geometry(qrText, tile: Self.tile)
        Self.cached = (qrText, g)
        return g
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        onFinished()
    }

    private static func feel(_ pattern: NSHapticFeedbackManager.FeedbackPattern) {
        NSHapticFeedbackManager.defaultPerformer.perform(pattern, performanceTime: .now)
    }

    // Timeline, seconds; the phone's (QrLinking.kt). The Mac has already said yes, so the clasp
    // follows as soon as the links have formed.
    // Each move is a critically damped spring (no bounce, settles smoothly) and each step starts
    // while the last is still settling, so nothing parks between them.
    private static let morphFrom = 0.1, morphTo = 0.42, travelFrom = 0.34, travelSpread = 0.36, travel = 0.72
    private static let arrive = travelFrom + travelSpread + travel
    private static let fused = arrive + 0.28
    private static let clasp = fused + 0.05
    private static let windUp = 0.08, contact = 0.18, lit = 0.53, done = 1.95

    private static let mint = Color(red: 0x7F / 255.0, green: 0xE3 / 255.0, blue: 0xB0 / 255.0)
    private static let ring = (0x5F / 255.0, 0xD9 / 255.0, 0xA0 / 255.0)
    private static let mintRGB = (0x7F / 255.0, 0xE3 / 255.0, 0xB0 / 255.0)
    private static let apartA = (0x65 / 255.0, 0xA1 / 255.0, 0x84 / 255.0)
    private static let apartB = (0x4F / 255.0, 0x6B / 255.0, 0x60 / 255.0)
    private static let inkRGB = (0.04, 0.05, 0.05)
    private static func mix(_ a: (Double, Double, Double), _ b: (Double, Double, Double), _ p: Double, _ alpha: Double = 1) -> Color {
        Color(red: a.0 + (b.0 - a.0) * p, green: a.1 + (b.1 - a.1) * p, blue: a.2 + (b.2 - a.2) * p).opacity(alpha)
    }

    private static func seg(_ t: Double, _ a: Double, _ b: Double) -> Double { min(1, max(0, (t - a) / (b - a))) }
    private static func eo(_ x: Double) -> Double { 1 - pow(1 - x, 3) }
    private static func eio(_ x: Double) -> Double { x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2 }
    /// A critically damped spring from rest, `response` seconds: 0 → 1, no overshoot.
    private static func settle(_ x: Double, _ response: Double) -> Double {
        guard x > 0 else { return 0 }
        let w = 2 * Double.pi / response
        return 1 - (1 + w * x) * exp(-w * x)
    }
    /// LinkCatchView's spring (damping 0.58, about 11.4 rad/s): 0 → 1, a little past, settling.
    private static func springClose(_ x: Double) -> Double {
        if x <= 0 { return 0 }
        let z = 0.58, w = 2 * Double.pi / 0.55, wd = w * sqrt(1 - z * z)
        return 1 - exp(-z * w * x) * (cos(wd * x) + z * w / wd * sin(wd * x))
    }

    /// Link units: the hooks in a 256 box, turned -45° (as the icon), held apart along `u`.
    private static let apart: CGFloat = 46
    private static let u = CGPoint(x: sqrt(0.5), y: -sqrt(0.5))
    private static let center = CGPoint(x: 128, y: 128)

    struct Geometry {
        struct Dot { let sign: CGFloat; let x, y, cx, cy, nx, ny, bend: CGFloat; let delay: Double }
        let hookA: Path, hookB: Path
        let dots: [Dot]
        let spare: [CGPoint]
        let cell: CGFloat
        let tileSide: CGFloat

        init(_ text: String, tile: CGFloat) {
            // The hooks, LinkCatchView's, turned -45° about the centre.
            let r: CGFloat = 31, half: CGFloat = 62, long: CGFloat = 82, short: CGFloat = 15
            let turn = CGAffineTransform(translationX: 128, y: 128).rotated(by: -.pi / 4).translatedBy(x: -128, y: -128)
            // A point and its tangent along a hook, s units from its open end.
            func along(_ sign: CGFloat, _ s: CGFloat) -> (CGPoint, CGPoint) {
                let ax = 128 - sign * half
                if s <= long { return (CGPoint(x: ax + sign * (long - s), y: 128 - sign * r), CGPoint(x: -sign, y: 0)) }
                let arc = Double.pi * Double(r)
                if Double(s - long) <= arc {
                    let a = -Double.pi / 2 - Double(s - long) / Double(r)
                    let p = CGPoint(x: ax + sign * r * CGFloat(cos(a)), y: 128 + sign * r * CGFloat(sin(a)))
                    return (p, CGPoint(x: sign * CGFloat(sin(a)), y: -sign * CGFloat(cos(a))))
                }
                let e = s - long - CGFloat(arc)
                return (CGPoint(x: ax + sign * e, y: 128 + sign * r), CGPoint(x: sign, y: 0))
            }
            func hook(_ sign: CGFloat) -> Path {
                var p = Path(); let ax = 128 - sign * half
                p.move(to: CGPoint(x: ax + sign * long, y: 128 - sign * r)); p.addLine(to: CGPoint(x: ax, y: 128 - sign * r))
                p.addRelativeArc(center: CGPoint(x: ax, y: 128), radius: r, startAngle: .degrees(sign > 0 ? -90 : 90), delta: .degrees(-180))
                p.addLine(to: CGPoint(x: ax + sign * short, y: 128 + sign * r))
                return p.applying(turn)
            }
            hookA = hook(1); hookB = hook(-1)

            // The QR as drawn on screen: modules over the tile's 260 pt, in link units.
            let unit = 1 / QrLinkingView.k
            tileSide = tile * unit
            let modules = Self.modules(text)
            let n = max(1, modules.count)
            let qrSide = (tile - 32) * unit
            cell = qrSide / CGFloat(n)
            let origin = 128 - qrSide / 2
            var cells: [CGPoint] = []
            for j in 0..<modules.count { for i in 0..<modules[j].count where modules[j][i] {
                cells.append(CGPoint(x: origin + (CGFloat(i) + 0.5) * cell, y: origin + (CGFloat(j) + 0.5) * cell))
            } }

            // Places along each hook, four rows across its stroke.
            let total = long + .pi * r + short
            var targets: [(CGFloat, CGPoint)] = []
            for sign: CGFloat in [1, -1] {
                var s: CGFloat = 0
                while s <= total + 0.01 {
                    let (p, tan) = along(sign, min(s, total))
                    for lane: CGFloat in [-7.2, -2.4, 2.4, 7.2] {
                        targets.append((sign, CGPoint(x: p.x - tan.y * lane, y: p.y + tan.x * lane).applying(turn)))
                    }
                    s += 4.4
                }
            }
            // Squares and places matched in sweeping order: streams, not a scramble. Hook A
            // (sign 1 here) sits up-left, so it moves by -u, as on the phone.
            let key = { (p: CGPoint) in p.x + p.y * 0.35 }
            let pts = targets.sorted { key($0.1) < key($1.1) }
            let pool = cells.sorted { key($0) < key($1) }
            var used = Set<Int>()
            let maxD = hypot(qrSide / 2, qrSide / 2)
            let step = CGFloat(pool.count) / CGFloat(max(1, pts.count))
            var dots: [Dot] = []
            if !pool.isEmpty {
                for (idx, item) in pts.enumerated() {
                    let sign: CGFloat = item.0 > 0 ? -1 : 1
                    let i = min(pool.count - 1, Int(CGFloat(idx) * step)); used.insert(i)
                    let c = pool[i], p = item.1
                    let ex = p.x + sign * QrLinkingView.u.x * QrLinkingView.apart, ey = p.y + sign * QrLinkingView.u.y * QrLinkingView.apart
                    let dx = ex - c.x, dy = ey - c.y, d = max(0.001, hypot(dx, dy))
                    let wobble = (CGFloat((idx * 7919) % 100) / 100 - 0.5) * 15
                    dots.append(Dot(sign: sign, x: p.x, y: p.y, cx: c.x, cy: c.y, nx: -dy / d, ny: dx / d, bend: sign * 30 + wobble,
                                    delay: QrLinkingView.travelFrom + QrLinkingView.travelSpread * Double(hypot(c.x - 128, c.y - 128) / maxD)))
                }
            }
            self.dots = dots
            spare = pool.enumerated().filter { !used.contains($0.offset) }.map(\.element)
        }

        /// The QR's modules, quiet zone included, top row first: the same image PairingQR shows.
        static func modules(_ text: String) -> [[Bool]] {
            let filter = CIFilter.qrCodeGenerator()
            filter.message = Data(text.utf8)
            filter.correctionLevel = "L"
            guard let out = filter.outputImage,
                  let cg = CIContext().createCGImage(out, from: out.extent) else { return [] }
            let w = cg.width, h = cg.height
            var px = [UInt8](repeating: 255, count: w * h)
            let drawn: Bool = px.withUnsafeMutableBytes { buf in
                guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                          space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
                ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
                return true
            }
            guard drawn else { return [] }
            return (0..<h).map { j in (0..<w).map { i in px[j * w + i] < 128 } }
        }
    }

    private static func draw(_ ctx: GraphicsContext, _ size: CGSize, geo: Geometry, t: Double, reduce: Bool) {
        var c = ctx
        c.translateBy(x: size.width / 2 - 128 * k, y: size.height / 2 - 128 * k)
        c.scaleBy(x: k, y: k)
        // Glow behind the link, growing in as it forms.
        let glow = reduce ? 1 : eo(seg(t, arrive - 0.4, arrive + 0.8))
        let gr: CGFloat = 148
        c.fill(Path(ellipseIn: CGRect(x: 128 - gr, y: 128 - gr, width: gr * 2, height: gr * 2)), with: .radialGradient(
            Gradient(stops: [
                .init(color: mix(ring, ring, 0, 0.17 * (0.3 + 0.7 * glow)), location: 0),
                .init(color: mix(ring, ring, 0, 0.04), location: 0.55),
                .init(color: .clear, location: 1),
            ]), center: center, startRadius: 0, endRadius: gr))
        if !reduce && t < arrive { forming(c, geo, t) } else { links(c, geo, t, reduce) }
    }

    private static func forming(_ c: GraphicsContext, _ geo: Geometry, _ t: Double) {
        // The near-white tile the code sat on gives way to the dark, as the squares turn mint.
        let tileFade = 1 - eo(seg(t, morphFrom - 0.1, morphTo))
        if tileFade > 0 {
            let s = geo.tileSide
            c.fill(Path(roundedRect: CGRect(x: 128 - s / 2, y: 128 - s / 2, width: s, height: s), cornerRadius: 22 * s / tile, style: .continuous),
                   with: .color(Color(white: 0.96).opacity(tileFade)))
        }
        let morph = eio(seg(t, morphFrom, morphTo))
        let color = mix(inkRGB, mintRGB, morph)
        let side = geo.cell + (4.4 - geo.cell) * CGFloat(morph)
        let corner = 0.3 + (side / 2 - 0.3) * CGFloat(morph)
        func square(_ x: CGFloat, _ y: CGFloat, _ shade: GraphicsContext.Shading) {
            c.fill(Path(roundedRect: CGRect(x: x - side / 2, y: y - side / 2, width: side, height: side), cornerRadius: corner), with: shade)
        }
        // Squares that aren't needed fade as the rest round into dots.
        let fade = 1 - seg(t, morphFrom, morphTo + 0.1)
        if fade > 0 { for p in geo.spare { square(p.x, p.y, .color(mix(inkRGB, mintRGB, morph, fade))) } }
        for d in geo.dots {
            let p = CGFloat(settle(t - d.delay, travel * 0.7))
            let b = CGFloat(sin(Double.pi * Double(p))) * d.bend
            let ex = d.x + d.sign * u.x * apart, ey = d.y + d.sign * u.y * apart
            square(d.cx + (ex - d.cx) * p + d.nx * b, d.cy + (ey - d.cy) * p + d.ny * b, .color(color))
        }
    }

    private static func links(_ ctx: GraphicsContext, _ geo: Geometry, _ t: Double, _ reduce: Bool) {
        let fuse = reduce ? 1 : settle(t - arrive, 0.3)
        let uu = t - clasp
        let gap: CGFloat
        if reduce { gap = 0 }
        else if uu < 0 { gap = apart }
        else if uu < windUp { gap = apart + 8 * CGFloat(eo(seg(uu, 0, windUp))) }
        else { gap = (apart + 8) * CGFloat(1 - springClose(uu - windUp)) }
        let lit = reduce ? 1 : eo(seg(uu, contact, lit))
        let squash = reduce || uu < 0 ? 0 : max(0, 1 - abs(uu - contact) / 0.12)
        if fuse < 1 {
            let r = 2.2 + 3.5 * CGFloat(fuse)
            for d in geo.dots {
                let p = CGPoint(x: d.x + d.sign * u.x * apart, y: d.y + d.sign * u.y * apart)
                ctx.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)), with: .color(mix(mintRGB, mintRGB, 0, 0.9 * (1 - fuse))))
            }
        }
        var c = ctx
        c.translateBy(x: 128, y: 128); c.rotate(by: .degrees(-45))
        c.scaleBy(x: 1 + 0.05 * squash, y: 1 - 0.06 * squash)
        c.rotate(by: .degrees(45)); c.translateBy(x: -128, y: -128)
        let hookLength: CGFloat = 82 + .pi * 31 + 15
        for (path, sign) in [(geo.hookB, CGFloat(1)), (geo.hookA, CGFloat(-1))] {
            var h = c
            h.translateBy(x: sign * u.x * gap, y: sign * u.y * gap)
            h.stroke(path, with: .color(mix(sign > 0 ? apartB : apartA, mintRGB, lit, fuse)),
                     style: StrokeStyle(lineWidth: 21, lineCap: .round, lineJoin: .round))
            if sign < 0, !reduce {
                let gp = seg(uu, 0.28, 0.73)
                if gp > 0 && gp < 1 {
                    h.stroke(path, with: .color(.white.opacity(0.9 * sin(.pi * gp))),
                             style: StrokeStyle(lineWidth: 6, lineCap: .round, dash: [22, hookLength * 2],
                                                dashPhase: -CGFloat(gp) * (hookLength + 22) + 22))
                }
            }
        }
        // One ring of light round the link as it clasps.
        guard !reduce else { return }
        let rp = seg(uu, 0.2, 1.28)
        guard rp > 0 && rp < 1 else { return }
        let head = -90 + rp * 396
        for i in 0..<28 {
            let f = Double(i) / 27
            var arc = Path()
            arc.addRelativeArc(center: center, radius: 142, startAngle: .degrees(head - 140 * f - 5.7), delta: .degrees(5.7))
            ctx.stroke(arc, with: .color(mix(ring, ring, 0, 0.9 * sin(.pi * rp) * sin(.pi * f))), style: StrokeStyle(lineWidth: 3, lineCap: .round))
        }
    }
}
