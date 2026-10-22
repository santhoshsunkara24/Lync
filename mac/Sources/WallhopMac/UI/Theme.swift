import AppKit
import CoreText
import SwiftUI

/// The Mac app's colours, from the same hue as the phone's Material You palette (the phone sends
/// its accent colours when it connects; Lync mint until then). Mirrors `HomeColors` on Android:
/// a `top` → `mid` → ink gradient, tinted `card`s, `pale` main buttons, a contrasting `peek`.
struct WTheme: Equatable {
    var top: Color
    var mid: Color
    var card: Color
    var pale: Color
    var onPale: Color
    var peek: Color
    var peekPale: Color
    var onPeekPale: Color

    static let ink = Color(red: 0.043, green: 0.043, blue: 0.047)
    static let surface = Color.white.opacity(0.07)
    static let chip = Color.white.opacity(0.13)
    static let secondary = Color.white.opacity(0.68)
    /// Times and other quiet labels: still ≥ 4.5:1 on the panel's darker surfaces.
    static let tertiary = Color.white.opacity(0.6)
    /// Destructive actions, failures and declining a call.
    static let danger = Color(red: 1, green: 0.42, blue: 0.38)
    /// Answering a call, codes.
    static let success = Color(red: 0.36, green: 0.84, blue: 0.5)

    /// One-time codes always use Lync's own colours, never the phone's accent (same as Android):
    /// Forest background, Mint digits and accents, white at 75 % for the rest.
    static let codeForest = Color(red: 0x0F / 255.0, green: 0x2E / 255.0, blue: 0x24 / 255.0)
    static let codeMint = Color(red: 0x7F / 255.0, green: 0xE3 / 255.0, blue: 0xB0 / 255.0)
    static let codeSecondary = Color.white.opacity(0.75)

    /// Before any phone has connected, the Mac uses Lync's own colours: deep mint, with rose as the
    /// contrasting accent. From the first connection on, it follows that phone's wallpaper.
    static let defaultSeed: UInt32 = 0xFF2FB57E
    static let defaultAccent: UInt32 = 0xFFE0386A

    init(seed: UInt32, accent: UInt32?) {
        let (hue, saturation, _) = Self.hsl(seed)
        // A monochrome phone palette (common on Nothing phones) stays monochrome, like on the phone.
        if saturation < 0.12 {
            top = Self.color(0, 0, 0.3); mid = Self.color(0, 0, 0.14); card = Self.color(0, 0, 0.12)
            pale = Self.color(0, 0, 0.92); onPale = Self.color(0, 0, 0.12)
            peek = Self.color(0, 0, 0.26); peekPale = Self.color(0, 0, 0.92); onPeekPale = Self.color(0, 0, 0.12)
            return
        }
        let sat = min(max(saturation, 0.45), 0.8)
        let other = accent.map { Self.hsl($0).0 } ?? (hue + 110).truncatingRemainder(dividingBy: 360)
        let p = Self.palette(hue: hue, other: other, sat: sat)
        top = Self.color(p.top); mid = Self.color(p.mid); card = Self.color(p.card)
        pale = Self.color(p.pale); onPale = Self.color(p.onPale)
        peek = Self.color(p.peek); peekPale = Self.color(p.peekPale); onPeekPale = Self.color(p.onPeekPale)
    }

    typealias RGB = (r: Double, g: Double, b: Double)

    /// The colours as sRGB. Surfaces that carry white text (`top`, `mid`, `card`, `peek`) are made
    /// dark enough for white to reach 4.5:1 whatever the hue: yellows and greens are much lighter
    /// than blues at the same HSL lightness, so lightness is chosen by WCAG luminance.
    static func palette(hue: Double, other: Double, sat: Double)
        -> (top: RGB, mid: RGB, card: RGB, pale: RGB, onPale: RGB, peek: RGB, peekPale: RGB, onPeekPale: RGB) {
        (top: forWhiteText(hue, sat * 0.85, 0.42),
         mid: forWhiteText(hue, sat * 0.7, 0.2),
         card: forWhiteText(hue, sat * 0.5, 0.12),
         pale: rgb(hue, 0.8, 0.92),
         onPale: rgb(hue, 0.6, 0.22),
         peek: forWhiteText(other, 0.62, 0.44),
         peekPale: rgb(other, 0.9, 0.9),
         onPeekPale: rgb(other, 0.6, 0.18))
    }

    /// `rgb(h, s, l)`, darkened until white text on it is at least 4.5:1.
    static func forWhiteText(_ h: Double, _ s: Double, _ l: Double) -> RGB {
        var lightness = l
        var value = rgb(h, s, lightness)
        while contrast((1, 1, 1), value) < 4.6, lightness > 0.02 {
            lightness -= 0.01
            value = rgb(h, s, lightness)
        }
        return value
    }

