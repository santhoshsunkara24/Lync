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

/// A borderless panel above the menu bar that shows live moments, and the glow and drop zone
/// while files are dragged anywhere on the Mac.
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
    private var flingMonitor: Any?
    /// Where the fingers would put the card, before the resistance going down.
    private var flingRaw: CGFloat = 0
    private var flingSamples: [(time: TimeInterval, offset: CGFloat)] = []

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
        CapturePrivacy.apply(panel)
        panel.ignoresMouseEvents = true

        let root = OverlayRoot(model: model, placement: placementState).environmentObject(model.themeStore)
        let hosting = NSHostingView(rootView: root)
        hosting.layer?.backgroundColor = .clear
        // The drop zone takes dropped files itself (same path as the notch and the menu bar icon).
        let container = DropContainerView(drop: FileDropHandler(model: model, source: "overlay"))
        hosting.frame = container.bounds
        hosting.autoresizingMask = [.width, .height]
        container.addSubview(hosting)
        panel.contentView = container

        model.$live
            .removeDuplicates()
            .sink { [weak self] _ in DispatchQueue.main.async { self?.update() } }
            .store(in: &cancellables)
        // A new card starts where cards start, whatever the last one was swiped to.
        model.$live.removeDuplicates().sink { [weak self] _ in self?.placementState.fling = 0; self?.flingRaw = 0 }
            .store(in: &cancellables)
        flingMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self, event.window === self.panel else { return event }
            return self.fling(event) ? nil : event
        }
        model.$fileDrag
            .combineLatest(model.$dragApproach)
            .map { drag, approach in drag != nil && approach > 0 }
            .removeDuplicates()
            .sink { [weak self] _ in DispatchQueue.main.async { self?.update() } }
            .store(in: &cancellables)
        // With the panel open, live moments show there instead (in Recent).
        model.$panelOpen
            .removeDuplicates()
            .sink { [weak self] _ in DispatchQueue.main.async { self?.update() } }
            .store(in: &cancellables)

        // Typing a reply needs keyboard focus; the panel takes it without activating the app.
        model.$replyingTo
            .removeDuplicates()
            .sink { [weak self] key in
                guard let self else { return }
                if key != nil { self.panel.makeKeyAndOrderFront(nil) } else if self.panel.isKeyWindow, !self.wantsKey { self.panel.resignKey() }
            }
            .store(in: &cancellables)

        NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, self.model.live != nil else { return }
            self.position()
        }
    }

    /// A pairing request takes keyboard focus while it waits: Return pairs, Esc declines.
    private var wantsKey: Bool {
        if case .pairing = model.live, model.pairingRequest != nil { return true }
        return false
    }

    /// Something to show: a card, or the glow while files are dragged towards the notch (not in
    /// menu bar mode, where the menu bar icon takes the colour instead).
    private var hasContent: Bool {
        guard !model.panelOpen else { return false }
        if model.live != nil { return true }
        return showsGlow && placementState.placement != .menuBar
    }

    private var showsGlow: Bool { model.fileDrag != nil && model.dragApproach > 0 }

    /// Two fingers on the trackpad move the card with them: up and away toward the notch, like a
    /// notification banner; down, it resists. Let go far enough (40 pt) or fast enough (300 pt/s)
    /// and it carries on into the notch at the fingers' speed; otherwise it springs back.
    private func fling(_ event: NSEvent) -> Bool {
        guard let card = model.live, card.isFlingable, !event.momentumPhase.contains(.changed),
              !event.momentumPhase.contains(.began), !event.momentumPhase.contains(.ended) else { return false }
        let phase = event.phase
        if phase.contains(.began) {
            flingRaw = placementState.fling
            flingSamples.removeAll()
        }
        if phase.contains(.changed) || phase.contains(.began) {
            // Follows the fingers whichever scroll direction is set.
            let dy = event.isDirectionInvertedFromDevice ? event.scrollingDeltaY : -event.scrollingDeltaY
            flingRaw += dy
            let shown = flingRaw <= 0 ? flingRaw : 60 * (1 - 1 / (flingRaw * 0.55 / 60 + 1))
            placementState.fling = shown
            flingSamples.append((event.timestamp, flingRaw))
            if flingSamples.count > 6 { flingSamples.removeFirst() }
            return true
        }
        if phase.contains(.ended) || phase.contains(.cancelled) {
            var velocity: CGFloat = 0
            if let first = flingSamples.first, let last = flingSamples.last, last.time > first.time {
                velocity = (last.offset - first.offset) / CGFloat(last.time - first.time)
            }
            let current = placementState.fling
            if current < -40 || velocity < -300 {
                let target: CGFloat = -240
                withAnimation(.interpolatingSpring(stiffness: 300, damping: 2 * sqrt(300),
                                                   initialVelocity: Double(velocity / min(-1, target - current)))) {
                    placementState.fling = target
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) { [weak self] in
                    guard let self, self.model.live == card else { return }
                    self.model.hideLive()
                }
            } else {
                withAnimation(.interpolatingSpring(stiffness: 420, damping: 2 * sqrt(420),
                                                   initialVelocity: Double(current == 0 ? 0 : -velocity / current))) {
                    placementState.fling = 0
                }
                flingRaw = 0
            }
            return true
        }
        return false
    }

    private func update() {
        hideWorkItem?.cancel()
        let card = model.live
        if card != nil || showsGlow { position() }
        if hasContent {
            // Quiet notes and the glow let clicks and drops through; the drop zone takes drops.
            let passive = card == nil || card?.isQuiet == true
            panel.ignoresMouseEvents = passive
            panel.orderFrontRegardless()
            if wantsKey { panel.makeKey() } else if panel.isKeyWindow, model.replyingTo == nil { panel.resignKey() }
        } else {
            panel.ignoresMouseEvents = true
            if panel.isKeyWindow { panel.resignKey() }
            let work = DispatchWorkItem { [weak self] in
                guard let self, !self.hasContent else { return }
                self.panel.orderOut(nil)
            }
            hideWorkItem = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
        }
    }

    /// ⌥⌘L while a card with buttons is showing: gives the card keyboard focus (Tab moves
    /// between its buttons). False when there's no such card, and the shortcut opens the panel.
    func focusCard() -> Bool {
        guard panel.isVisible, !model.panelOpen, let card = model.live else { return false }
        switch card {
        case .pairing, .call, .notification:
            if panel.isKeyWindow { return false }
            panel.makeKey()
            return true
        default:
            return false
        }
    }

    /// Picks the placement for the current mode and screen, and moves the panel there: the
    /// notch on the built-in display, the menu bar icon in menu bar mode, otherwise a pill at
    /// the top centre of the screen in use (the one with the keyboard focus, or the pointer
    /// for a file drag).
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

        let dragging = model.fileDrag != nil || model.isDropHint(model.live)
        let pointerScreen = NSScreen.screens.first { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) }
        guard let screen = (dragging ? pointerScreen : nil) ?? NSScreen.main ?? NSScreen.screens.first else { return }
        let notchHeight = screen.safeAreaInsets.top
        if notchHeight > 0 {
            placementState.placement = .notch(notchHeight: notchHeight)
            if let left = screen.auxiliaryTopLeftArea, let right = screen.auxiliaryTopRightArea {
                placementState.notchWidth = screen.frame.width - left.width - right.width
            }
            panel.setFrame(NSRect(x: screen.frame.midX - width / 2, y: screen.frame.maxY - height,
                                  width: width, height: height), display: true)
        } else {
            placementState.placement = .pill
            panel.setFrame(NSRect(x: screen.frame.midX - width / 2, y: screen.visibleFrame.maxY - 6 - height,
                                  width: width, height: height), display: true)
        }
    }

    /// Where a file drag should be held to open the drop zone, in screen coordinates: around
    /// the notch (120 pt), the menu bar icon (80 pt), or the top centre of the pointer's screen.
    static func dropTarget(model: AppModel, statusFrame: NSRect?) -> NSRect? {
        if model.displayMode == .menuBar, let statusFrame { return statusFrame.insetBy(dx: -80, dy: -80) }
        guard let screen = NSScreen.screens.first(where: { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) }) else { return nil }
        if screen.safeAreaInsets.top > 0, let left = screen.auxiliaryTopLeftArea, let right = screen.auxiliaryTopRightArea {
            let notch = NSRect(x: screen.frame.minX + left.width, y: screen.frame.maxY - screen.safeAreaInsets.top,
                               width: screen.frame.width - left.width - right.width, height: screen.safeAreaInsets.top)
            return notch.insetBy(dx: -120, dy: -120)
        }
        if model.displayMode == .menuBar || !model.hasNotch, let statusFrame, screen.frame.intersects(statusFrame) {
            return statusFrame.insetBy(dx: -80, dy: -80)
        }
        let pill = NSRect(x: screen.frame.midX - 120, y: screen.visibleFrame.maxY - 40, width: 240, height: 40)
        return pill.insetBy(dx: -120, dy: -120)
    }
}

