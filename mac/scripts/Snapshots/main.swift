// Renders the Mac app's screens to PNGs without a display, using the real views and a fake
// connected phone. Leaves the clipboard as it found it.
//
//   scripts/snapshots.sh <output folder>
import AppKit
import SwiftUI

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "snapshots", isDirectory: true)
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
setenv("WALLHOP_HOME", NSTemporaryDirectory() + "wallhop-snapshots", 1)

// Keep the clipboard as it was.
let board = NSPasteboard.general
let savedItems = (board.pasteboardItems ?? []).map { item -> NSPasteboardItem in
    let copy = NSPasteboardItem()
    for type in item.types { if let data = item.data(forType: type) { copy.setData(data, forType: type) } }
    return copy
}

CTFontManagerRegisterFontsForURL(URL(fileURLWithPath: "Resources/Figtree.ttf") as CFURL, .process, nil)
CTFontManagerRegisterFontsForURL(URL(fileURLWithPath: "Resources/Galindo.ttf") as CFURL, .process, nil)

@MainActor func render<V: View>(_ name: String, _ view: V) {
    let renderer = ImageRenderer(content: view.environment(\.colorScheme, .dark))
    renderer.scale = 2
    guard let image = renderer.nsImage, let tiff = image.tiffRepresentation,
          let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) else {
        print("failed: \(name)"); return
    }
    try? png.write(to: out.appendingPathComponent("\(name).png"))
    print("rendered \(name)")
}

@MainActor func overlay(_ model: AppModel, notch: Bool = true) -> some View {
    let placement = PlacementState()
    placement.placement = notch ? .notch(notchHeight: 32) : .pill
    placement.notchWidth = 190
    return OverlayRoot(model: model, placement: placement)
        .frame(width: 448, height: 220)
        .background(Color(white: 0.55))
        .environmentObject(model.themeStore)
}

