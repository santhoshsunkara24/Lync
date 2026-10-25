import AppKit
import Carbon.HIToolbox

/// A system-wide keyboard shortcut (⌥⌘L opens Lync). Carbon's RegisterEventHotKey needs no
/// Accessibility permission, unlike a global key monitor.
final class HotKey {
    private var hotKey: EventHotKeyRef?
    private var handler: EventHandlerRef?
    private let action: () -> Void
    /// False when another app already has the shortcut.
    private(set) var registered = false

    init(keyCode: Int = kVK_ANSI_L, modifiers: Int = optionKey | cmdKey, action: @escaping () -> Void) {
        self.action = action
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let context = Unmanaged.passUnretained(self).toOpaque()
        InstallEventHandler(GetApplicationEventTarget(), { _, _, context in
            guard let context else { return OSStatus(eventNotHandledErr) }
            let hotKey = Unmanaged<HotKey>.fromOpaque(context).takeUnretainedValue()
            DispatchQueue.main.async { hotKey.action() }
            return noErr
        }, 1, &spec, context, &handler)
        let id = EventHotKeyID(signature: OSType(0x4C59_4E43), id: 1) // "LYNC"
        let status = RegisterEventHotKey(UInt32(keyCode), UInt32(modifiers), id, GetApplicationEventTarget(), 0, &hotKey)
        registered = status == noErr
        if !registered { NSLog("Lync: couldn't register ⌥⌘L (\(status)); another app may use it") }
    }

    deinit {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        if let handler { RemoveEventHandler(handler) }
    }

    /// How the shortcut is shown in menus and Settings.
    static let display = "⌥⌘L"
}

/// Notices files being dragged anywhere on the Mac (Finder, the Desktop, other apps), so the
/// notch or the menu bar icon can offer to take them. Global mouse monitors need no
/// Accessibility permission. The drag pasteboard is read once per drag: when its change count
/// moves after a mouse down, a drag-and-drop has started.
final class FileDragWatcher {
    private let model: AppModel
    /// Where the pointer opens the drop zone (around the notch or the menu bar icon).
    private let target: () -> NSRect?
    private var monitors: [Any] = []
    private var changeCountAtDown = 0
    private var inspected = true
    private var lastCheck = Date.distantPast
    /// While a file drag is on: follows the pointer and notices the drag ending (the mouse up
    /// can be swallowed by the drag session of the app it started in).
    private var timer: Timer?

    init(model: AppModel, target: @escaping () -> NSRect?) {
        self.model = model
        self.target = target
        if let down = NSEvent.addGlobalMonitorForEvents(matching: .leftMouseDown, handler: { [weak self] _ in
            guard let self else { return }
            self.changeCountAtDown = NSPasteboard(name: .drag).changeCount
            self.inspected = false
        }) { monitors.append(down) }
        if let dragged = NSEvent.addGlobalMonitorForEvents(matching: .leftMouseDragged, handler: { [weak self] _ in
            self?.dragged()
        }) { monitors.append(dragged) }
        if let up = NSEvent.addGlobalMonitorForEvents(matching: .leftMouseUp, handler: { [weak self] _ in
            self?.inspected = true
            self?.end()
        }) { monitors.append(up) }
    }

    deinit {
        monitors.forEach(NSEvent.removeMonitor)
        timer?.invalidate()
    }

    private func dragged() {
        guard !inspected else { return }
        // Window moves and text selections drag too; checking ten times a second is plenty.
        let now = Date()
        guard now.timeIntervalSince(lastCheck) > 0.1 else { return }
        lastCheck = now
        let board = NSPasteboard(name: .drag)
        guard board.changeCount != changeCountAtDown else { return }
        inspected = true
        guard model.connectedPhone != nil else { return }
        let files = FileDropHandler.read(board).files
        guard !files.isEmpty else { return }
        model.fileDragBegan(files: files)
        guard model.fileDrag != nil else { return }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in self?.follow() }
        follow()
    }

    private func follow() {
        guard NSEvent.pressedMouseButtons & 1 != 0, model.fileDrag != nil else {
            end()
            return
        }
        model.fileDragNear(target()?.contains(NSEvent.mouseLocation) ?? false)
    }

    private func end() {
        guard timer != nil else { return }
        timer?.invalidate()
        timer = nil
        model.fileDragEnded()
    }
}
