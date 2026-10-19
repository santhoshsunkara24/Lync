import AppKit
import Combine
import SwiftUI

/// Where the overlay draws itself on screen.
enum OverlayPlacement: Equatable {
    /// Grows out of the MacBook notch. `notchHeight` is the menu bar height under the notch.
    case notch(notchHeight: CGFloat)
    /// A floating pill at the top centre, for Macs without a notch.
    case pill
    /// Drops down from the menu bar icon.
    case menuBar
}

/// A borderless panel above the menu bar that shows live moments.
final class OverlayController {
    static let cardWidth: CGFloat = 400
    private static let windowPadding: CGFloat = 24
    private static let windowHeight: CGFloat = 220

    private let model: AppModel
    private let anchorFrame: () -> NSRect?
    private let panel: NSPanel
    private let placementState = PlacementState()
    private var cancellables = Set<AnyCancellable>()
    private var hideWorkItem: DispatchWorkItem?

    init(model: AppModel, anchorFrame: @escaping () -> NSRect?) {
        self.model = model
        self.anchorFrame = anchorFrame

        panel = KeyablePanel(
            contentRect: NSRect(x: 0, y: 0, width: Self.cardWidth + Self.windowPadding * 2, height: Self.windowHeight),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: true
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 2)
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.isMovable = false
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        // OTPs and messages never show up in screen shares or recordings.
        // WALLHOP_CAPTURE=1 allows screenshots, for development only.
        panel.sharingType = ProcessInfo.processInfo.environment["WALLHOP_CAPTURE"] == "1" ? .readOnly : .none
        panel.ignoresMouseEvents = true

        let root = OverlayRoot(model: model, placement: placementState).environmentObject(model.themeStore)
        let hosting = NSHostingView(rootView: root)
        hosting.layer?.backgroundColor = .clear
        panel.contentView = hosting

        model.$live
            .removeDuplicates()
            .sink { [weak self] card in self?.update(for: card) }
            .store(in: &cancellables)
        // With the panel open, live moments show there instead (in Recent).
        model.$panelOpen
            .removeDuplicates()
            .sink { [weak self] open in self?.update(for: open ? nil : self?.model.live) }
            .store(in: &cancellables)

        // Typing a reply needs keyboard focus; the panel takes it without activating the app.
        model.$replyingTo
            .removeDuplicates()
            .sink { [weak self] key in
                guard let self else { return }
                if key != nil { self.panel.makeKeyAndOrderFront(nil) } else if self.panel.isKeyWindow { self.panel.resignKey() }
            }
            .store(in: &cancellables)

        NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, self.model.live != nil else { return }
            self.position()
        }
    }

    private func update(for card: LiveCard?) {
        hideWorkItem?.cancel()
        if card != nil, !model.panelOpen {
            position()
            panel.ignoresMouseEvents = model.isDropHint(card)
            panel.orderFrontRegardless()
        } else {
            panel.ignoresMouseEvents = true
            let work = DispatchWorkItem { [weak self] in
                guard let self, self.model.live == nil else { return }
                self.panel.orderOut(nil)
            }
            hideWorkItem = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
        }
    }

    /// Picks the placement for the current mode and screen, and moves the panel there.
    private func position() {
        let width = Self.cardWidth + Self.windowPadding * 2
        let height = Self.windowHeight

        if model.displayMode == .menuBar, let anchor = anchorFrame(),
           let screen = NSScreen.screens.first(where: { $0.frame.intersects(anchor) }) ?? NSScreen.main {
            placementState.placement = .menuBar
            var x = anchor.midX - width / 2
            x = min(max(x, screen.visibleFrame.minX + 4), screen.visibleFrame.maxX - width - 4)
            panel.setFrame(NSRect(x: x, y: anchor.minY - 4 - height, width: width, height: height), display: true)
            return
        }

        // Notch mode, or menu bar mode when the icon is hidden behind the notch.
        guard let screen = Self.notchScreen ?? NSScreen.main ?? NSScreen.screens.first else { return }
        let notchHeight = screen.safeAreaInsets.top
        if notchHeight > 0 {
            placementState.placement = .notch(notchHeight: notchHeight)
            panel.setFrame(NSRect(x: screen.frame.midX - width / 2, y: screen.frame.maxY - height,
                                  width: width, height: height), display: true)
        } else {
            placementState.placement = .pill
            panel.setFrame(NSRect(x: screen.frame.midX - width / 2, y: screen.visibleFrame.maxY - 6 - height,
                                  width: width, height: height), display: true)
        }
    }

    /// The built-in display with a notch, if it's connected and on.
    private static var notchScreen: NSScreen? {
        NSScreen.screens.first { $0.safeAreaInsets.top > 0 }
    }
}

final class PlacementState: ObservableObject {
    @Published var placement: OverlayPlacement = .pill
}

