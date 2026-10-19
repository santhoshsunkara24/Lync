import SwiftUI

// Small, reusable pieces of motion. All of them go through `WMotion`, so Reduce Motion turns
// movement into a short fade, and none of them animate while nothing is happening.

// MARK: - Buttons

/// Hover, press and keyboard focus for the app's buttons: brightens and grows a touch on hover
/// (snappy), sinks on press (press spring), and draws a ring in the button's shape when it has
/// keyboard focus.
struct WInteractive<S: Shape>: ViewModifier {
    let shape: S
    let pressed: Bool
    @State private var hovering = false
    @Environment(\.isEnabled) private var enabled
    @Environment(\.isFocused) private var focused

    func body(content: Content) -> some View {
        let hover = hovering && enabled && !pressed
        content
            .brightness(hover ? 0.06 : 0)
            .overlay(shape.stroke(Color.white.opacity(0.8), lineWidth: 2).padding(-3).opacity(focused ? 1 : 0))
            .scaleEffect(pressed ? 0.96 : hover ? 1.02 : 1)
            .opacity(pressed ? 0.85 : 1)
            .contentShape(shape)
            .onHover { hovering = $0 }
            .animation(WMotion.animation(WMotion.press), value: pressed)
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

/// A text-only button (Undo, Clear all): dims and sinks a little while pressed.
struct WPlainPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(Rectangle())
            .animation(WMotion.animation(WMotion.press), value: configuration.isPressed)
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

/// Pops in when it appears: from small to full size on the bouncy spring.
struct PopIn: ViewModifier {
    @State private var shown = PanelSnapshots.active
    var delay: Double = 0

    func body(content: Content) -> some View {
        content
            .scaleEffect(shown || WMotion.reduceMotion ? 1 : 0.3)
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

extension EnvironmentValues {
    /// False while the panel is closed or still growing out of the notch: sections fade in after.
    var panelRevealed: Bool {
        get { self[PanelRevealedKey.self] }
        set { self[PanelRevealedKey.self] = newValue }
    }
}

/// A section of the panel: fades in and rises 4 pt once the panel has mostly opened, a little
/// after the section above it. Disappears at once when the panel closes.
struct RevealSection: ViewModifier {
    let index: Int
    @Environment(\.panelRevealed) private var revealed

    func body(content: Content) -> some View {
        content
            .opacity(revealed ? 1 : 0)
            .offset(y: revealed || WMotion.reduceMotion ? 0 : 4)
            .animation(revealed ? WMotion.animation(WMotion.smooth.delay(0.14 + Double(index) * 0.035))
                                : .easeOut(duration: 0.08),
                       value: revealed)
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
