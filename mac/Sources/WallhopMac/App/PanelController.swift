import AppKit
import Combine
import SwiftUI

/// Shows Wallhop's panel: dropping out of the notch in notch mode, or under the menu bar icon in
/// menu bar mode. Closes on Esc, on a click elsewhere, and — when it opened by hovering the
/// notch — shortly after the pointer leaves it.
final class PanelController {
    private let model: AppModel
    private let statusFrame: () -> NSRect?
    private let panel: KeyablePanel
    private var cancellables = Set<AnyCancellable>()
    private var monitors: [Any] = []
    private var leaveTimer: Timer?
    private var outsideSince: Date?
    /// Opened by hovering the notch: closes when the pointer leaves.
    private(set) var openedByHover = false

    init(model: AppModel, statusFrame: @escaping () -> NSRect?) {
        self.model = model
        self.statusFrame = statusFrame
        panel = KeyablePanel(contentRect: NSRect(x: 0, y: 0, width: 380, height: 400),
                             styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 3)
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        panel.isMovable = false
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = false
        // Codes and messages never show up in screen shares or recordings (WALLHOP_CAPTURE=1 for development).
        panel.sharingType = ProcessInfo.processInfo.environment["WALLHOP_CAPTURE"] == "1" ? .readOnly : .none

        let root = PanelRoot(model: model, theme: model.themeStore) { [weak self] size in self?.resize(to: size) }
        let hosting = NSHostingView(rootView: root)
        hosting.layer?.backgroundColor = .clear
        panel.contentView = hosting

        model.$panelOpen.removeDuplicates().sink { [weak self] open in
            open ? self?.present() : self?.dismiss()
        }.store(in: &cancellables)
        model.$displayMode.dropFirst().sink { [weak self] _ in
            DispatchQueue.main.async { if self?.model.panelOpen == true { self?.present() } }
        }.store(in: &cancellables)
    }

    func open(byHover: Bool) {
        if model.panelOpen { if !byHover { openedByHover = false }; return }
        openedByHover = byHover
        model.openPanel()
    }

    func toggle() {
        if model.panelOpen { model.closePanel() } else { open(byHover: false) }
    }

    private var inNotch: Bool { model.displayMode == .notch && model.hasNotch }

    private func present() {
        position(size: panel.frame.size)
        panel.ignoresMouseEvents = false
        panel.alphaValue = 0
        panel.orderFrontRegardless()
        panel.makeKey()
        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.18
            panel.animator().alphaValue = 1
        }
        installMonitors()
    }

    private func dismiss() {
        removeMonitors()
        // Stops taking clicks at once; removed after the fade even if the animation never runs
        // (e.g. while the screen is locked).
        panel.ignoresMouseEvents = true
        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.14
            panel.animator().alphaValue = 0
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self, !self.model.panelOpen else { return }
            self.panel.orderOut(nil)
            if ProcessInfo.processInfo.environment["WALLHOP_CAPTURE"] == "1" { NSLog("Lync: panel visible=\(self.panel.isVisible)") }
        }
    }

    /// Keeps the top edge fixed while the content grows or shrinks.
    private func resize(to size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        position(size: size)
    }

    private func position(size: CGSize) {
        let width = size.width, height = size.height
        if inNotch, let screen = NSScreen.screens.first(where: { $0.safeAreaInsets.top > 0 }) {
            panel.setFrame(NSRect(x: screen.frame.midX - width / 2, y: screen.frame.maxY - height, width: width, height: height),
                           display: true)
            panel.hasShadow = true
            return
        }
        let anchor = statusFrame()
        // The screen the menu bar icon is on (it can be another display than the main one).
        guard let screen = anchor.flatMap({ frame in NSScreen.screens.first { $0.frame.intersects(frame) } })
                ?? NSScreen.main ?? NSScreen.screens.first else { return }
        if let anchor {
            var x = anchor.midX - width / 2
            x = min(max(x, screen.visibleFrame.minX + 6), screen.visibleFrame.maxX - width - 6)
            panel.setFrame(NSRect(x: x, y: anchor.minY - 6 - height, width: width, height: height), display: true)
        } else {
            panel.setFrame(NSRect(x: screen.visibleFrame.maxX - width - 12, y: screen.visibleFrame.maxY - 6 - height,
                                  width: width, height: height), display: true)
        }
        panel.hasShadow = true
    }

    private func installMonitors() {
        removeMonitors()
        // Esc goes back from Settings or Add a phone, then closes.
        if let keys = NSEvent.addLocalMonitorForEvents(matching: .keyDown, handler: { [weak self] event in
            guard let self, event.keyCode == 53, self.model.replyingTo == nil else { return event }
            if self.model.confirmingForget != nil {
                self.model.confirmingForget = nil
            } else if self.model.panelPage != .home {
                self.model.panelPage = .home
            } else {
                self.model.closePanel()
            }
            return nil
        }) { monitors.append(keys) }
        // A click anywhere else closes.
        if let clicks = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown], handler: { [weak self] _ in
            guard let self, !self.panel.frame.contains(NSEvent.mouseLocation) else { return }
            self.model.closePanel()
        }) { monitors.append(clicks) }
        // Opened by hover: close a moment after the pointer leaves the panel and the notch.
        outsideSince = nil
        leaveTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            guard let self, self.openedByHover, self.inNotch, self.model.replyingTo == nil else { return }
            let inside = self.panel.frame.insetBy(dx: -8, dy: -8).contains(NSEvent.mouseLocation)
            if inside { self.outsideSince = nil; return }
            if let since = self.outsideSince {
                if Date().timeIntervalSince(since) > 0.5 { self.model.closePanel() }
            } else {
                self.outsideSince = Date()
            }
        }
    }

    private func removeMonitors() {
        monitors.forEach(NSEvent.removeMonitor)
        monitors.removeAll()
        leaveTimer?.invalidate()
        leaveTimer = nil
    }
}