/// Draws the card in the right shape for the placement and animates it in and out.
private struct OverlayRoot: View {
    @ObservedObject var model: AppModel
    @ObservedObject var placement: PlacementState
    @EnvironmentObject var theme: ThemeStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovering = false

    var body: some View {
        VStack(spacing: 0) {
            if let card = model.live {
                cardView(card)
                    .transition(transition)
                    .id(cardKey(card))
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .animation(reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.82), value: model.live)
    }

    @ViewBuilder
    private func cardView(_ card: LiveCard) -> some View {
        let content = LiveCardView(model: model, card: card)
            .frame(width: OverlayController.cardWidth - 36, alignment: .leading)

        switch placement.placement {
        case .notch(let notchHeight):
            content
                .padding(.top, notchHeight + 10)
                .padding(.horizontal, 18)
                .padding(.bottom, 16)
                // Black at the top to flow out of the notch, the phone's colour at the bottom.
                .background(NotchShape(cornerRadius: 26).fill(
                    LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.45),
                                           .init(color: theme.theme.card, location: 1)], startPoint: .top, endPoint: .bottom)))
                .contentShape(NotchShape(cornerRadius: 24))
                .onTapGesture { openPanel(from: card) }
                .overlay(alignment: .topLeading) {
                    closeButton(card).padding(.top, notchHeight + 1).padding(.leading, 4)
                }
                .onHover { hover($0) }
        case .pill, .menuBar:
            content
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(LinearGradient(colors: [theme.theme.mid, theme.theme.card], startPoint: .top, endPoint: .bottom))
                        .shadow(color: .black.opacity(0.35), radius: 14, y: 6)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.08), lineWidth: 1)
                )
                .overlay(alignment: .topLeading) { closeButton(card).offset(x: -6, y: -6) }
                .padding(.top, 8)
                .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .onTapGesture { openPanel(from: card) }
                .onHover { hover($0) }
        }
    }

    private var transition: AnyTransition {
        if reduceMotion { return .opacity }
        switch placement.placement {
        case .notch:
            return .asymmetric(
                insertion: .scale(scale: 0.45, anchor: .top).combined(with: .opacity),
                removal: .scale(scale: 0.45, anchor: .top).combined(with: .opacity)
            )
        case .pill, .menuBar:
            return .move(edge: .top).combined(with: .opacity)
        }
    }

    private func hover(_ inside: Bool) {
        hovering = inside
        model.setHovering(inside)
    }

    /// Cards with their own buttons (pairing, notifications, calls) don't react to clicks.
    private func isPassive(_ card: LiveCard) -> Bool {
        switch card {
        case .pairing, .notification, .call: return false
        default: return true
        }
    }

    /// A click on a passive card opens the panel, where the same thing is in Recent.
    private func openPanel(from card: LiveCard) {
        guard isPassive(card) else { return }
        model.hideLive()
        model.openPanel()
    }

    /// ✕ in the corner while the pointer is over a passive card.
    @ViewBuilder private func closeButton(_ card: LiveCard) -> some View {
        if hovering && isPassive(card) {
            Button { model.hideLive() } label: {
                Image(systemName: "xmark")
                    .font(.wh(9, .bold))
                    .foregroundStyle(.white)
                    .frame(width: 18, height: 18)
                    .background(Circle().fill(Color(white: 0.25)))
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.15), lineWidth: 0.5))
            }
            .buttonStyle(.plain)
            .help("Dismiss")
            .accessibilityLabel("Dismiss")
            .transition(.opacity)
        }
    }

    /// Cards of the same kind update in place; a new kind animates in fresh.
    private func cardKey(_ card: LiveCard) -> String {
        switch card {
        case .otp(let code, _): return "otp-\(code)"
        case .text(let text): return "text-\(text.hashValue)"
        case .receiving(let id), .added(let id): return "files-\(id)"
        case .saved(let id): return "saved-\(id)"
        case .pairing(let id): return "pair-\(id)"
        case .connected(let name): return "connected-\(name)"
        case .sentToPhone(let message): return "sent-\(message)"
        case .notification(let key): return "notif-\(key)"
        case .call(let id): return "call-\(id)"
        case .info(_, let title, _): return "info-\(title)"
        }
    }
}

/// Square top edge (flush with the notch) and rounded bottom corners, with small outward
/// curves at the top so the shape flows out of the notch.
struct NotchShape: Shape {
    var cornerRadius: CGFloat
    var earRadius: CGFloat = 10

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let r = min(cornerRadius, rect.height / 2)
        let e = earRadius
        path.move(to: CGPoint(x: rect.minX - e, y: rect.minY))
        path.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.minY + e), control: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - r))
        path.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.maxY), control: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.maxY))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.maxY - r), control: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + e))
        path.addQuadCurve(to: CGPoint(x: rect.maxX + e, y: rect.minY), control: CGPoint(x: rect.maxX, y: rect.minY))
        path.closeSubpath()
        return path
    }
}

/// A borderless panel that can take keyboard focus (for replies) without activating the app.
final class KeyablePanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}