final class PlacementState: ObservableObject {
    @Published var placement: OverlayPlacement = .pill
    @Published var notchWidth: CGFloat = 200
    /// How far a two-finger swipe has moved the card, in points (negative is up, toward the notch).
    @Published var fling: CGFloat = 0
}

/// Holds the overlay's SwiftUI content and takes files dropped on the drop zone.
private final class DropContainerView: NSView {
    private let drop: FileDropHandler

    init(drop: FileDropHandler) {
        self.drop = drop
        super.init(frame: NSRect(x: 0, y: 0, width: 100, height: 100))
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { drop.entered(sender) }
    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation { drop.entered(sender, update: true) }
    override func draggingExited(_ sender: NSDraggingInfo?) { drop.exited() }
    override func draggingEnded(_ sender: NSDraggingInfo) { drop.exited() }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool { drop.perform(sender) }
}

/// Draws the card in the right shape for the placement and animates it in and out: out of the
/// notch (bouncy) and back up into it (snappy). Going from one card to another keeps the shape,
/// springs it to the new size and crossfades what's inside.
struct OverlayRoot: View {
    @ObservedObject var model: AppModel
    @ObservedObject var placement: PlacementState
    @EnvironmentObject var theme: ThemeStore
    @ObservedObject private var motion = ReduceMotionObserver.shared
    @State private var hovering = false
    /// 0…1: the quick bump when files are dropped on the notch.
    @State private var pulse: CGFloat = 0

