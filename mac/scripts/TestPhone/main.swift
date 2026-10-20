// A stand-in phone for testing the Mac app without a real one. Speaks the real protocol
// (PROTOCOL.md): pairs (run the Mac app with WALLHOP_AUTO_PAIR=1), opens an encrypted session,
// then sends a status, theme, code, text and two files, answering pings meanwhile. Files the
// Mac sends are received like the Android app does (file_start/file_chunk/file_end, sha256
// checked, the same `ack`) and logged as "[test-phone] received <name> <bytes> ok".
//
//   scripts/test-phone.sh [host] [port] [seconds to stay connected]
import AppKit
import CryptoKit
import Foundation

let args = CommandLine.arguments
let host = args.count > 1 ? args[1] : "127.0.0.1"
let port = UInt16(args.count > 2 ? args[2] : "47474") ?? 47474
let stay = Double(args.count > 3 ? args[3] : "20") ?? 20

func log(_ s: String) { print("[test-phone] \(s)"); fflush(stdout) }

// MARK: Socket

let fd = socket(AF_INET, SOCK_STREAM, 0)
var addr = sockaddr_in()
addr.sin_family = sa_family_t(AF_INET)
addr.sin_port = port.bigEndian
inet_pton(AF_INET, host, &addr.sin_addr)
let connected = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
guard connected == 0 else { log("can't connect"); exit(1) }

func writeAll(_ data: Data) {
    data.withUnsafeBytes { raw in
        var off = 0
        while off < data.count { off += write(fd, raw.baseAddress! + off, data.count - off) }
    }
}
func readExactly(_ n: Int) -> Data {
    var out = Data(capacity: n)
    var buf = [UInt8](repeating: 0, count: n)
    while out.count < n {
        let r = read(fd, &buf, n - out.count)
        guard r > 0 else { log("connection closed"); exit(2) }
        out.append(buf, count: r)
    }
    return out
}
func readFrame() -> Data {
    let len = readExactly(4).reduce(0) { ($0 << 8) | Int($1) }
    return readExactly(len)
}
func sendJSON(_ o: [String: Any]) { writeAll(Framing.encode(try! HandshakeJSON.encode(o))) }
func readJSON() -> [String: Any] { HandshakeJSON.decode(readFrame()) ?? [:] }

// MARK: Pair + session

let me = Curve25519.KeyAgreement.PrivateKey()
let myPub = me.publicKey.rawRepresentation
// Pairing v2: commit to a nonce, get the Mac's, then reveal ours (PROTOCOL.md, Pairing).
let myNonce = Handshake.randomBytes(16)!
let commit = Handshake.pairingCommit(phonePub: myPub, phoneNonce: myNonce)
sendJSON(["t": "pair", "v": 2, "name": "Test Phone", "pub": myPub.base64EncodedString(), "commit": commit.base64EncodedString()])
let wait = readJSON()
guard wait["t"] as? String == "pair_wait", let macPubB64 = wait["pub"] as? String, let macPub = Data(base64Encoded: macPubB64),
      let macNonce = (wait["nonce"] as? String).flatMap({ Data(base64Encoded: $0) }) else {
    log("unexpected: \(wait)"); exit(3)
}
sendJSON(["t": "pair_nonce", "nonce": myNonce.base64EncodedString()])
log("pair_wait from \(wait["name"] ?? "?"), code \(Handshake.pairingCode(phonePub: myPub, macPub: macPub, phoneNonce: myNonce, macNonce: macNonce))")
let decision = readJSON()
guard decision["t"] as? String == "pair_ok" else { log("pairing: \(decision)"); exit(4) }
log("paired")

