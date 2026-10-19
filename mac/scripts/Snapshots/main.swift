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

@MainActor func run() {
    PanelSnapshots.active = true
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
    let model = AppModel(server: server)
    let send: (LinkEvent) -> Void = { server.onEvent?($0) }
    let phone = PairedPhone(id: "test", name: "Nothing Phone (3a) Pro", publicKey: Data(count: 32), pairedAt: Date())

    // Not paired yet: onboarding with the QR.
    render("1_onboarding", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())

    // A connected phone with a bit of everything.
    server.paired.add(phone)
    send(.pairingEnded(id: UUID()))
    send(.connected(phone, host: "192.168.0.12"))
    send(.status(battery: 76, charging: true))
    send(.theme(seed: 0xFF6B4EFF, accent: 0xFFE8505B))
    send(.media(NowPlaying(app: "YouTube Music", title: "Midnight City", artist: "M83", playing: true), art: nil))
    send(.notification(PhoneNotification(key: "n1", packageName: "com.whatsapp", appName: "WhatsApp", title: "Design team",
                                         text: "Can you share the new home screen?", time: Date(), canReply: true), icon: nil))
    send(.text("Meeting moved to 4:30, see you there", from: phone))
    let folder = URL(fileURLWithPath: NSTemporaryDirectory() + "wallhop-snapshot-files", isDirectory: true)
    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let image = NSImage(size: NSSize(width: 300, height: 220))
    image.lockFocus()
    NSGradient(colors: [.systemPink, .systemIndigo])!.draw(in: NSRect(x: 0, y: 0, width: 300, height: 220), angle: 35)
    image.unlockFocus()
    let photo = folder.appendingPathComponent("Sunset.png")
    try? NSBitmapImageRep(data: image.tiffRepresentation!)!.representation(using: .png, properties: [:])!.write(to: photo)
    send(.batchStarted(id: "b1", count: 1, bytes: 1000))
    send(.fileSaved(batch: "b1", url: photo))
    send(.batchFinished(id: "b1"))
    send(.otp(code: "731942", sender: "HDFC Bank", app: "SMS"))

    render("2_home_notch", PanelView(model: model, theme: model.themeStore, fromNotch: true).fixedSize())
    render("3_home_menubar", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.panelPage = .settings
    render("4_settings", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.confirmingForget = phone.id
    render("4b_settings_forget", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.confirmingForget = nil
    model.panelPage = .home

    // Feedback while the panel is open: a toast at the bottom (one with Undo).
    model.panelOpen = true
    model.feedback("Clipboard sent to your phone")
    render("8_toast", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.showToast(Toast(symbol: "bell.slash", message: "Cleared 1 notification", actionTitle: "Undo") {})
    render("8b_toast_undo", PanelView(model: model, theme: model.themeStore, fromNotch: false).fixedSize())
    model.panelOpen = false

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

    // Live moments, drawn the way the notch shows them.
    for (name, card) in [("5_live_code", LiveCard.otp(code: "731942", sender: "HDFC Bank")),
                         ("6_live_files", LiveCard.added(batchId: "b1")),
                         ("7_live_text", LiveCard.text("Meeting moved to 4:30")),
                         ("7b_live_interrupted", LiveCard.added(batchId: "b2"))] {
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
    // Saving moves the files from the inbox into Downloads; only in the temp copy here.
    board.clearContents()
    board.writeObjects(savedItems)
    print("clipboard restored")
    exit(0)
}

DispatchQueue.main.async { MainActor.assumeIsolated { run() } }
dispatchMain()