    var body: some View {
        VStack(spacing: 0) {
            if let card = model.live, card.isQuiet {
                quietView(card)
                    .transition(transition)
            } else if let card = model.live, !(card == .dropZone && placement.placement != .menuBar) {
                cardView(card)
                    // Moved by a two-finger swipe; fading as it heads back into the notch.
                    .offset(y: placement.fling)
                    .opacity(1 + Double(min(0, placement.fling)) / 180)
                    // Files dragged over the notch: it swells a little while they're held there.
                    .scaleEffect(swells(card) ? 1.03 : 1, anchor: .top)
                    .scaleEffect(1 + 0.05 * pulse, anchor: .top)
                    .brightness(0.08 * pulse)
                    .shake(model.dropShake)
                    .transition(transition)
            } else if placement.placement != .menuBar, model.live == .dropZone || (model.fileDrag != nil && model.dragApproach > 0) {
                // The same glow and pill from approaching to dropping: at the notch it brightens and
                // says "Release". One view throughout, so it grows rather than swapping for a card.
                DragGlow(model: model, placement: placement.placement, notchWidth: placement.notchWidth,
                         ready: model.live == .dropZone)
                    .scaleEffect(1 + 0.05 * pulse, anchor: .top)
                    .brightness(0.08 * pulse)
                    .transition(WMotion.transition(.opacity.combined(with: .scale(scale: 0.9, anchor: .top))))
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        // Increase Contrast or Reduce Transparency changed: redraw with the new colours.
        .id(motion.appearanceKey)
        .animation(WMotion.animation(WMotion.smooth), value: model.live)
        .animation(WMotion.animation(WMotion.smooth), value: model.fileDrag != nil && model.dragApproach > 0)
        .onChange(of: model.dropPulse) { _, _ in
            guard !WMotion.reduceMotion else { return }
            withAnimation(WMotion.press) { pulse = 1 }
            withAnimation(WMotion.snappy.delay(0.1)) { pulse = 0 }
        }
    }

    /// "Connected", "Link opened", "Sent": a small note just under the notch (or a small pill)
    /// that comes and goes without asking for attention.
    @ViewBuilder private func quietView(_ card: LiveCard) -> some View {
        if case .quiet(let symbol, let text) = card {
            let label = HStack(spacing: 8) {
                if symbol == LinkCatchView.symbol {
                    LinkCatchView(connected: true, shaded: false, playOnAppear: true).frame(width: 18, height: 18)
                } else {
                    NudgeImage(symbol).font(.wh(12, .semibold)).foregroundStyle(WTheme.codeMint)
                }
                Text(text).font(.wh(12, .semibold)).foregroundStyle(.white).lineLimit(1)
            }
            .accessibilityElement(children: .combine)
            switch placement.placement {
            case .notch(let notchHeight):
                label
                    .padding(.top, notchHeight + 6).padding(.horizontal, 18).padding(.bottom, 10)
                    .frame(minWidth: placement.notchWidth)
                    .background(NotchShape(cornerRadius: 16).fill(Color.black))
            case .pill, .menuBar:
                label
                    .padding(.horizontal, 14).frame(height: 32)
                    .background(Capsule().fill(theme.theme.card).shadow(color: .black.opacity(0.3), radius: 8, y: 3))
                    .overlay(Capsule().strokeBorder(WTheme.edge, lineWidth: 1))
                    .padding(.top, 8)
            }
        }
    }

    /// The "Drop to send" card, with a phone to send to.
    /// The drop card keeps the drag glow's colour, so glow and card read as one thing.
    @ViewBuilder private func dropGlow<S: Shape>(_ card: LiveCard, _ shape: S) -> some View {
        if card == .dropZone {
            shape.fill(theme.theme.glow).blur(radius: 16).opacity(0.45)
        }
    }

    private func swells(_ card: LiveCard) -> Bool {
        model.connectedPhone != nil && model.isDropHint(card) && !WMotion.reduceMotion
    }

    @ViewBuilder
    private func cardView(_ card: LiveCard) -> some View {
        // A new kind of card crossfades inside the same shape; the old one stops taking up room
        // at once so the shape springs straight to the new size.
        let content = ZStack(alignment: .topLeading) {
            LiveCardView(model: model, card: card)
                .frame(width: OverlayController.cardWidth - 36, alignment: .leading)
                .id(cardKey(card))
                .transition(contentSwap)
        }
        // Clip what's fading out to the card, with room for buttons' hover and focus rings.
        .padding(4).clipped().padding(-4)

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
                .background(dropGlow(card, RoundedRectangle(cornerRadius: 24, style: .continuous)))
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .strokeBorder(WTheme.edge, lineWidth: 1)
                )
                .overlay(alignment: .topLeading) { closeButton(card).offset(x: -6, y: -6) }
                .padding(.top, 8)
                .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .onTapGesture { openPanel(from: card) }
                .onHover { hover($0) }
        }
    }

