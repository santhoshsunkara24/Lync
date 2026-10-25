import SwiftUI

// Small, reusable pieces of motion. All of them go through `WMotion`, so Reduce Motion turns
// movement into a short fade, and none of them animate while nothing is happening.

// MARK: - Buttons

/// Hover, press and keyboard focus for the app's buttons: brightens on hover (snappy), its icon
/// gives a small bounce as the pointer arrives (like Telegram's), sinks on press (press spring)
/// and comes back a little faster (release), and draws a ring in the button's shape when it has
/// keyboard focus.
struct WInteractive<S: Shape>: ViewModifier {
    let shape: S
    let pressed: Bool
    @State private var hovering = false
    @State private var nudges = 0
    @Environment(\.isEnabled) private var enabled
    @Environment(\.isFocused) private var focused

    func body(content: Content) -> some View {
        let hover = hovering && enabled && !pressed
        content
            .brightness(hover ? 0.06 : 0)
            .overlay(shape.stroke(Color.white.opacity(0.8), lineWidth: 2).padding(-3).opacity(focused ? 1 : 0))
            .scaleEffect(pressed ? 0.96 : 1)
            .opacity(pressed ? 0.85 : 1)
            .modifier(HoverNudge(count: nudges))
            .contentShape(shape)
            .onHover { inside in
                hovering = inside
                if inside && enabled { nudges += 1 }
            }
            .animation(WMotion.animation(pressed ? WMotion.press : WMotion.release), value: pressed)
            .animation(WMotion.animation(WMotion.snappy), value: hover)
            .animation(WMotion.animation(WMotion.snappy), value: focused)
    }
}

/// A button whose label draws its own shape (the play button, the display-mode chips).
struct WShapePress<S: Shape>: ButtonStyle {
    let shape: S
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.modifier(WInteractive(shape: shape, pressed: configuration.isPressed))
    }
}

/// A text-only button (Undo, Clear all): dims and sinks a little while pressed, its icon (if it
/// has one) bounces as the pointer arrives, and it shows a rounded ring while it has keyboard focus.
struct WPlainPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(HoverNudgeTracking())
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(Rectangle())
            .modifier(WFocusRing(shape: RoundedRectangle(cornerRadius: 6, style: .continuous), inset: -4))
            .animation(WMotion.animation(configuration.isPressed ? WMotion.press : WMotion.release),
                       value: configuration.isPressed)
    }
}

/// Hands "the pointer just arrived" down to the icons inside a button or row: each icon
/// (`NudgeImage`) plays its own small motion once when `count` goes up.
struct HoverNudge: ViewModifier {
    let count: Int

    func body(content: Content) -> some View {
        content.environment(\.hoverNudge, count)
    }
}

private struct HoverNudgeKey: EnvironmentKey { static let defaultValue = 0 }

extension EnvironmentValues {
    /// Goes up each time the pointer arrives on the button or row around this view.
    var hoverNudge: Int {
        get { self[HoverNudgeKey.self] }
        set { self[HoverNudgeKey.self] = newValue }
    }
}

/// An SF Symbol that moves very slightly when the pointer arrives on its button or row, after
/// Telegram's menu icons but calmer: one small movement out and a smooth return, no wobble. A
/// bell or a call tips from its top, arrows and send ease the way they point, a pencil or a
/// message tilts, refresh and settings turn a little, anything else grows a touch. The media
/// controls and the back button stay still. Once per arrival; only the glyph moves. Nothing moves
/// with Reduce Motion.
struct NudgeImage: View {
    let name: String
    @Environment(\.hoverNudge) private var nudge

    init(_ name: String) { self.name = name }

    var body: some View {
        let image = Image(systemName: name)
        let motion = IconMotion(symbol: name)
        if WMotion.reduceMotion || motion.isStill {
            image
        } else {
            image.keyframeAnimator(initialValue: IconPose(), trigger: nudge) { content, pose in
                content
                    .scaleEffect(pose.scale)
                    .rotationEffect(.degrees(pose.angle), anchor: motion.anchor)
                    .offset(x: pose.x, y: pose.y)
            } keyframes: { _ in
                KeyframeTrack(\.angle) { motion.angleKeys }
                KeyframeTrack(\.x) { motion.xKeys }
                KeyframeTrack(\.y) { motion.yKeys }
                KeyframeTrack(\.scale) { motion.scaleKeys }
            }
        }
    }
}

