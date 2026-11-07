// A stand-in phone that hammers a test copy of the Mac app for a while: it pairs once (run the
// Mac app with WALLHOP_AUTO_PAIR=1), then keeps connecting, sending everything a phone sends
// (codes, texts, notifications with icons, calls, media with artwork, files small to large,
// cancelled and cut-off transfers) and dropping the connection, sometimes mid-file. Meanwhile
// a second thread throws rubbish at the port: garbage bytes, huge length prefixes, bad
// handshakes, idle connections and floods past the per-host cap. Every session must still open
// within a few seconds; anything that doesn't is counted as a failure.
//
// The phone connects to <phone host> (the Mac's own network address, say) and the rubbish comes
// from 127.0.0.1, so they count as different devices, as a real phone and an attacker would.
//
//   scripts/stress-phone.sh <port> <minutes> [phone host]
import AppKit
import CryptoKit
import Foundation

let args = CommandLine.arguments
let junkHost = "127.0.0.1"
let phoneHost = args.count > 3 ? args[3] : junkHost
let port = UInt16(args.count > 1 ? args[1] : "47999") ?? 47999
let minutes = Double(args.count > 2 ? args[2] : "30") ?? 30
let deadline = Date().addingTimeInterval(minutes * 60)

let logLock = NSLock()
func log(_ s: String) {
    logLock.lock(); defer { logLock.unlock() }
    let t = ISO8601DateFormatter().string(from: Date()).suffix(9)
    print("[stress \(t)] \(s)"); fflush(stdout)
}

final class Stats {
    private let lock = NSLock()
    private var counts: [String: Int] = [:]
    func add(_ key: String, _ n: Int = 1) { lock.lock(); counts[key, default: 0] += n; lock.unlock() }
    func snapshot() -> [String: Int] { lock.lock(); defer { lock.unlock() }; return counts }
}
let stats = Stats()

// MARK: Socket

enum Closed: Error { case closed }
struct Failed: Error, CustomStringConvertible { let description: String; init(_ d: String) { description = d } }

final class Socket {
    let fd: Int32
    init?(timeout: Int = 10, host: String = phoneHost) {
        fd = socket(AF_INET, SOCK_STREAM, 0)
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: timeout, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        inet_pton(AF_INET, host, &addr.sin_addr)
        let r = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        if r != 0 { close(fd); return nil }
    }
    func write(_ data: Data) throws {
        try data.withUnsafeBytes { raw in
            var off = 0
            while off < data.count {
                let n = Darwin.write(fd, raw.baseAddress! + off, data.count - off)
                if n <= 0 { throw Closed.closed }
                off += n
            }
        }
    }
    func read(_ n: Int) throws -> Data {
        var out = Data(capacity: n)
        var buf = [UInt8](repeating: 0, count: min(n, 65536))
        while out.count < n {
            let r = Darwin.read(fd, &buf, min(buf.count, n - out.count))
            if r <= 0 { throw Closed.closed }
            out.append(buf, count: r)
        }
        return out
    }
    func frame() throws -> Data {
        let len = try read(4).reduce(0) { ($0 << 8) | Int($1) }
        return try read(len)
    }
    func shut() { Darwin.shutdown(fd, SHUT_RDWR); close(fd) }
}

func json(_ s: Socket, _ o: [String: Any]) throws { try s.write(Framing.encode(try HandshakeJSON.encode(o))) }
func readJSON(_ s: Socket) throws -> [String: Any] { HandshakeJSON.decode(try s.frame()) ?? [:] }

// MARK: Pair once

let me = Curve25519.KeyAgreement.PrivateKey()
let myPub = me.publicKey.rawRepresentation
var macPub = Data()
do {
    guard let s = Socket() else { log("can't reach the Mac on \(port)"); exit(1) }
    let nonce = Handshake.randomBytes(16)!
    try json(s, ["t": "pair", "v": 3, "name": "Stress Phone", "pub": myPub.base64EncodedString(),
                 "commit": Handshake.pairingCommit(phonePub: myPub, phoneNonce: nonce).base64EncodedString()])
    let wait = try readJSON(s)
    guard wait["t"] as? String == "pair_wait", let pub = (wait["pub"] as? String).flatMap({ Data(base64Encoded: $0) }) else {
        log("pairing: \(wait)"); exit(3)
    }
    macPub = pub
    try json(s, ["t": "pair_nonce", "nonce": nonce.base64EncodedString()])
    guard try readJSON(s)["t"] as? String == "pair_ok" else { log("not paired"); exit(4) }
    s.shut()
    log("paired")
} catch { log("pairing failed: \(error)"); exit(4) }

