import AppKit
import Combine

/// The menu bar icon (menu bar mode): click for the panel, drop files on it to send them to the
/// phone. Hidden in notch mode, where the notch does both.
final class StatusItemController: NSObject {
    private let model: AppModel
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private var cancellables = Set<AnyCancellable>()
    /// Opens or closes the panel. Set by the app delegate.
    var onToggle: () -> Void = {}

    init(model: AppModel, menu: @escaping () -> NSMenu) {
        self.model = model
        super.init()

        if let button = item.button {
            button.target = self
            button.action = #selector(togglePopover)
            button.imagePosition = .imageOnly
            button.setAccessibilityLabel("Lync")
            let drop = DropTargetView(frame: button.bounds, drop: FileDropHandler(model: model, source: "status"), menu: menu) { [weak self] in
                self?.togglePopover()
            }
            drop.autoresizingMask = [.width, .height]
            button.addSubview(drop)
        }

        model.$connectedPhone
            .combineLatest(model.$pairingRequest.map { $0 != nil }, model.$listenerProblem.map { $0 != nil })
            .sink { [weak self] phone, pairing, failing in self?.updateIcon(connected: phone != nil, attention: pairing, failing: failing) }
            .store(in: &cancellables)
        // Files dragged anywhere on the Mac: the icon glows Mint (pulsing gently; steady under Reduce Motion).
        model.$fileDrag.map { $0 != nil }.removeDuplicates()
            .sink { [weak self] dragging in self?.glow(dragging) }
            .store(in: &cancellables)
    }

    private func glow(_ on: Bool) {
        guard let button = item.button else { return }
        button.wantsLayer = true
        button.contentTintColor = on ? NSColor(srgbRed: 0x7F / 255.0, green: 0xE3 / 255.0, blue: 0xB0 / 255.0, alpha: 1) : nil
        button.layer?.removeAnimation(forKey: "dragGlow")
        guard on, !WMotion.reduceMotion else { return }
        let pulse = CABasicAnimation(keyPath: "opacity")
        pulse.fromValue = 1
        pulse.toValue = 0.55
        pulse.duration = 0.9
        pulse.autoreverses = true
        pulse.repeatCount = .infinity
        pulse.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        button.layer?.add(pulse, forKey: "dragGlow")
    }

    /// Shown in menu bar mode, or always on a Mac without a notch.
    func update(mode: DisplayMode, hasNotch: Bool) {
        item.isVisible = mode == .menuBar || !hasNotch
    }

    /// The icon's frame on screen, or nil if it's hidden (for example behind the notch).
    var visibleFrame: NSRect? {
        guard let window = item.button?.window, window.isVisible else { return nil }
        let frame = window.frame
        guard let screen = NSScreen.screens.first(where: { $0.frame.intersects(frame) }) else { return nil }
        if screen.safeAreaInsets.top > 0,
           let left = screen.auxiliaryTopLeftArea, let right = screen.auxiliaryTopRightArea {
            let notch = NSRect(x: screen.frame.minX + left.width, y: frame.minY,
                               width: screen.frame.width - left.width - right.width, height: frame.height)
            if notch.intersects(frame) { return nil }
        }
        return frame
    }

    private func updateIcon(connected: Bool, attention: Bool, failing: Bool = false) {
        // The Lync droid (Resources/MenuBarIcon*.png, a template image); a dot at its top right when something
        // needs attention, a ring when Lync can't accept connections at all. Dimmed while no phone is connected.
        let image = attention ? Self.markWithDot : failing ? Self.markWithRing : Self.mark
        let state = attention ? "Lync, needs attention" : failing ? "Lync, can't accept connections"
            : connected ? "Lync, phone connected" : "Lync, phone not connected"
        image?.accessibilityDescription = state
        guard let button = item.button else { return }
        button.setAccessibilityLabel(state)
        let disabled = !connected && !attention
        // A short crossfade when the icon changes (the dot coming or going, connecting).
        if button.image != nil, button.image !== image || button.appearsDisabled != disabled {
            button.wantsLayer = true
            let fade = CATransition()
            fade.type = .fade
            fade.duration = WMotion.reduceMotion ? 0.12 : 0.25
            fade.timingFunction = CAMediaTimingFunction(name: .easeOut)
            button.layer?.add(fade, forKey: "iconFade")
        }
        button.image = image
        button.appearsDisabled = disabled
    }

