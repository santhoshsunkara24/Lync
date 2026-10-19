import AppKit
import Combine
import SwiftUI

/// The menu bar icon (menu bar mode): click for the panel, drop files on it to send them to the
/// phone. Hidden in notch mode, where the notch does both.
final class StatusItemController: NSObject {
    private let model: AppModel
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private var cancellables = Set<AnyCancellable>()
    /// Opens or closes the panel. Set by the app delegate.
    var onToggle: () -> Void = {}

    init(model: AppModel) {
        self.model = model
        super.init()

        if let button = item.button {
            button.target = self
            button.action = #selector(togglePopover)
            button.imagePosition = .imageOnly
            let drop = DropTargetView(frame: button.bounds) { [weak self] urls in
                self?.model.sendFilesToPhone(urls)
            } onClick: { [weak self] in
                self?.togglePopover()
            }
            drop.autoresizingMask = [.width, .height]
            button.addSubview(drop)
        }

        model.$connectedPhone
            .combineLatest(model.$pairingRequest.map { $0 != nil })
            .sink { [weak self] phone, pairing in self?.updateIcon(connected: phone != nil, attention: pairing) }
            .store(in: &cancellables)
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

    private func updateIcon(connected: Bool, attention: Bool) {
        // The Lync droid (Resources/MenuBarIcon*.png, a template image); a dot at its top right when something
        // needs attention. Dimmed while no phone is connected.
        let image = attention ? Self.markWithDot : Self.mark
        image?.accessibilityDescription = attention ? "Lync, needs attention" : "Lync"
        item.button?.image = image
        item.button?.appearsDisabled = !connected && !attention
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

    @objc private func togglePopover() {
        onToggle()
    }
}

/// Sits on top of the status item button to accept dropped files.
private final class DropTargetView: NSView {
    private let onDrop: ([URL]) -> Void
    private let onClick: () -> Void

    init(frame: NSRect, onDrop: @escaping ([URL]) -> Void, onClick: @escaping () -> Void) {
        self.onDrop = onDrop
        self.onClick = onClick
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func mouseDown(with event: NSEvent) { onClick() }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        fileURLs(from: sender).isEmpty ? [] : .copy
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = fileURLs(from: sender)
        guard !urls.isEmpty else { return false }
        onDrop(urls)
        return true
    }

    private func fileURLs(from info: NSDraggingInfo) -> [URL] {
        let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        let urls = info.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: options) as? [URL] ?? []
        return urls.filter { !$0.hasDirectoryPath }
    }
}
