import AppKit

/// The app's menus: the main menu (shown while the Messages window makes Lync a regular app)
/// and the right-click menu on the menu bar icon and the notch.
final class Menus: NSObject {
    private let model: AppModel
    /// Opens the panel. Set by the app delegate.
    var openPanel: () -> Void = {}

    init(model: AppModel) {
        self.model = model
        super.init()
    }

    // MARK: - Main menu

    func installMainMenu() {
        let main = NSMenu()

        let appMenu = NSMenu(title: "Lync")
        appMenu.addItem(item("About Lync", #selector(showAbout)))
        appMenu.addItem(.separator())
        appMenu.addItem(item("Settings…", #selector(showSettings), key: ","))
        appMenu.addItem(.separator())
        appMenu.addItem(NSMenuItem(title: "Hide Lync", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h"))
        let others = NSMenuItem(title: "Hide Others", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
        others.keyEquivalentModifierMask = [.command, .option]
        appMenu.addItem(others)
        appMenu.addItem(NSMenuItem(title: "Show All", action: #selector(NSApplication.unhideAllApplications(_:)), keyEquivalent: ""))
        appMenu.addItem(.separator())
        appMenu.addItem(NSMenuItem(title: "Quit Lync", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        add(appMenu, to: main)

        // Standard selectors with no target go to the first responder, so text fields just work.
        let edit = NSMenu(title: "Edit")
        edit.addItem(NSMenuItem(title: "Undo", action: Selector(("undo:")), keyEquivalent: "z"))
        let redo = NSMenuItem(title: "Redo", action: Selector(("redo:")), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        edit.addItem(redo)
        edit.addItem(.separator())
        edit.addItem(NSMenuItem(title: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x"))
        edit.addItem(NSMenuItem(title: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c"))
        edit.addItem(NSMenuItem(title: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v"))
        edit.addItem(NSMenuItem(title: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a"))
        add(edit, to: main)

        let window = NSMenu(title: "Window")
        window.addItem(NSMenuItem(title: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w"))
        window.addItem(NSMenuItem(title: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m"))
        window.addItem(.separator())
        window.addItem(NSMenuItem(title: "Bring All to Front", action: #selector(NSApplication.arrangeInFront(_:)), keyEquivalent: ""))
        add(window, to: main)
        NSApp.windowsMenu = window

        NSApp.mainMenu = main
    }

    private func add(_ menu: NSMenu, to main: NSMenu) {
        let holder = NSMenuItem(title: menu.title, action: nil, keyEquivalent: "")
        holder.submenu = menu
        main.addItem(holder)
    }

    // MARK: - Right-click menu

    /// For the menu bar icon and the notch. Built fresh each time so it reflects the connection.
    func contextMenu() -> NSMenu {
        let menu = NSMenu()
        let connected = model.connectedPhone != nil
        // Items that need the phone are off: the first line says why.
        if !connected, let phone = model.bluetoothPhone ?? model.pairedPhones.first {
            let state = NSMenuItem(title: model.bluetoothPhone != nil ? "\(phone.name) · over Bluetooth" : "\(phone.name) not connected",
                                   action: nil, keyEquivalent: "")
            state.isEnabled = false
            state.toolTip = Problem.disabledHelp(bluetooth: model.bluetoothPhone != nil)
            menu.addItem(state)
            menu.addItem(.separator())
        }
        // Shows the global shortcut; it works from anywhere, not just this menu.
        let open = item("Open Lync", #selector(openLync), key: "l")
        open.keyEquivalentModifierMask = [.option, .command]
        menu.addItem(open)
        if let code = model.latestCode {
            menu.addItem(item("Copy latest code (\(spaced(code.code)))", #selector(copyLatestCode)))
        }
        menu.addItem(.separator())
        let help = connected ? nil : Problem.disabledHelp(bluetooth: model.bluetoothPhone != nil)
        for entry in [item("Send clipboard", #selector(sendClipboard), enabled: connected),
                      item("Send files…", #selector(sendFiles), enabled: connected)] {
            entry.toolTip = help
            menu.addItem(entry)
        }
        menu.addItem(item("Texts", #selector(openMessages)))
        let ring = item(model.ringing ? "Stop ringing" : "Ring phone", #selector(ringPhone), enabled: connected)
        ring.toolTip = help
        menu.addItem(ring)
        menu.addItem(.separator())
        menu.addItem(item("Add a phone…", #selector(addPhone)))
        menu.addItem(item("Settings…", #selector(showSettings), key: ","))
        menu.addItem(item("About Lync", #selector(showAbout)))
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit Lync", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        return menu
    }

    private func item(_ title: String, _ action: Selector, key: String = "", enabled: Bool = true) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: enabled ? action : nil, keyEquivalent: key)
        item.target = self
        item.isEnabled = enabled
        return item
    }

    // MARK: - Actions

    @objc private func openLync() { openPanel() }
    @objc private func copyLatestCode() { model.copyLatestCode() }
    @objc private func addPhone() {
        openPanel()
        model.showingPairingQR = true
    }
    @objc private func sendClipboard() { model.sendClipboardToPhone() }
    @objc private func sendFiles() { model.chooseFilesToSend() }
    @objc private func openMessages() { model.openMessages() }
    @objc private func ringPhone() { model.ringPhone() }
    @objc private func showSettings() { model.openPanel(.settings) }
    @objc private func showAbout() { model.showAbout() }
}