    private static let mark: NSImage? = {
        let image = NSImage(named: "MenuBarIcon") ?? NSImage(systemSymbolName: "iphone", accessibilityDescription: "Lync")
        image?.isTemplate = true
        return image
    }()

    private static let markWithDot: NSImage? = {
        guard let base = mark else { return nil }
        let dot: CGFloat = 6, size = NSSize(width: base.size.width + dot / 2, height: base.size.height)
        let image = NSImage(size: size, flipped: false) { _ in
            base.draw(in: NSRect(origin: .zero, size: base.size))
            NSBezierPath(ovalIn: NSRect(x: size.width - dot, y: size.height - dot, width: dot, height: dot)).fill()
            return true
        }
        image.isTemplate = true
        return image
    }()

    /// The listener failed: a hollow ring where the attention dot goes, so it reads as a problem
    /// with Lync itself rather than "phone away".
    private static let markWithRing: NSImage? = {
        guard let base = mark else { return nil }
        let dot: CGFloat = 6, size = NSSize(width: base.size.width + dot / 2, height: base.size.height)
        let image = NSImage(size: size, flipped: false) { _ in
            base.draw(in: NSRect(origin: .zero, size: base.size))
            let ring = NSBezierPath(ovalIn: NSRect(x: size.width - dot + 0.75, y: size.height - dot + 0.75, width: dot - 1.5, height: dot - 1.5))
            ring.lineWidth = 1.5
            ring.stroke()
            return true
        }
        image.isTemplate = true
        return image
    }()

    @objc private func togglePopover() {
        onToggle()
    }
}

/// Sits on top of the status item button to accept dropped files and show the right-click menu.
private final class DropTargetView: NSView {
    private let drop: FileDropHandler
    private let makeMenu: () -> NSMenu
    private let onClick: () -> Void

    init(frame: NSRect, drop: FileDropHandler, menu: @escaping () -> NSMenu, onClick: @escaping () -> Void) {
        self.drop = drop
        self.makeMenu = menu
        self.onClick = onClick
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func mouseDown(with event: NSEvent) {
        if event.modifierFlags.contains(.control) { rightMouseDown(with: event) } else { onClick() }
    }

    override func rightMouseDown(with event: NSEvent) {
        let menu = makeMenu()
        // Under the icon, like a menu bar menu.
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: bounds.minY - 4), in: self)
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { drop.entered(sender) }
    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation { drop.entered(sender, update: true) }
    override func draggingExited(_ sender: NSDraggingInfo?) { drop.exited() }
    override func draggingEnded(_ sender: NSDraggingInfo) { drop.exited() }
    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool { drop.perform(sender) }
}

/// Files dragged onto the notch or the menu bar icon. Only accepted while a phone is connected;
/// folders are left out (and the user told so).
final class FileDropHandler {
    private let model: AppModel
    /// Which drop target this is ("sensor", "status", "overlay", "panel"), for `AppModel.dropHover`.
    private let source: String

    init(model: AppModel, source: String) {
        self.model = model
        self.source = source
    }

    /// `update`: a move within the target; only the answer is needed.
    func entered(_ info: NSDraggingInfo, update: Bool = false) -> NSDragOperation {
        let (files, folders) = Self.read(info)
        guard !files.isEmpty || folders > 0 else { return [] }
        // Only folders: "Folders can't be sent" while they hover.
        if !update { model.dropHover(true, source: source, foldersOnly: files.isEmpty) }
        return model.connectedPhone != nil && !files.isEmpty ? .copy : []
    }

    func exited() {
        model.dropHover(false, source: source)
    }

    func perform(_ info: NSDraggingInfo) -> Bool {
        let (files, folders) = Self.read(info)
        guard model.connectedPhone != nil, !files.isEmpty else {
            model.dropHover(false, source: source)
            return false
        }
        model.dropAccepted()
        model.sendFilesToPhone(files, skippedFolders: folders)
        return true
    }

    private static func read(_ info: NSDraggingInfo) -> (files: [URL], folders: Int) {
        read(info.draggingPasteboard)
    }

    /// Dragged files, and how many folders were left out (they can't be sent).
    static func read(_ board: NSPasteboard) -> (files: [URL], folders: Int) {
        let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        let urls = board.readObjects(forClasses: [NSURL.self], options: options) as? [URL] ?? []
        let files = urls.filter { !$0.hasDirectoryPath }
        return (files, urls.count - files.count)
    }
}