// MARK: A session

final class Session {
    let socket: Socket
    let channel: SecureChannel
    private let sendLock = NSLock()
    private(set) var alive = true

    init(socket: Socket, channel: SecureChannel) { self.socket = socket; self.channel = channel }

    static func open() throws -> Session {
        let started = Date()
        guard let s = Socket() else { throw Failed("can't connect") }
        let noise = try Noise.Handshake(role: .initiator, prologue: Handshake.tcpPrologue, staticKey: me, remoteStatic: macPub)
        let about = try JSONSerialization.data(withJSONObject: ["name": "Stress Phone", "app": "stress"])
        try json(s, ["t": "hello", "v": 3, "noise": try noise.writeMessage(payload: about).base64EncodedString()])
        let ok: [String: Any]
        do { ok = try readJSON(s) } catch { s.shut(); throw Failed("no hello_ok after \(Int(Date().timeIntervalSince(started) * 1000)) ms") }
        guard ok["t"] as? String == "hello_ok", let reply = (ok["noise"] as? String).flatMap({ Data(base64Encoded: $0) }) else {
            s.shut(); throw Failed("hello answered with \(ok)")
        }
        _ = try noise.readMessage(reply)
        let session = Session(socket: s, channel: SecureChannel(try noise.split()))
        try session.send(Message("ready"))
        let ready: Message
        do { ready = try session.receive() } catch { s.shut(); throw Failed("no ready after \(Int(Date().timeIntervalSince(started) * 1000)) ms") }
        guard ready.type == "ready" else { s.shut(); throw Failed("got \(ready.type) instead of ready") }
        // The 10 s limit is for the handshake only: a quiet Mac in a session is normal.
        var none = timeval(tv_sec: 0, tv_usec: 0)
        setsockopt(s.fd, SOL_SOCKET, SO_RCVTIMEO, &none, socklen_t(MemoryLayout<timeval>.size))
        stats.add("sessions")
        let ms = Int(Date().timeIntervalSince(started) * 1000)
        if ms > 2000 { log("slow session open: \(ms) ms"); stats.add("slow opens") }
        Thread.detachNewThread { session.pump() }
        return session
    }

    func send(_ m: Message) throws {
        sendLock.lock(); defer { sendLock.unlock() }
        guard alive else { throw Closed.closed }
        try socket.write(Framing.encode(try channel.seal(try m.encoded())))
    }

    func receive() throws -> Message { try Message.decode(try channel.open(try socket.frame())) }

    /// Answers pings and acks whatever the Mac sends, until the connection goes.
    private func pump() {
        while alive {
            let m: Message
            do { m = try receive() } catch {
                if alive { log("the Mac ended the session: \(error)"); stats.add("FAILED sessions (Mac hung up)") }
                alive = false; break
            }
            stats.add("mac → \(m.type)")
            switch m.type {
            case "ping": try? send(Message("pong"))
            case "file_end": try? send(Message("ack", ["id": m.string("id") ?? "", "ok": true]))
            default: break
            }
        }
    }

    func drop() { alive = false; socket.shut() }
}

// MARK: Things a phone sends

func rnd(_ r: ClosedRange<Int>) -> Int { Int.random(in: r) }
func uuid() -> String { UUID().uuidString }

let texts = [
    "Meeting moved to 4:30, see you there",
    String(repeating: "A very long message that goes on and on. ", count: 200),
    "Emoji 🎉🔥👨‍👩‍👧‍👦🏳️‍🌈 and RTL مرحبا بالعالم and CJK 你好世界",
    "", "\u{0000}\u{FEFF}\u{202E}control characters",
    "https://example.com/a/really/long/link?with=query&and=more#fragment",
]
let apps = [("com.whatsapp", "WhatsApp"), ("com.google.android.gm", "Gmail"), ("com.slack", "Slack"), ("org.telegram.messenger", "Telegram")]

func icon(_ size: Int, _ color: NSColor) -> Data {
    let image = NSImage(size: NSSize(width: size, height: size))
    image.lockFocus(); color.setFill(); NSBezierPath(ovalIn: NSRect(x: 0, y: 0, width: size, height: size)).fill(); image.unlockFocus()
    return NSBitmapImageRep(data: image.tiffRepresentation!)!.representation(using: .png, properties: [:])!
}
let iconData = icon(96, .systemGreen)
let artData: Data = {
    let image = NSImage(size: NSSize(width: 600, height: 600))
    image.lockFocus()
    NSGradient(colors: [.systemPink, .systemIndigo])!.draw(in: NSRect(x: 0, y: 0, width: 600, height: 600), angle: 35)
    image.unlockFocus()
    return NSBitmapImageRep(data: image.tiffRepresentation!)!.representation(using: .jpeg, properties: [.compressionFactor: 0.8])!
}()