    private var transition: AnyTransition {
        if WMotion.reduceMotion { return .opacity.animation(WMotion.reduced) }
        switch placement.placement {
        case .notch:
            // Expands out of the notch; shrinks back up into it.
            return .asymmetric(
                insertion: .scale(scale: 0.9, anchor: .top).combined(with: .opacity).animation(WMotion.bouncy),
                removal: .scale(scale: 0.6, anchor: .top).combined(with: .opacity).animation(WMotion.snappy)
            )
        case .pill, .menuBar:
            return .asymmetric(
                insertion: .scale(scale: 0.9, anchor: .top).combined(with: .opacity).animation(WMotion.bouncy),
                removal: .scale(scale: 0.9, anchor: .top).combined(with: .opacity).animation(WMotion.snappy)
            )
        }
    }

    private var contentSwap: AnyTransition {
        let removal = AnyTransition.collapsingHeight.combined(with: .opacity).animation(.easeOut(duration: 0.12))
        if WMotion.reduceMotion { return .asymmetric(insertion: .opacity.animation(WMotion.reduced), removal: removal) }
        return .asymmetric(
            insertion: .opacity.combined(with: .offset(y: -4)).animation(WMotion.smooth.delay(0.06)),
            removal: removal
        )
    }

    private func hover(_ inside: Bool) {
        // The ✕ eases in and out with the pointer instead of popping.
        withAnimation(WMotion.animation(WMotion.snappy)) { hovering = inside }
        model.setHovering(inside)
    }

