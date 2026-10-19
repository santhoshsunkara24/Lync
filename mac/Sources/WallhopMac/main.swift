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
    private var observers: [Any] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu bar app: no Dock icon, no main window.
        NSApp.setActivationPolicy(.accessory)
        WFont.register()

        let server = LinkServer(identity: Identity.loadOrCreate(), paired: PairedPhones())
        model = AppModel(server: server)
        statusItem = StatusItemController(model: model)
        overlay = OverlayController(model: model) { [weak self] in self?.statusItem.visibleFrame }
        messages = MessagesWindowController(server: server, appModel: model)
        model.openMessages = { [weak self] in self?.model.closePanel(); self?.messages.show() }
        panel = PanelController(model: model) { [weak self] in self?.statusItem.visibleFrame }
        statusItem.onToggle = { [weak self] in self?.panel.toggle() }
        sensor = NotchSensor(
            onHover: { [weak self] in self?.panel.open(byHover: true) },
            onClick: { [weak self] in self?.panel.toggle() },
            onDrop: { [weak self] urls in self?.model.sendFilesToPhone(urls) }
        )
        let applyMode = { [weak self] in
            guard let self else { return }
            let notch = self.model.hasNotch
            self.statusItem.update(mode: self.model.displayMode, hasNotch: notch)
            self.sensor.update(active: self.model.displayMode == .notch && notch)
        }
        applyMode()
        observers.append(model.$displayMode.dropFirst().sink { _ in DispatchQueue.main.async { applyMode() } })
        observers.append(NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { _ in applyMode() })
        // Development (WALLHOP_CAPTURE=1 only): drive the panel for screenshots, e.g.
        // `distnoted`-style: post "app.wallhop.debug" with object open | close | settings | notch | menubar.
        if ProcessInfo.processInfo.environment["WALLHOP_CAPTURE"] == "1" {
            observers.append(DistributedNotificationCenter.default().addObserver(
                forName: .init("app.wallhop.debug"), object: nil, queue: .main) { [weak self] note in
                guard let self else { return }
                switch note.object as? String {
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
        if ProcessInfo.processInfo.environment["WALLHOP_OPEN_PANEL"] == "1" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.panel.open(byHover: false) }
        }
        server.start()

        // Re-announce on wake so the phone finds this Mac again within seconds.
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { _ in server.refreshAdvertising() }
        // And whenever the Mac's network changes (another Wi-Fi, Ethernet, back from a VPN):
        // the phone's search sees the new address within seconds instead of waiting it out.
        pathMonitor.pathUpdateHandler = { path in
            guard path.status == .satisfied else { return }
            DispatchQueue.main.async {
                networkRefresh?.cancel()
                let work = DispatchWorkItem { server.refreshAdvertising() }
                networkRefresh = work
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "app.wallhop.path"))
    }
}

private let pathMonitor = NWPathMonitor()
private var networkRefresh: DispatchWorkItem?

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