func sendFiles(_ s: Session, cutOff: Bool, cancel: Bool) throws {
    let count = rnd(1...4)
    let files: [(String, Data)] = (0..<count).map { i in
        let size = [rnd(10...2_000), rnd(10_000...300_000), rnd(1_000_000...8_000_000)].randomElement()!
        if i == 0, Bool.random() { return ("Photo \(rnd(1...999)).png", icon(rnd(200...1200), .systemIndigo)) }
        var bytes = Data(count: size)
        bytes.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, size, $0.baseAddress!) }
        return (["Report.pdf", "Notes.txt", "Clip.mp4", "Archive.zip", "名前.txt", "a/../../etc.txt"].randomElement()!, bytes)
    }
    let batch = uuid()
    try s.send(Message("batch_start", ["batch": batch, "count": files.count, "bytes": files.reduce(0) { $0 + $1.1.count }]))
    for (n, (name, data)) in files.enumerated() {
        let id = uuid()
        try s.send(Message("file_start", ["id": id, "batch": batch, "name": name, "size": data.count]))
        var offset = 0
        while offset < data.count {
            let end = min(offset + 64 * 1024, data.count)
            try s.send(Message("file_chunk", ["id": id, "batch": batch], body: data.subdata(in: offset..<end)))
            offset = end
            if n == files.count - 1, offset > data.count / 2 {
                if cutOff { stats.add("cut-off transfers"); s.drop(); return }
                if cancel { try s.send(Message("batch_cancel", ["batch": batch])); stats.add("cancelled batches"); return }
            }
        }
        try s.send(Message("file_end", ["id": id, "batch": batch, "sha256": Data(SHA256.hash(data: data)).hex]))
        stats.add("files", 1); stats.add("file bytes", data.count)
    }
    try s.send(Message("batch_end", ["batch": batch]))
    stats.add("batches")
}

func call(_ s: Session) throws {
    let id = uuid()
    let who: [String: Any] = ["id": id, "number": "+91 98\(rnd(10_000_000...99_999_999))",
                              "name": ["Mom", "Unknown", "A Very Long Contact Name That Should Truncate Nicely"].randomElement()!]
    try s.send(Message("call", who.merging(["state": "ringing"]) { $1 }))
    Thread.sleep(forTimeInterval: Double(rnd(1...4)))
    if Bool.random() {
        try s.send(Message("call", who.merging(["state": "active"]) { $1 }))
        Thread.sleep(forTimeInterval: Double(rnd(1...6)))
        try s.send(Message("call", who.merging(["state": "ended"]) { $1 }))
    } else {
        try s.send(Message("call", who.merging(["state": "missed"]) { $1 }))
    }
    stats.add("calls")
}

func burst(_ s: Session, seconds: Double) throws {
    let end = Date().addingTimeInterval(seconds)
    var notifKeys: [String] = []
    while Date() < end, s.alive {
        switch rnd(0...11) {
        case 0: try s.send(Message("status", ["battery": rnd(0...100), "charging": Bool.random()]))
        case 1: try s.send(Message("theme", ["p": Int64(0xFF000000) | Int64(rnd(0...0xFFFFFF)), "a": Int64(0xFF000000) | Int64(rnd(0...0xFFFFFF))]))
        case 2: try s.send(Message("otp", ["id": uuid(), "code": String(rnd(100_000...999_999)), "sender": ["HDFC Bank", "Google", ""].randomElement()!, "app": "SMS"])); stats.add("codes")
        case 3: try s.send(Message("text", ["id": uuid()], body: Data(texts.randomElement()!.utf8))); stats.add("texts")
        case 4, 5:
            let (pkg, app) = apps.randomElement()!
            let key = "\(pkg)|\(rnd(1...30))"
            notifKeys.append(key)
            try s.send(Message("notif", ["key": key, "pkg": pkg, "app": app, "title": ["Priya", "Team chat", "Order shipped"].randomElement()!,
                                         "text": texts.randomElement()!, "time": Int64(Date().timeIntervalSince1970 * 1000),
                                         "reply": Bool.random(), "actions": [["i": 0, "title": "Mark as read", "reply": false]]],
                               body: Bool.random() ? iconData : Data()))
            stats.add("notifications")
        case 6: if let key = notifKeys.randomElement() { try s.send(Message("notif_removed", ["key": key])) }
        case 7:
            let art = Bool.random()
            try s.send(Message("media", ["active": true, "app": "Spotify", "title": "Track \(rnd(1...99))", "artist": "Artist", "playing": Bool.random(),
                                         "duration": 200_000, "position": rnd(0...200_000), "art": art, "new_track": Bool.random()],
                               body: art ? artData : Data()))
            stats.add("media")
        case 8: try call(s)
        case 9: try sendFiles(s, cutOff: rnd(0...9) == 0, cancel: rnd(0...7) == 0)
        case 10: try s.send(Message("sms_changed")); try s.send(Message("ring", ["action": "stopped"]))
        default:
            // Unknown and malformed (but authenticated) messages: the Mac should shrug them off.
            try s.send(Message(["nonsense", "otp", "call", "file_chunk"].randomElement()!, ["id": rnd(0...9), "state": "???"]))
            stats.add("odd messages")
        }
        Thread.sleep(forTimeInterval: Double(rnd(0...600)) / 1000)
    }
}