/// The panel's shape: square top under the notch (it grows out of it), rounded elsewhere.
private struct PanelRoot: View {
    @ObservedObject var model: AppModel
    @ObservedObject var theme: ThemeStore
    let onSize: (CGSize) -> Void

    private var inNotch: Bool { model.displayMode == .notch && model.hasNotch }

    var body: some View {
        let shape = inNotch
            ? AnyShape(UnevenRoundedRectangle(bottomLeadingRadius: 30, bottomTrailingRadius: 30, style: .continuous))
            : AnyShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        PanelView(model: model, theme: theme, fromNotch: inNotch)
            .clipShape(shape)
            .overlay(shape.stroke(Color.white.opacity(inNotch ? 0 : 0.08), lineWidth: 1))
            .fixedSize()
            .background(GeometryReader { proxy in
                Color.clear.onAppear { onSize(proxy.size) }.onChange(of: proxy.size) { _, size in onSize(size) }
            })
            .animation(.spring(response: 0.32, dampingFraction: 0.86), value: model.panelPage)
    }
}

/// An invisible strip over the notch: hover or click opens the panel; drop files to send them;
/// right-click for the menu.
final class NotchSensor {
    private let window: NSPanel
    private let view: SensorView

    init(onHover: @escaping () -> Void, onClick: @escaping () -> Void, drop: FileDropHandler, menu: @escaping () -> NSMenu) {
        window = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = false
        window.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        window.ignoresMouseEvents = false
        view = SensorView(onHover: onHover, onClick: onClick, drop: drop, menu: menu)
        window.contentView = view
    }

    /// Covers the notch on the built-in display; hidden when there's no notch or in menu bar mode.
    func update(active: Bool) {
        if ProcessInfo.processInfo.environment["WALLHOP_CAPTURE"] == "1" { NSLog("Lync: notch sensor active=\(active)") }
        guard active, let screen = NSScreen.screens.first(where: { $0.safeAreaInsets.top > 0 }),
              let left = screen.auxiliaryTopLeftArea, let right = screen.auxiliaryTopRightArea else {
            window.orderOut(nil)
            if ProcessInfo.processInfo.environment["WALLHOP_CAPTURE"] == "1" { NSLog("Lync: sensor visible=\(window.isVisible)") }
            return
        }
        let height = screen.safeAreaInsets.top
        let x = screen.frame.minX + left.width
        let width = screen.frame.width - left.width - right.width
        window.setFrame(NSRect(x: x, y: screen.frame.maxY - height, width: width, height: height), display: true)
        view.frame = NSRect(origin: .zero, size: window.frame.size)
        view.resetTracking()
        window.orderFrontRegardless()
    }
}

private final class SensorView: NSView {
    private let onHover: () -> Void
    private let onClick: () -> Void
    private let drop: FileDropHandler
    private let makeMenu: () -> NSMenu
    private var hoverWork: DispatchWorkItem?

    init(onHover: @escaping () -> Void, onClick: @escaping () -> Void, drop: FileDropHandler, menu: @escaping () -> NSMenu) {
        self.onHover = onHover
        self.onClick = onClick
        self.drop = drop
        self.makeMenu = menu
        super.init(frame: .zero)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func resetTracking() {
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self))
    }

    override func mouseEntered(with event: NSEvent) {
        // A short pause, so passing the pointer across the top of the screen doesn't open it.
        let work = DispatchWorkItem { [weak self] in self?.onHover() }
        hoverWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: work)
    }

    override func mouseExited(with event: NSEvent) { hoverWork?.cancel() }

    override func mouseDown(with event: NSEvent) {
        hoverWork?.cancel()
        if event.modifierFlags.contains(.control) {
            rightMouseDown(with: event)
        } else {
            onClick()
        }
    }

    override func rightMouseDown(with event: NSEvent) {
        hoverWork?.cancel()
        NSMenu.popUpContextMenu(makeMenu(), with: event, for: self)
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { drop.entered(sender) }
    override func draggingExited(_ sender: NSDraggingInfo?) { drop.exited() }
    override func draggingEnded(_ sender: NSDraggingInfo) { drop.exited() }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool { drop.perform(sender) }
}