    /// WCAG relative luminance of an sRGB colour.
    static func luminance(_ c: RGB) -> Double {
        func channel(_ v: Double) -> Double { v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
    }

    /// WCAG contrast ratio between two sRGB colours.
    static func contrast(_ a: RGB, _ b: RGB) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// `fg` at `alpha` over `bg`.
    static func blend(_ fg: RGB, _ alpha: Double, over bg: RGB) -> RGB {
        (fg.r * alpha + bg.r * (1 - alpha), fg.g * alpha + bg.g * (1 - alpha), fg.b * alpha + bg.b * (1 - alpha))
    }

    /// A self check of the colour rules, run by the snapshot renderer (scripts/snapshots.sh):
    /// every hue and saturation keeps white text ≥ 4.5:1 on the coloured surfaces, and the
    /// code card's colours pass too. Returns the failures, empty when all is well.
    static func contrastProblems() -> [String] {
        var problems: [String] = []
        let white: RGB = (1, 1, 1)
        for hue in stride(from: 0.0, to: 360, by: 5) {
            for sat in [0.45, 0.6, 0.8] {
                let p = palette(hue: hue, other: (hue + 110).truncatingRemainder(dividingBy: 360), sat: sat)
                for (name, c) in [("top", p.top), ("mid", p.mid), ("card", p.card), ("peek", p.peek)] {
                    let ratio = contrast(white, c)
                    if ratio < 4.5 { problems.append("white on \(name) hue \(Int(hue)) sat \(sat): \(String(format: "%.2f", ratio))") }
                }
                let onPale = contrast(p.onPale, p.pale)
                if onPale < 4.5 { problems.append("onPale on pale hue \(Int(hue)): \(String(format: "%.2f", onPale))") }
            }
        }
        let forest: RGB = (0x0F / 255.0, 0x2E / 255.0, 0x24 / 255.0), mint: RGB = (0x7F / 255.0, 0xE3 / 255.0, 0xB0 / 255.0)
        let ink: RGB = (0.043, 0.043, 0.047)
        let checks: [(String, Double)] = [
            ("mint on forest", contrast(mint, forest)),
            ("white 75% on forest", contrast(blend(white, 0.75, over: forest), forest)),
            ("forest on mint", contrast(forest, mint)),
            ("tertiary (white 60%) on ink", contrast(blend(white, 0.6, over: ink), ink)),
            ("tertiary on surface", contrast(blend(white, 0.6, over: blend(white, 0.07, over: ink)), blend(white, 0.07, over: ink))),
        ]
        for (name, ratio) in checks where ratio < 4.5 { problems.append("\(name): \(String(format: "%.2f", ratio))") }
        return problems
    }

    /// The screen gradient: colour at the top fading to ink.
    var background: LinearGradient {
        LinearGradient(stops: [.init(color: top, location: 0), .init(color: mid, location: 0.38), .init(color: Self.ink, location: 0.75)],
                       startPoint: .top, endPoint: .bottom)
    }

    private static func hsl(_ argb: UInt32) -> (Double, Double, Double) {
        let r = Double((argb >> 16) & 0xFF) / 255, g = Double((argb >> 8) & 0xFF) / 255, b = Double(argb & 0xFF) / 255
        let maxV = max(r, g, b), minV = min(r, g, b), l = (maxV + minV) / 2
        guard maxV != minV else { return (0, 0, l) }
        let d = maxV - minV
        let s = l > 0.5 ? d / (2 - maxV - minV) : d / (maxV + minV)
        var h: Double
        switch maxV {
        case r: h = (g - b) / d + (g < b ? 6 : 0)
        case g: h = (b - r) / d + 2
        default: h = (r - g) / d + 4
        }
        h *= 60
        return (h, s, l)
    }

    private static func color(_ h: Double, _ s: Double, _ l: Double) -> Color {
        color(rgb(h, s, l))
    }

    private static func color(_ c: RGB) -> Color {
        Color(red: c.r, green: c.g, blue: c.b)
    }

    static func rgb(_ h: Double, _ s: Double, _ l: Double) -> RGB {
        let c = (1 - abs(2 * l - 1)) * s
        let x = c * (1 - abs((h / 60).truncatingRemainder(dividingBy: 2) - 1))
        let m = l - c / 2
        let (r, g, b): (Double, Double, Double)
        switch h {
        case ..<60: (r, g, b) = (c, x, 0)
        case ..<120: (r, g, b) = (x, c, 0)
        case ..<180: (r, g, b) = (0, c, x)
        case ..<240: (r, g, b) = (0, x, c)
        case ..<300: (r, g, b) = (x, 0, c)
        default: (r, g, b) = (c, 0, x)
        }
        return (r + m, g + m, b + m)
    }
}

/// Corner radii (DESIGN.md "Current direction"). Always with `style: .continuous`.
enum WRadius {
    /// Cards: the latest code, now playing, pairing.
    static let card: CGFloat = 28
    /// Grouped lists: notifications, Recent, settings groups.
    static let group: CGFloat = 24
    /// Text fields, the QR's white tile, artwork.
    static let field: CGFloat = 14
    /// Thumbnails and small tiles.
    static let small: CGFloat = 10
}

/// Motion tokens (DESIGN.md: calm motion, physical response). Every animation in the app uses one
/// of these springs, through `WMotion.animation(_:)` so Reduce Motion turns movement into a
/// short fade.
enum WMotion {
    /// A finger (or click) going down: immediate, a little give.
    static let press = Animation.spring(response: 0.22, dampingFraction: 0.7)
    /// Hover, toggles, small state changes, closing things.
    static let snappy = Animation.spring(response: 0.28, dampingFraction: 0.82)
    /// Layout: pages, lists, the panel opening.
    static let smooth = Animation.spring(response: 0.38, dampingFraction: 0.86)
    /// Things arriving: live cards, toasts, a check popping in.
    static let bouncy = Animation.spring(response: 0.42, dampingFraction: 0.68)
    /// What Reduce Motion uses instead of any of the above.
    static let reduced = Animation.easeOut(duration: 0.12)