// MARK: Rubbish at the port, alongside

Thread.detachNewThread {
    while Date() < deadline {
        switch rnd(0...5) {
        case 0: // Garbage bytes.
            if let s = Socket(timeout: 3, host: junkHost) { var g = Data(count: rnd(1...4096)); g.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!) }; try? s.write(g); _ = try? s.read(1); s.shut() }
        case 1: // A frame that claims to be 2 GB.
            if let s = Socket(timeout: 3, host: junkHost) { try? s.write(Data([0x7F, 0xFF, 0xFF, 0xFF]) + Data(count: 1000)); _ = try? s.read(1); s.shut() }
        case 2: // A hello that isn't Noise at all, and one from an unknown phone.
            if let s = Socket(timeout: 3, host: junkHost) { try? json(s, ["t": "hello", "v": 3, "noise": Data(count: 80).base64EncodedString()]); _ = try? readJSON(s); s.shut() }
            if let s = Socket(timeout: 3, host: junkHost), let n = try? Noise.Handshake(role: .initiator, prologue: Handshake.tcpPrologue, staticKey: .init(), remoteStatic: macPub) {
                try? json(s, ["t": "hello", "v": 3, "noise": (try? n.writeMessage())?.base64EncodedString() ?? ""]); _ = try? readJSON(s); s.shut()
            }
        case 3: // Floods past the per-host cap, held for a moment.
            let held = (0..<rnd(10...40)).compactMap { _ in Socket(timeout: 2, host: junkHost) }
            Thread.sleep(forTimeInterval: Double(rnd(1...5)))
            held.forEach { $0.shut() }
            stats.add("flood connections", held.count)
        case 4: // Idle (slow loris) for a while.
            if let s = Socket(timeout: 30, host: junkHost) { _ = try? s.read(1); s.shut() }
        default: // Pairing attempts that never finish.
            if let s = Socket(timeout: 3, host: junkHost) { try? json(s, ["t": "pair", "v": 3, "name": "Stranger", "pub": Data(count: 32).base64EncodedString(), "commit": Data(count: 32).base64EncodedString()]); _ = try? readJSON(s); s.shut() }
        }
        stats.add("rubbish")
        Thread.sleep(forTimeInterval: Double(rnd(200...1500)) / 1000)
    }
}

// MARK: Main loop: connect, burst, drop

var lastReport = Date()
var failuresInARow = 0
while Date() < deadline {
    do {
        let s = try Session.open()
        failuresInARow = 0
        try burst(s, seconds: Double(rnd(5...60)))
        // Mostly an abrupt drop (Wi‑Fi gone); sometimes a quick reconnect straight after.
        s.drop()
    } catch {
        stats.add("FAILED sessions")
        failuresInARow += 1
        log("session failed (\(failuresInARow) in a row): \(error)")
        if failuresInARow >= 10 { log("Mac stopped answering"); break }
        Thread.sleep(forTimeInterval: 2)
    }
    Thread.sleep(forTimeInterval: Double(rnd(0...3000)) / 1000)
    if Date().timeIntervalSince(lastReport) > 60 {
        lastReport = Date()
        log("so far: " + stats.snapshot().sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ", "))
    }
}
log("FINAL: " + stats.snapshot().sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ", "))
exit(failuresInARow >= 10 ? 1 : 0)
