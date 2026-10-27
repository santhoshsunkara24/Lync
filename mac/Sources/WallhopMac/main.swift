import Network
import AppKit
import Combine

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var model: AppModel!
    private var statusItem: StatusItemController!
    private var overlay: OverlayController!
    private var messages: MessagesWindowController!
    private var panel: PanelController!
    private var sensor: NotchSensor!
    private var menus: Menus!
    private var hotKey: HotKey?
    private var dragWatcher: FileDragWatcher?
    private var observers: [Any] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu bar app: no Dock icon, no main window.
        NSApp.setActivationPolicy(.accessory)
        WFont.register()

        let server = LinkServer(identity: Identity.loadOrCreate(), paired: PairedPhones())
        model = AppModel(server: server)
        menus = Menus(model: model)
        // Shown whenever Lync is the active app (e.g. with the Messages window open): Edit
        // commands for text fields, ⌘, for Settings, ⌘W and ⌘Q.
        menus.installMainMenu()
        statusItem = StatusItemController(model: model) { [weak self] in self?.menus.contextMenu() ?? NSMenu() }
        overlay = OverlayController(model: model) { [weak self] in self?.statusItem.visibleFrame }
        messages = MessagesWindowController(server: server, appModel: model)
        model.openMessages = { [weak self] in self?.model.closePanel(); self?.messages.show() }
        panel = PanelController(model: model) { [weak self] in self?.statusItem.visibleFrame }
        statusItem.onToggle = { [weak self] in self?.panel.toggle() }
        menus.openPanel = { [weak self] in self?.panel.open(byHover: false) }
        sensor = NotchSensor(
            onHover: { [weak self] in self?.panel.open(byHover: true) },
            onClick: { [weak self] in self?.panel.toggle() },
            drop: FileDropHandler(model: model, source: "sensor"),
            menu: { [weak self] in self?.menus.contextMenu() ?? NSMenu() }
        )
        let applyMode = { [weak self] in
            guard let self else { return }
            let notch = self.model.hasNotch
            self.model.noteScreens()
            self.statusItem.update(mode: self.model.displayMode, hasNotch: notch)
            self.sensor.update(active: self.model.displayMode == .notch && notch)
        }
        applyMode()
        observers.append(model.$displayMode.dropFirst().sink { _ in DispatchQueue.main.async { applyMode() } })
        observers.append(NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { _ in applyMode() })
        // Development (WALLHOP_CAPTURE=1 only): drive the panel for screenshots, e.g.
        // `distnoted`-style: post "app.wallhop.debug" with object open | close | settings | notch | menubar
        // | save-latest | send-clipboard | send-files:<path>[|<path>…].
        if DevEnv.on("WALLHOP_CAPTURE") {
            observers.append(DistributedNotificationCenter.default().addObserver(
                forName: .init("app.wallhop.debug"), object: nil, queue: .main) { [weak self] note in
                guard let self else { return }
                let command = note.object as? String ?? ""
                // "send-files:/a/b.png|/c/d.txt": the same path as files dropped on the notch or icon.
                if command.hasPrefix("send-files:") {
                    let urls = command.dropFirst("send-files:".count).split(separator: "|").map { URL(fileURLWithPath: String($0)) }
                    let files = urls.filter { !$0.hasDirectoryPath }
                    NSLog("Lync: debug send-files \(files.count)")
                    self.model.dropAccepted()
                    self.model.sendFilesToPhone(files, skippedFolders: urls.count - files.count)
                    return
                }
                switch command {
                case "send-clipboard": self.model.sendClipboardToPhone()
                case "open": self.panel.open(byHover: false)
                case "close": self.model.closePanel()
                case "settings": self.model.openPanel(.settings)
                case "notch": self.model.displayMode = .notch
                case "menubar": self.model.displayMode = .menuBar
                case "save-latest":
                    if let item = self.model.activity.first(where: { if case .files = $0.payload { return true }; return false }) {
                        self.model.saveFiles(item.id)
                    }
                default: break
                }
            })
        }
        // Development: WALLHOP_OPEN_PANEL=1 opens the panel at launch.
        if DevEnv.on("WALLHOP_OPEN_PANEL") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.panel.open(byHover: false) }
        }
        // ⌥⌘L from anywhere: gives a card with buttons keyboard focus, otherwise opens or closes the panel.
        // First launch: while "Lync lives here" teaches it, pressing it is the step done; the
        // panel stays (or opens) on the QR instead of closing.
        hotKey = HotKey { [weak self] in
            guard let self else { return }
            if self.model.hotkeyStepPending {
                self.model.finishHotkeyStep(viaShortcut: true)
                if !self.model.panelOpen { self.panel.open(byHover: false, fromKeyboard: true) }
                return
            }
            if !self.overlay.focusCard() { self.panel.toggle(fromKeyboard: true) }
        }
        // Another app has ⌥⌘L: don't teach it, and Settings says so.
        if hotKey?.registered == false { model.hotkeyUnavailable() }
        // Files dragged anywhere on the Mac: the notch (or menu bar icon) offers to take them.
        dragWatcher = FileDragWatcher(model: model) { [weak self] in
            guard let self else { return nil }
            return OverlayController.dropTarget(model: self.model, statusFrame: self.statusItem.visibleFrame)
        }
        server.start()
        // First run: unpaired, the panel opens by itself with the pairing steps; until the panel
        // has been opened once, a short hint says where Lync lives.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            guard let self, !DevEnv.on("WALLHOP_OPEN_PANEL") else { return }
            self.model.launched { self.panel.open(byHover: false, user: false) }
        }

        // Re-announce on wake so the phone finds this Mac again within seconds.
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { _ in server.refreshAdvertising() }
        // And whenever the Mac's network changes (another Wi-Fi, Ethernet, back from a VPN):
        // the phone's search sees the new address within seconds instead of waiting it out.
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // The panel says "This Mac is offline" instead of waiting for the phone forever.
            let online = path.status == .satisfied
            DispatchQueue.main.async { self?.model.setOnline(online) }
            guard online else { return }
            DispatchQueue.main.async {
                networkRefresh?.cancel()
                let work = DispatchWorkItem { server.refreshAdvertising() }
                networkRefresh = work
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "app.wallhop.path"))
    }

    /// Opening Lync again (Finder, Spotlight, Launchpad, `open`) while it runs opens the panel.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        panel.open(byHover: false)
        return false
    }
}

private let pathMonitor = NWPathMonitor()
private var networkRefresh: DispatchWorkItem?

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