struct IconPose {
    var angle: Double = 0
    var x: CGFloat = 0
    var y: CGFloat = 0
    var scale: CGFloat = 1
}

/// Which motion an icon gets, from its symbol name. Every one is a single move out (0.16 s,
/// ease-out) and back (0.26 s, ease-in-out).
private enum IconMotion {
    case still, tip, slide(dx: CGFloat, dy: CGFloat), tilt, turn(Double), grow

    init(symbol: String) {
        let s = symbol
        if s.hasPrefix("play") || s.hasPrefix("pause") || s.hasPrefix("forward") || s.hasPrefix("backward") || s.hasPrefix("chevron.left") {
            self = .still
        }
        else if s.hasPrefix("bell") || s.hasPrefix("phone") || s.hasPrefix("music") { self = .tip }
        else if s == "arrow.clockwise" { self = .turn(40) }
        else if s.hasPrefix("gearshape") { self = .turn(30) }
        else if s.hasPrefix("paperplane") { self = .slide(dx: 1.5, dy: -1.5) }
        else if s.hasPrefix("arrow.up") { self = .slide(dx: 0, dy: -1.5) }
        else if s.hasPrefix("chevron.right") || s.contains("arrow.right") { self = .slide(dx: 1.5, dy: 0) }
        else if s.contains("pencil") || s.hasPrefix("message") || s.hasPrefix("doc") { self = .tilt }
        else { self = .grow }
    }

    var isStill: Bool { if case .still = self { return true }; return false }

    var anchor: UnitPoint {
        switch self {
        case .tip: return .top
        case .tilt: return .bottomLeading
        default: return .center
        }
    }

    @KeyframeTrackContentBuilder<Double> var angleKeys: some KeyframeTrackContent<Double> {
        switch self {
        case .tip:
            CubicKeyframe(-7, duration: 0.16)
            CubicKeyframe(0, duration: 0.26)
        case .tilt:
            CubicKeyframe(-5, duration: 0.16)
            CubicKeyframe(0, duration: 0.26)
        case .turn(let degrees):
            CubicKeyframe(degrees, duration: 0.18)
            CubicKeyframe(0, duration: 0.3)
        default:
            MoveKeyframe(0)
        }
    }

    @KeyframeTrackContentBuilder<CGFloat> var xKeys: some KeyframeTrackContent<CGFloat> {
        if case .slide(let dx, _) = self {
            CubicKeyframe(dx, duration: 0.16)
            CubicKeyframe(0, duration: 0.26)
        } else {
            MoveKeyframe(0)
        }
    }

    @KeyframeTrackContentBuilder<CGFloat> var yKeys: some KeyframeTrackContent<CGFloat> {
        if case .slide(_, let dy) = self {
            CubicKeyframe(dy, duration: 0.16)
            CubicKeyframe(0, duration: 0.26)
        } else {
            MoveKeyframe(0)
        }
    }

    @KeyframeTrackContentBuilder<CGFloat> var scaleKeys: some KeyframeTrackContent<CGFloat> {
        if case .grow = self {
            CubicKeyframe(1.07, duration: 0.16)
            CubicKeyframe(1, duration: 0.26)
        } else {
            MoveKeyframe(1)
        }
    }
}

/// `HoverNudge` for views that don't track hover themselves (plain buttons, clickable rows).
struct HoverNudgeTracking: ViewModifier {
    @State private var nudges = 0
    @Environment(\.isEnabled) private var enabled

    func body(content: Content) -> some View {
        content
            .modifier(HoverNudge(count: nudges))
            .onHover { inside in if inside && enabled { nudges += 1 } }
    }
}

/// A ring in `shape` while the view has keyboard focus.
struct WFocusRing<S: Shape>: ViewModifier {
    let shape: S
    var inset: CGFloat = -3
    @Environment(\.isFocused) private var focused

    func body(content: Content) -> some View {
        content
            .overlay(shape.stroke(Color.white.opacity(0.8), lineWidth: 2).padding(inset).opacity(focused ? 1 : 0))
            .animation(WMotion.animation(WMotion.snappy), value: focused)
    }
}

// MARK: - Rows

/// A faint highlight behind a list row while the pointer is over it, fading in and out.
struct WRowHover<S: Shape>: ViewModifier {
    var shape: S
    @State private var hovering = false