    /// True while System Settings › Accessibility › Display › Reduce motion is on (kept current).
    static var reduceMotion: Bool { ReduceMotionObserver.shared.isOn }

    /// `animation`, or a short fade when Reduce Motion is on.
    static func animation(_ animation: Animation) -> Animation? {
        reduceMotion ? reduced : animation
    }

    /// `transition`, or a plain fade when Reduce Motion is on.
    static func transition(_ transition: AnyTransition) -> AnyTransition {
        reduceMotion ? .opacity : transition
    }
}

/// `withAnimation` with a motion token, respecting Reduce Motion.
@discardableResult
func withMotion<Result>(_ animation: Animation, _ body: () throws -> Result) rethrows -> Result {
    try withAnimation(WMotion.animation(animation), body)
}

/// Caches `accessibilityDisplayShouldReduceMotion` and follows changes to it. Root views observe
/// it, so switching Reduce Motion on or off takes effect at once, without a relaunch.
final class ReduceMotionObserver: ObservableObject {
    static let shared = ReduceMotionObserver()
    @Published private(set) var isOn: Bool
    private var observer: NSObjectProtocol?

    private init() {
        isOn = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        observer = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.isOn = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        }
    }
}

/// Figtree, the phone app's font (SIL OFL, bundled in Resources).
enum WFont {
    static func register() {
        guard let url = Bundle.main.url(forResource: "Figtree", withExtension: "ttf") else { return }
        CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
    }

    static func make(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .custom("Figtree", size: size).weight(weight)
    }
}

extension Font {
    /// Figtree at `size`; titles use `.medium`, labels `.semibold`, like the phone.
    static func wh(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font { WFont.make(size, weight) }
}

/// Pale pill (main action) or see-through pill (secondary), like the phone's buttons.
/// `destructive` makes a red pill for actions that can't be undone, like Forget.
struct WPill: ButtonStyle {
    var prominent = true
    var compact = false
    var destructive = false
    @EnvironmentObject var theme: ThemeStore

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.wh(compact ? 12 : 13, .semibold))
            .foregroundStyle(destructive ? WTheme.ink : prominent ? theme.theme.onPale : Color.white)
            .padding(.horizontal, compact ? 12 : 16)
            .frame(height: compact ? 30 : 36)
            .background(Capsule().fill(destructive ? WTheme.danger : prominent ? theme.theme.pale : WTheme.chip))
            .modifier(WInteractive(shape: Capsule(), pressed: configuration.isPressed))
    }
}

/// The pill on the code card: Mint with Forest text.
struct CodePill: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .labelStyle(.titleAndIcon)
            .font(.wh(13, .semibold))
            .foregroundStyle(WTheme.codeForest)
            .padding(.horizontal, 16).frame(height: 36)
            .background(Capsule().fill(WTheme.codeMint))
            .modifier(WInteractive(shape: Capsule(), pressed: configuration.isPressed))
    }
}

/// Round see-through icon button (settings, ring, close).
struct WRound: ButtonStyle {
    var size: CGFloat = 32
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(WTheme.chip))
            .modifier(WInteractive(shape: Circle(), pressed: configuration.isPressed))
    }
}

/// Holds the current theme and keeps the phone's last colours across launches.
final class ThemeStore: ObservableObject {
    @Published private(set) var theme: WTheme

    init() {
        let defaults = UserDefaults.standard
        if let seed = (defaults.object(forKey: "themeSeed") as? NSNumber)?.uint32Value {
            let accent = (defaults.object(forKey: "themeAccent") as? NSNumber)?.uint32Value
            theme = WTheme(seed: seed, accent: accent)
        } else {
            theme = WTheme(seed: WTheme.defaultSeed, accent: WTheme.defaultAccent)
        }
    }

    func update(seed: UInt32, accent: UInt32?) {
        let defaults = UserDefaults.standard
        defaults.set(NSNumber(value: seed), forKey: "themeSeed")
        if let accent { defaults.set(NSNumber(value: accent), forKey: "themeAccent") }
        let next = WTheme(seed: seed, accent: accent)
        if next != theme { theme = next }
    }
}