@MainActor func run() {
    PanelSnapshots.active = true
    // The phone's lock screen reads 9:41 on Fri 23 Oct 2026, whenever this runs.
    PanelSnapshots.clock = Calendar.current.date(from: DateComponents(year: 2026, month: 10, day: 23, hour: 23, minute: 24))
    // Contrast self check (Theme.swift): every phone hue keeps white text ≥ 4.5:1, codes too.
    let problems = WTheme.contrastProblems()
    if problems.isEmpty { print("contrast check: OK") } else { problems.forEach { print("contrast check FAILED: \($0)") } }
    // A clean start each run: nothing paired, nothing in Recent.
    for name in ["recent.json", "paired-phones.json"] {
        try? FileManager.default.removeItem(atPath: NSTemporaryDirectory() + "wallhop-snapshots/" + name)
    }
    if CommandLine.arguments.contains("--qrlink") {
        let text = "wallhop://pair?v=1&n=MacBook%20Air&h=192.168.0.4&p=47474&k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&t=BBBBBBBBBBBBBBBBBBBBBB"
        for t in [0.0, 0.4, 0.7, 1.0, 1.4, 1.8, 2.2, 2.42, 2.55, 2.75, 3.3, 4.2] {
            render(String(format: "qrlink_%.2f", t), QrLinkingView(qrText: text, phoneName: "Pixel 9", frozenAt: t)
                .frame(width: 400).background(Color.black).foregroundStyle(.white))
        }
        exit(0)
    }
    if CommandLine.arguments.contains("--copy") {
        let store = ThemeStore()
        let row = HStack(spacing: 12) {
            CopyButton(style: PeekPill()) {}
            CopyButton(style: PeekPill(), startCopied: true) {}
            CopyButton(iconOnly: true, style: WRound(size: 30)) {}
            CopyButton(iconOnly: true, style: WRound(size: 30), startCopied: true) {}
            CopyButton(style: WPill(prominent: false, compact: true), startCopied: true) {}
        }
        .padding(20).background(store.theme.card).environmentObject(store)
        render("copy_states", row)
        exit(0)
    }
    let server = LinkServer(identity: Identity.loadOrCreate(), paired: PairedPhones())
    // First launch each run: the panel starts on "Lync lives here".
    UserDefaults.standard.removeObject(forKey: "onboardingHotkeySeen")
    let model = AppModel(server: server)
    let send: (LinkEvent) -> Void = { server.onEvent?($0) }
    let phone = PairedPhone(id: "test", name: "Nothing Phone (3a) Pro", publicKey: Data(count: 32), pairedAt: Date())

    // Not paired yet, first launch: "Lync lives here" teaches ⌥⌘L, then the QR.
    render("1a_onboarding_hotkey", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())
    model.finishHotkeyStep()
    render("1_onboarding", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())

    // A connected phone with a bit of everything.
    server.paired.add(phone)
    send(.pairingEnded(id: UUID()))
    send(.listening(port: 47474))
    // Paired, phone not around yet: the header says it isn't connected and what to do.
    render("1b_home_searching", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())
    send(.connected(phone))
    send(.status(battery: 62, charging: true))
    // Lync's own mint and rose (HomeColors.Default on the phone), not a wallpaper's colours.
    send(.theme(seed: WTheme.defaultSeed, accent: WTheme.defaultAccent))
    send(.media(NowPlaying(app: "YouTube Music", title: "Midnight City", artist: "M83", playing: true), art: nil))
    send(.notification(PhoneNotification(key: "n1", packageName: "com.whatsapp", appName: "WhatsApp", title: "Design team",
                                         text: "Can you share the new home screen?", time: Date(), canReply: true), icon: nil))
    send(.text("Meeting moved to 4:30, see you there", from: phone))
    let folder = URL(fileURLWithPath: NSTemporaryDirectory() + "wallhop-snapshot-files", isDirectory: true)
    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    // Three small photos, each its own colours.
    let photos = [("Sunset", NSColor.systemPink, NSColor.systemIndigo), ("Beach", .systemTeal, .systemYellow),
                  ("Forest", .systemGreen, .systemBrown)].map { name, from, to -> URL in
        let image = NSImage(size: NSSize(width: 300, height: 220))
        image.lockFocus()
        NSGradient(colors: [from, to])!.draw(in: NSRect(x: 0, y: 0, width: 300, height: 220), angle: 35)
        image.unlockFocus()
        let url = folder.appendingPathComponent("\(name).png")
        try? NSBitmapImageRep(data: image.tiffRepresentation!)!.representation(using: .png, properties: [:])!.write(to: url)
        return url
    }
    let photo = photos[0]
    send(.batchStarted(id: "b1", count: photos.count, bytes: 3000))
    for url in photos { send(.fileSaved(batch: "b1", url: url)) }
    send(.batchFinished(id: "b1"))
    // A large send halfway across, for the receiving card.
    send(.batchStarted(id: "b3", count: 1, bytes: 1_800_000_000))
    send(.fileSaved(batch: "b3", url: photo))
    send(.batchProgress(id: "b3", bytes: 860_000_000))
    send(.otp(code: "482913", sender: "HDFC Bank", app: "SMS"))

    render("2_home_notch", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())
    render("3_home_menubar", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.panelPage = .settings
    render("4_settings", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.confirmingForget = phone.id
    render("4b_settings_forget", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.confirmingForget = nil
    model.panelPage = .addPhone
    render("4c_add_phone", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.panelPage = .home

    // Files held over the open panel.
    model.dropHover(true, source: "panel")
    render("3b_panel_drop", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.dropHover(false, source: "panel")

    // Feedback while the panel is open: a toast at the bottom (one with Undo).
    model.panelOpen = true
    model.feedback("Clipboard sent to your phone")
    render("8_toast", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.showToast(Toast(symbol: "bell.slash", message: "Cleared 1 notification", actionTitle: "Undo") {})
    render("8b_toast_undo", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    // A problem: why, what to do, and the button that does it, on two lines.
    let storage = Problem.sendFailed(.refused(PhoneError.storageFull))
    model.report(Problem(title: storage.title, detail: storage.detail).with("Try again") {})
    render("8c_toast_problem", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.closePanel()

    // Messages with nothing in it, and with a search that finds nothing.
    let empty = VStack(spacing: 0) {
        MessagesEmptyState(symbol: "message", title: "No conversations yet",
                           text: "Texts on your phone show up here. Start one with the pencil above.")
        MessagesEmptyState(symbol: "magnifyingglass", title: "No results", text: "Nothing matches \u{201C}Anna\u{201D}.")
    }
    .frame(width: 280).padding(.vertical, 20).background(WTheme.ink).foregroundStyle(.white).environmentObject(model.themeStore)
    render("9_messages_empty", empty)

    // A transfer cut off by a disconnect.
    send(.batchStarted(id: "b2", count: 3, bytes: 3000))
    send(.fileSaved(batch: "b2", url: photo))
    send(.batchInterrupted(id: "b2"))

    // A phone asking to pair, and a ringing call.
    let request = PairingRequest(id: UUID(), phoneName: phone.name, code: "305718", decide: { _ in })
    send(.pairingRequested(request))
    send(.call(PhoneCall(id: "c1", state: .ringing, number: "+44 7700 900123", name: "mi amor ❤️", sim: "SIM 1")))

    // Contacts search under "To:" in Texts, and the phone saying it can't search.
    let texts = MessagesModel(server: server)
    texts.showContactResults([ContactMatch(name: "Anna Lindqvist", number: "+46 70 123 45 67"),
                              ContactMatch(name: "Anna (work)", number: "+46 8 555 010 20"),
                              ContactMatch(name: "Annabel Ortiz", number: "+1 415 555 0134")])
    render("9b_contacts_search", ContactResults(model: texts, highlighted: .constant(1)) { _ in }
        .frame(width: 520).padding(.vertical, 12).background(WTheme.ink).foregroundStyle(.white).environmentObject(model.themeStore))
    texts.showContactResults([], unavailable: true)
    render("9c_contacts_unavailable", ContactResults(model: texts, highlighted: .constant(nil)) { _ in }
        .frame(width: 520).padding(.vertical, 12).background(WTheme.ink).foregroundStyle(.white).environmentObject(model.themeStore))

    // Live moments, drawn the way the notch shows them.
    for (name, card) in [("7c_live_pairing", LiveCard.pairing(request.id)),
                         ("7d_live_call", LiveCard.call("c1")),
                         ("7e_live_notification", LiveCard.notification(key: "n1")),("5_live_code", LiveCard.otp(code: "482913", sender: "HDFC Bank")),
                         ("6_live_files", LiveCard.added(batchId: "b1")),
                         ("6b_live_receiving", LiveCard.receiving(batchId: "b3")),
                         ("7_live_text", LiveCard.text("Meeting moved to 4:30")),
                         ("7b_live_interrupted", LiveCard.added(batchId: "b2")),
                         ("7g_live_problem", LiveCard.problem({
                             let text = Problem.partlySent(sent: 2, total: 3, firstFailedName: "Report.pdf", failure: .disconnected)
                             return Problem(symbol: "iphone.slash", title: text.title, detail: text.detail).with("Try again") {}
                         }()))] {
        let content = LiveCardView(model: model, card: card)
            .frame(width: 364, alignment: .leading)
            .padding(.top, 42).padding(.horizontal, 18).padding(.bottom, 16)
            .background(NotchShape(cornerRadius: 26).fill(LinearGradient(
                stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.45), .init(color: model.themeStore.theme.card, location: 1)],
                startPoint: .top, endPoint: .bottom)))
            .padding(.horizontal, 12)
            .environmentObject(model.themeStore)
            .foregroundStyle(.white)
        render(name, content)
    }
    // The pairing request and the call from the cards above are done with.
    model.decidePairing(false)
    send(.call(PhoneCall(id: "c1", state: .ended, number: nil, name: nil, sim: nil)))
    // First run: where Lync lives.
    model.show(.info(symbol: "laptopcomputer", title: "Lync lives in the notch — click it", subtitle: "Or press ⌥⌘L from anywhere"), for: nil)
    render("10c_overlay_first_run_hint", overlay(model))
    model.hideLive()
    model.hideLive()
    // Quiet moments.
    model.show(.quiet(symbol: "iphone.radiowaves.left.and.right", text: "\(phone.name) connected"), for: nil)
    render("10d_overlay_quiet_connected", overlay(model))
    model.hideLive()
    // Just paired (Pair on the card, or the QR): the link catches, "Linked with …".
    model.show(.quiet(symbol: LinkCatchView.symbol, text: "Linked with \(phone.name)"), for: nil)
    render("7f_live_linked", overlay(model))
    model.hideLive()
    // Files dragged somewhere on the Mac: nothing until they head for the notch, then a faint
    // glow, a stronger one with the label close up, then the drop zone at the notch.
    model.fileDragBegan(files: [photo, photo, photo])
    model.fileDragMoved(approach: 0.3, near: false)
    render("10e_overlay_drag_glow_far", overlay(model))
    model.fileDragMoved(approach: 0.8, near: false)
    render("10e_overlay_drag_glow", overlay(model))
    render("10f_overlay_drag_glow_pill", overlay(model, notch: false))
    // The glow takes the phone's colours: a purple and blue wallpaper.
    model.themeStore.update(seed: 0xFF7B4FD6, accent: 0xFF3D7BE0)
    render("10e_overlay_drag_glow_purple", overlay(model))
    // Rose with a green accent (opposites): the glow keeps to rose and its neighbours.
    model.themeStore.update(seed: 0xFFC2185B, accent: 0xFF43A047)
    render("10e_overlay_drag_glow_rose", overlay(model))
    model.themeStore.update(seed: WTheme.defaultSeed, accent: WTheme.defaultAccent)
    model.fileDragMoved(approach: 1, near: true)
    render("10g_overlay_drop_zone", overlay(model))
    model.fileDragMoved(approach: 0, near: false)
    model.fileDragEnded()
    // Menu bar mode: the same moments drop down from the menu bar icon.
    let menuBar = PlacementState()
    menuBar.placement = .menuBar
    model.fileDragBegan(files: [photo])
    model.fileDragMoved(approach: 1, near: true)
    render("10i_menubar_drop_zone", OverlayRoot(model: model, placement: menuBar).frame(width: 448, height: 220)
        .background(Color(white: 0.55)).environmentObject(model.themeStore))
    model.fileDragMoved(approach: 0, near: false)
    model.fileDragEnded()
    model.hideLive()
    // The phone went away: the panel says so.
    send(.disconnected(phone))
    render("10h_home_offline", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())
    // Still reachable over Bluetooth only: what works, and what Wi‑Fi would add. (Recent
    // cleared first, so the header isn't cut off: snapshots don't scroll.)
    model.clearActivity()
    model.closePanel()
    send(.bluetooth(phone))
    render("10j_home_bluetooth", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())
    send(.bluetooth(nil))
    // Bluetooth off on this Mac: a second note under "isn't connected".
    send(.bluetoothRadio(.off))
    render("10k_home_bluetooth_off", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())
    send(.bluetoothRadio(.ready))
    send(.connected(phone))
    send(.call(PhoneCall(id: "c1", state: .ringing, number: "+44 7700 900123", name: "mi amor ❤️", sim: "SIM 1")))

    // The real overlay: a code that arrived during the call waits, then shows once the call ends.
    model.show(.call("c1"), for: nil)
    send(.otp(code: "550127", sender: "Bank", app: "SMS"))
    render("10a_overlay_call_with_code_waiting", overlay(model))
    send(.call(PhoneCall(id: "c1", state: .ended, number: nil, name: nil, sim: nil)))
    // Queued cards show a beat later; render then.
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { MainActor.assumeIsolated {
    print("queued code shown after call: \(model.live == .otp(code: "550127", sender: "Bank")) live=\(String(describing: model.live))")
    // Saving moves the files from the inbox into Downloads; only in the temp copy here.
    board.clearContents()
    board.writeObjects(savedItems)
    print("clipboard restored")
    exit(0)
    } }
}

DispatchQueue.main.async { MainActor.assumeIsolated { run() } }
dispatchMain()