    func body(content: Content) -> some View {
        content
            .background(shape.fill(Color.white.opacity(hovering ? 0.05 : 0)))
            .modifier(HoverNudgeTracking())
            .onHover { inside in
                withAnimation(WMotion.reduceMotion ? nil : .easeOut(duration: 0.15)) { hovering = inside }
            }
    }
}

extension View {
    /// Hover highlight for a row inside a grouped list (the group clips the corners).
    func rowHover() -> some View { modifier(WRowHover(shape: Rectangle())) }
    func rowHover<S: Shape>(_ shape: S) -> some View { modifier(WRowHover(shape: shape)) }
}

// MARK: - Codes

/// A code whose digits arrive one by one, 25 ms apart: rolling up from below like an odometer
/// (one-time codes) or dropping in from above (pairing codes).
struct RollingCode: View {
    let code: String
    var font: Font
    var tracking: CGFloat = 0
    var from: Edge = .bottom
    @State private var shown = PanelSnapshots.active

    var body: some View {
        let characters = Array(spaced(code))
        HStack(spacing: 0) {
            ForEach(Array(characters.enumerated()), id: \.offset) { index, character in
                Text(String(character))
                    .font(font)
                    .tracking(tracking)
                    .offset(y: shown || WMotion.reduceMotion ? 0 : (from == .bottom ? 18 : -18))
                    .opacity(shown ? 1 : 0)
                    .animation(WMotion.animation(WMotion.bouncy.delay(Double(index) * 0.025)), value: shown)
            }
        }
        // Digits roll in through a window just taller than the text.
        .padding(.vertical, 2)
        .clipped()
        .padding(.vertical, -2)
        .onAppear { shown = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(code.map(String.init).joined(separator: " "))
    }
}

// MARK: - Feedback

/// A horizontal shake: bump `shakes` inside an animation to shake once.
struct ShakeEffect: GeometryEffect {
    var shakes: CGFloat
    var travel: CGFloat = 5

    var animatableData: CGFloat {
        get { shakes }
        set { shakes = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        // Three swings that die down, back to rest at every whole number.
        let phase = shakes - shakes.rounded(.down)
        let x = sin(phase * .pi * 6) * travel * (1 - phase)
        return ProjectionTransform(CGAffineTransform(translationX: x, y: 0))
    }
}

extension View {
    /// Shakes once each time `trigger` grows. Nothing under Reduce Motion.
    func shake(_ trigger: Int) -> some View {
        modifier(ShakeEffect(shakes: CGFloat(trigger)))
            .animation(WMotion.reduceMotion ? nil : .linear(duration: 0.42), value: trigger)
    }
}

/// Shakes once when it appears (a transfer that failed).
struct ShakeOnAppear: ViewModifier {
    @State private var shakes = 0
    func body(content: Content) -> some View {
        content.shake(shakes).onAppear { if !PanelSnapshots.active { shakes += 1 } }
    }
}

/// Pops in when it appears: from 90 % and clear to full size on the bouncy spring.
struct PopIn: ViewModifier {
    @State private var shown = PanelSnapshots.active
    var delay: Double = 0

    func body(content: Content) -> some View {
        content
            .scaleEffect(shown || WMotion.reduceMotion ? 1 : 0.9)
            .opacity(shown ? 1 : 0)
            .onAppear { withMotion(WMotion.bouncy.delay(delay)) { shown = true } }
    }
}

/// Three dots breathing one after another, for "looking for your phone". Only exists in that
/// state, so nothing runs otherwise; still dots under Reduce Motion.
struct LookingDots: View {
    @State private var on = false

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .frame(width: 4, height: 4)
                    .opacity(on ? 1 : 0.3)
                    .animation(WMotion.reduceMotion ? nil
                               : .easeInOut(duration: 0.6).repeatForever(autoreverses: true).delay(Double(index) * 0.2),
                               value: on)
            }
        }
        .onAppear { on = true }
        .accessibilityHidden(true)
    }
}

// MARK: - Swapping content

/// One of two things swapping in place (a copy icon and its tick, Copy and "Copied"): the one
/// leaving blurs, shrinks to 90 % and fades as the other comes in. Only a fade under Reduce Motion.
struct SwapState: ViewModifier {
    let shown: Bool
    func body(content: Content) -> some View {
        let still = shown || WMotion.reduceMotion
        content
            .opacity(shown ? 1 : 0)
            .scaleEffect(still ? 1 : 0.9)
            .blur(radius: still ? 0 : 2)
    }
}