    /// Cards with their own buttons (pairing, notifications, calls) don't react to clicks.
    private func isPassive(_ card: LiveCard) -> Bool {
        switch card {
        case .pairing, .notification, .call, .dropZone: return false
        default: return !model.isDropHint(card)
        }
    }

    /// A click on a passive card opens the panel, where the same thing is in Recent. A click on
    /// a notification opens the panel at that notification (or Texts for a text message).
    private func openPanel(from card: LiveCard) {
        if case .notification(let key) = card, model.replyingTo == nil {
            model.openNotification(key)
            return
        }
        guard isPassive(card) else { return }
        model.hideLive()
        model.noteUserOpenedPanel()
        model.openPanel()
    }

    /// ✕ in the corner while the pointer is over a passive card.
    @ViewBuilder private func closeButton(_ card: LiveCard) -> some View {
        if hovering && isPassive(card) {
            Button { model.hideLive() } label: {
                Image(systemName: "xmark")
                    .font(.wh(11, .bold))
                    .foregroundStyle(.white)
                    .frame(width: 24, height: 24)
                    .background(Circle().fill(Color(white: 0.25)))
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.15), lineWidth: 0.5))
                    // A little more to aim at than it shows.
                    .padding(2)
                    .contentShape(Circle())
            }
            .buttonStyle(WShapePress(shape: Circle()))
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
        case .quiet(_, let text): return "quiet-\(text)"
        case .dropZone: return "drop"
        case .sending(let id): return "sending-\(id)"
        case .notification(let key): return "notif-\(key)"
        case .call(let id): return "call-\(id)"
        case .info(_, let title, _): return "info-\(title)"
        case .problem(let problem): return "problem-\(problem.id)"
        }
    }
}

/// A file drag heading for the notch: a bloom of the phone's colour spreading from under the
/// notch, a chevron pointing into it, and a small floating pill, "Drop to send to <phone>",
/// once the pointer is close. All of it grows with `AppModel.dragApproach`; nothing shows for
/// drags elsewhere on the Mac, and on the way it lets clicks and drops through. At the notch
/// (`ready`, the drop zone) it is the drop target: full strength, "Release to send", what's coming. The bloom is a gradient (no blur to redraw); close up it breathes and the chevron bobs,
/// except under Reduce Motion.
struct DragGlow: View {
    @ObservedObject var model: AppModel
    let placement: OverlayPlacement
    var notchWidth: CGFloat
    /// At the notch: the drop zone is open and letting go sends the files.
    var ready = false
    @EnvironmentObject private var theme: ThemeStore
    @State private var breathe = false

