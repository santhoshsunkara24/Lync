import AppKit
import CoreText
import SwiftUI

/// The Mac app's colours, from the same hue as the phone's Material You palette (the phone sends
/// its accent colours when it connects; violet until then). Mirrors `HomeColors` on Android:
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
    static let tertiary = Color.white.opacity(0.4)
    /// Destructive actions, failures and declining a call.
    static let danger = Color(red: 1, green: 0.42, blue: 0.38)
    /// Answering a call, codes.
    static let success = Color(red: 0.36, green: 0.84, blue: 0.5)

    static let defaultSeed: UInt32 = 0xFF4B2BC9

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
        top = Self.color(hue, sat * 0.85, 0.42)
        mid = Self.color(hue, sat * 0.7, 0.2)
        card = Self.color(hue, sat * 0.5, 0.12)
        pale = Self.color(hue, 0.8, 0.92)
        onPale = Self.color(hue, 0.6, 0.22)
        peek = Self.color(other, 0.62, 0.44)
        peekPale = Self.color(other, 0.9, 0.9)
        onPeekPale = Self.color(other, 0.6, 0.18)
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
        return Color(red: r + m, green: g + m, blue: b + m)
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

/// Caches `accessibilityDisplayShouldReduceMotion` and follows changes to it.
private final class ReduceMotionObserver {
    static let shared = ReduceMotionObserver()
    private(set) var isOn: Bool
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
        let seed = (defaults.object(forKey: "themeSeed") as? NSNumber)?.uint32Value ?? WTheme.defaultSeed
        let accent = (defaults.object(forKey: "themeAccent") as? NSNumber)?.uint32Value
        theme = WTheme(seed: seed, accent: accent)
    }

    func update(seed: UInt32, accent: UInt32?) {
        let defaults = UserDefaults.standard
        defaults.set(NSNumber(value: seed), forKey: "themeSeed")
        if let accent { defaults.set(NSNumber(value: accent), forKey: "themeAccent") }
        let next = WTheme(seed: seed, accent: accent)
        if next != theme { theme = next }
    }
}