extension View {
    /// Shown or swapped out in place; animate the change with `WMotion.quick`.
    func swapState(shown: Bool) -> some View { modifier(SwapState(shown: shown)) }
}

extension AnyTransition {
    /// `SwapState` as a transition, for content swapped with `if`/`else`.
    static var swap: AnyTransition {
        .modifier(active: SwapState(shown: false), identity: SwapState(shown: true))
    }
}

/// A cheap stand-in for a list's ids as an animation value: rows coming and going, or a new
/// one on top. Unlike `ids.map(\.id)` it doesn't build an array on every body evaluation.
struct ListChange: Equatable {
    let count: Int
    let first: AnyHashable?
    let last: AnyHashable?

    init<C: BidirectionalCollection, ID: Hashable>(_ items: C, _ id: KeyPath<C.Element, ID>) {
        count = items.count
        first = items.first.map { AnyHashable($0[keyPath: id]) }
        last = items.last.map { AnyHashable($0[keyPath: id]) }
    }
}

/// Lays a view out at its natural height, but reports no height while `collapsed`: something
/// on its way out shouldn't hold its container open while the new content springs to size.
struct CollapsedHeight: ViewModifier {
    let collapsed: Bool
    func body(content: Content) -> some View {
        content
            .fixedSize(horizontal: false, vertical: true)
            .frame(height: collapsed ? 0 : nil, alignment: .top)
    }
}

extension AnyTransition {
    /// For removals in a ZStack: stops taking up room at once (combine with a fade or slide).
    static var collapsingHeight: AnyTransition {
        .modifier(active: CollapsedHeight(collapsed: true), identity: CollapsedHeight(collapsed: false))
    }
}

// MARK: - Panel reveal

private struct PanelRevealedKey: EnvironmentKey {
    static let defaultValue = true
}

private struct PanelRevealInstantKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// False while the panel is closed or still growing out of the notch: sections fade in after.
    var panelRevealed: Bool {
        get { self[PanelRevealedKey.self] }
        set { self[PanelRevealedKey.self] = newValue }
    }

    /// The panel was opened from the keyboard: everything is there at once, no stagger.
    var panelRevealInstant: Bool {
        get { self[PanelRevealInstantKey.self] }
        set { self[PanelRevealInstantKey.self] = newValue }
    }
}

/// A section of the panel: fades in and rises 4 pt as the panel opens, a moment after the
/// section above it (all of them readable within about 250 ms). Opened from the keyboard it's
/// simply there. Disappears at once when the panel closes.
struct RevealSection: ViewModifier {
    let index: Int
    @Environment(\.panelRevealed) private var revealed
    @Environment(\.panelRevealInstant) private var instant

    func body(content: Content) -> some View {
        content
            .opacity(revealed ? 1 : 0)
            .offset(y: revealed || instant || WMotion.reduceMotion ? 0 : 4)
            .animation(animation, value: revealed)
    }

    private var animation: Animation? {
        guard revealed else { return .easeOut(duration: WMotion.instantFade) }
        if instant { return nil }
        return WMotion.animation(WMotion.snappy.delay(WMotion.revealDelay + Double(index) * WMotion.revealStep))
    }
}

extension View {
    func revealSection(_ index: Int) -> some View { modifier(RevealSection(index: index)) }
}

/// The notch growing into the panel: at 0 it's the notch's own rectangle (centred at the top,
/// small bottom corners), at 1 the whole panel with its 30 pt bottom corners.
struct NotchRevealShape: Shape {
    var progress: CGFloat
    var notch: CGSize

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let p = min(max(progress, 0), 1)
        let startWidth = min(notch.width, rect.width), startHeight = min(notch.height, rect.height)
        let width = startWidth + (rect.width - startWidth) * p
        let height = startHeight + (rect.height - startHeight) * p
        let radius = min(10 + 20 * p, height / 2)
        let frame = CGRect(x: rect.midX - width / 2, y: rect.minY, width: width, height: height)
        return UnevenRoundedRectangle(bottomLeadingRadius: radius, bottomTrailingRadius: radius, style: .continuous)
            .path(in: frame)
    }
}