    var body: some View {
        let approach = ready ? 1 : model.dragApproach
        let drag = model.fileDrag
        let phone = model.connectedPhone?.name ?? "your phone"
        let color = theme.theme.glow
        let top: CGFloat = { if case .notch(let height) = placement { return height } else { return 4 } }()
        // The pill comes in over the last half of the approach; its space is kept so nothing jumps.
        let pill = min(max((approach - 0.4) / 0.4, 0), 1)
        ZStack(alignment: .top) {
            bloom(color, approach: approach, width: max(notchWidth * 1.7, 300), top: top)
            VStack(spacing: 4) {
                Image(systemName: "chevron.compact.down")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.55 + 0.4 * approach))
                    .shadow(color: color, radius: 6)
                    .offset(y: breathe ? 3 : 0)
                HStack(spacing: 8) {
                    if ready, let images = drag?.images, !images.isEmpty {
                        ThumbnailStack(urls: images, size: 22)
                    } else {
                        Image(systemName: ready ? "arrow.up.doc.fill" : "hand.point.up.left.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(color)
                    }
                    // At the notch the phone's name has been said already: what matters is letting go.
                    (Text(ready ? "Release to send" : "Drop to send to \(phone)").foregroundStyle(.white)
                        + Text(ready ? drag.map { "  ·  \($0.summary)" } ?? "" : "").foregroundStyle(.white.opacity(0.6)))
                        .font(.wh(13, .semibold)).lineLimit(1)
                }
                .padding(.horizontal, 16).frame(height: ready ? 40 : 36)
                .background(Capsule().fill(Color.black.opacity(0.9)))
                .overlay(Capsule().strokeBorder(color.opacity(ready ? 0.9 : 0.45), lineWidth: ready ? 1.5 : 1))
                .shadow(color: color.opacity(ready ? 0.7 : 0.45), radius: ready ? 16 : 12)
                .opacity(pill)
                .scaleEffect(0.9 + 0.1 * pill, anchor: .top)
            }
            .padding(.top, top + 4)
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .animation(WMotion.animation(WMotion.smooth), value: approach)
        .animation(WMotion.animation(WMotion.smooth), value: ready)
        .onAppear {
            guard approach >= 0.6, !WMotion.reduceMotion, !PanelSnapshots.active else { return }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { breathe = true }
        }
        .onChange(of: approach >= 0.6) { _, close in
            // Breathes only once the pointer is close: a hint, not a call for attention.
            guard !WMotion.reduceMotion, !PanelSnapshots.active else { return }
            if close {
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { breathe = true }
            } else {
                withAnimation(.easeOut(duration: 0.2)) { breathe = false }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ready ? "Drop to send to \(phone)\(drag.map { ", \($0.summary)" } ?? "")" : "")
        .accessibilityHidden(!ready)
    }

    /// The phone's colours pouring out from under the notch, like light: a bright core in its main
    /// colour, a neighbouring colour mixed in to the sides (`WTheme.glowAccent`), everything fading to nothing at the edge (no
    /// straight sides). Starts at the top of the screen, so the notch sits in its middle.
    private func bloom(_ color: Color, approach: Double, width: CGFloat, top: CGFloat) -> some View {
        let accent = theme.theme.glowAccent
        let height = top + 110
        func light(_ color: Color, _ strength: Double, width: CGFloat, x: CGFloat) -> some View {
            let radius = width / 2
            return RadialGradient(
                stops: [.init(color: color.opacity(strength), location: 0), .init(color: color.opacity(strength * 0.65), location: 0.3),
                        .init(color: color.opacity(strength * 0.22), location: 0.65), .init(color: color.opacity(0), location: 1)],
                center: .top, startRadius: 0, endRadius: radius
            )
            .frame(width: width, height: radius)
            .scaleEffect(x: 1, y: height / radius, anchor: .top)
            .frame(height: height, alignment: .top)
            .offset(x: x)
        }
        return ZStack(alignment: .top) {
            light(accent, 0.75, width: width * 0.8, x: -width * 0.14)
            light(accent, 0.75, width: width * 0.8, x: width * 0.14)
            // The main colour on top, adding to the accent like light does, not averaging to grey.
            light(color, 0.9, width: width * 0.7, x: 0).blendMode(.plusLighter)
            light(color, 0.45, width: width * 0.32, x: 0).blendMode(.plusLighter)
        }
        .compositingGroup()
        .frame(width: width, height: height, alignment: .top)
        .opacity((0.5 + 0.5 * approach) * (breathe ? 0.88 : 1))
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

extension LiveCard {
    /// Cards a two-finger swipe can put away: not a pairing request or a call waiting for an
    /// answer, and not the drop zone or a send in progress.
    var isFlingable: Bool {
        switch self {
        case .pairing, .call, .dropZone, .sending, .quiet: return false
        default: return true
        }
    }
}