let eph = Curve25519.KeyAgreement.PrivateKey()
let ephPub = eph.publicKey.rawRepresentation
sendJSON(["t": "hello", "v": 2, "name": "Test Phone", "pub": myPub.base64EncodedString(), "eph": ephPub.base64EncodedString()])
let ok = readJSON()
guard ok["t"] as? String == "hello_ok", let macEph = (ok["eph"] as? String).flatMap({ Data(base64Encoded: $0) }) else {
    log("hello: \(ok)"); exit(5)
}
let macStatic = try! Curve25519.KeyAgreement.PublicKey(rawRepresentation: macPub)
let macEphKey = try! Curve25519.KeyAgreement.PublicKey(rawRepresentation: macEph)
let dh1 = try! eph.sharedSecretFromKeyAgreement(with: macEphKey).data
let dh2 = try! me.sharedSecretFromKeyAgreement(with: macEphKey).data
let dh3 = try! eph.sharedSecretFromKeyAgreement(with: macStatic).data
let keys = Handshake.sessionKeys(ikm: dh1 + dh2 + dh3, transcript: myPub + ephPub + macPub + macEph)
let channel = SecureChannel(sendKey: keys.phoneToMac, receiveKey: keys.macToPhone)
let sendLock = NSLock()
func send(_ m: Message) {
    sendLock.lock(); defer { sendLock.unlock() }
    writeAll(Framing.encode(try! channel.seal(try! m.encoded())))
}
func receive() -> Message { try! Message.decode(try! channel.open(readFrame())) }

send(Message("ready"))
let ready = receive()
log("session: \(ready.type)")

// Answer pings, receive files and print what the Mac sends, in the background.
struct IncomingFile { let name: String; let size: Int; var data = Data() }
var incoming: [String: IncomingFile] = [:]
Thread.detachNewThread {
    while true {
        let m = receive()
        switch m.type {
        case "ping": send(Message("pong"))
        case "file_start":
            if let id = m.string("id") { incoming[id] = IncomingFile(name: m.string("name") ?? "File", size: m.int("size") ?? 0) }
        case "file_chunk":
            if let id = m.string("id") { incoming[id]?.data.append(m.body) }
        case "file_end":
            let id = m.string("id") ?? ""
            let file = incoming.removeValue(forKey: id)
            let ok = file.map { Data(SHA256.hash(data: $0.data)).hex == m.string("sha256")?.lowercased() } ?? false
            // Same as Android (LinkManager): ack with ok, and err when it failed.
            var ack: [String: Any] = ["id": id, "ok": ok]
            if !ok { ack["err"] = "checksum mismatch" }
            send(Message("ack", ack))
            log("received \(file?.name ?? "?") \(file?.data.count ?? 0) \(ok ? "ok" : "FAILED")")
            continue
        default: break
        }
        if m.type != "file_chunk" { log("mac → \(m.type) \(m.header.filter { $0.key != "t" })") }
    }
}

// MARK: Things a phone sends

func pause(_ s: Double) { Thread.sleep(forTimeInterval: s) }
send(Message("status", ["battery": 76, "charging": true]))
send(Message("theme", ["p": 0xFF1E88E5 as Int64, "a": 0xFFE91E63 as Int64]))
log("sent status + theme")
pause(1.5)
send(Message("otp", ["id": UUID().uuidString, "code": "731942", "sender": "HDFC Bank", "app": "SMS"]))
log("sent code 731942")
pause(4)
send(Message("text", ["id": UUID().uuidString], body: Data("Meeting moved to 4:30, see you there".utf8)))
log("sent text")
pause(3)

// Two files: a generated photo and a note.
let image = NSImage(size: NSSize(width: 400, height: 300))
image.lockFocus()
NSGradient(colors: [.systemPink, .systemIndigo])!.draw(in: NSRect(x: 0, y: 0, width: 400, height: 300), angle: 35)
image.unlockFocus()
let png = NSBitmapImageRep(data: image.tiffRepresentation!)!.representation(using: .png, properties: [:])!
let files: [(String, Data)] = [("Sunset.png", png), ("Notes.txt", Data("Hello from the test phone.\n".utf8))]
let batch = UUID().uuidString
send(Message("batch_start", ["batch": batch, "count": files.count, "bytes": files.reduce(0) { $0 + $1.1.count }]))
for (name, data) in files {
    let id = UUID().uuidString
    send(Message("file_start", ["id": id, "batch": batch, "name": name, "size": data.count]))
    var offset = 0
    while offset < data.count {
        let end = min(offset + 64 * 1024, data.count)
        send(Message("file_chunk", ["id": id, "batch": batch], body: data.subdata(in: offset..<end)))
        offset = end
    }
    send(Message("file_end", ["id": id, "batch": batch, "sha256": Data(SHA256.hash(data: data)).hex]))
}
send(Message("batch_end", ["batch": batch]))
log("sent 2 files")

pause(stay)
log("done")
exit(0)
