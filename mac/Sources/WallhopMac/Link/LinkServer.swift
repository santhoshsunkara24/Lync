import AppKit
import Foundation
import Network

/// Something that happened on the link, delivered to the app on the main thread.
enum LinkEvent {
    case listening(port: UInt16?)
    case stopped(reason: String)
    case pairingRequested(PairingRequest)
    case pairingEnded(id: UUID)
    /// The phone's Material You colours (ARGB), so the Mac matches it.
    case theme(seed: UInt32, accent: UInt32?)
    /// A phone paired by scanning this Mac's QR code; no confirmation needed.
    case pairedWithQR(name: String)
    case connected(PairedPhone, host: String)
    case disconnected(PairedPhone)
    case text(String, from: PairedPhone)
    case otp(code: String, sender: String, app: String)
    case status(battery: Int, charging: Bool)
    case batchStarted(id: String, count: Int, bytes: Int64)
    case media(NowPlaying?, art: Data?)
    case batchProgress(id: String, bytes: Int64)
    case fileSaved(batch: String, url: URL)
    case fileFailed(batch: String, name: String)
    case batchFinished(id: String)
    case notification(PhoneNotification, icon: Data?)
    case call(PhoneCall)
    case smsChanged
    case linkOpened(URL)
    case notificationRemoved(key: String)
    case outgoingProgress(id: String, sent: Int64, total: Int64)
    case outgoingFinished(id: String, ok: Bool)
}

/// A notification mirrored from the phone.
struct PhoneNotification: Identifiable, Equatable {
    let key: String
    let packageName: String
    let appName: String
    let title: String
    let text: String
    let time: Date
    let canReply: Bool
    var actions: [NotificationAction] = []

    var id: String { key }
}

/// A button on a phone notification, such as "Mark as read".
struct NotificationAction: Equatable, Identifiable {
    let index: Int
    let title: String
    let isReply: Bool

    var id: Int { index }
}

/// What's playing on the phone.
struct NowPlaying: Equatable {
    let app: String
    let title: String
    let artist: String
    let playing: Bool
}

/// A phone call reported by the phone.
struct PhoneCall: Equatable {
    enum State: String { case ringing, active, ended, missed }

    let id: String
    let state: State
    let number: String?
    let name: String?
    let sim: String?

    var displayName: String { name ?? number ?? "Unknown caller" }
}

/// A phone asking to pair. `decide` may be called from any thread.
struct PairingRequest: Identifiable {
    let id: UUID
    let phoneName: String
    let code: String
    let decide: (Bool) -> Void
}

/// Listens on the local network, advertises `_wallhop._tcp` over Bonjour, and runs phone connections.
final class LinkServer {
    static let serviceType = "_wallhop._tcp"
    /// Development: WALLHOP_DEBUG=1 logs message types (never message text).
    static let debugLog = ProcessInfo.processInfo.environment["WALLHOP_DEBUG"] == "1"
    /// WALLHOP_PORT overrides it for a test copy, so a real phone never reaches that copy.
    static let preferredPort: UInt16 = ProcessInfo.processInfo.environment["WALLHOP_PORT"].flatMap(UInt16.init) ?? 47474

    let identity: Identity
    let paired: PairedPhones
    let queue = DispatchQueue(label: "app.wallhop.link")
    let macName = ProcessInfo.processInfo.environment["WALLHOP_NAME"] ?? Host.current().localizedName ?? "Mac"

    /// Called on the main thread.
    var onEvent: ((LinkEvent) -> Void)?

    private var listener: NWListener?
    private var connections: [UUID: PhoneConnection] = [:]
    private var active: PhoneConnection?
    private var listeningPort: UInt16?
    /// The secret in the pairing QR currently on screen. One at a time, used once, two minutes.
    private var invite: (token: Data, expires: Date)?
    private lazy var files = FileReceiver()
    private var pendingAcks: [String: (Bool) -> Void] = [:]
    private var pendingResults: [String: (Message?) -> Void] = [:]

    init(identity: Identity, paired: PairedPhones) {
        self.identity = identity
        self.paired = paired
    }

    func start() {
        queue.async { self.startListener(port: Self.preferredPort) }
    }

    // MARK: - Listener

    private func startListener(port: UInt16?) {
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 20
        tcp.keepaliveInterval = 10
        tcp.keepaliveCount = 3
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.allowLocalEndpointReuse = true

        let listener: NWListener
        do {
            if let port, let nwPort = NWEndpoint.Port(rawValue: port) {
                listener = try NWListener(using: parameters, on: nwPort)
            } else {
                listener = try NWListener(using: parameters)
            }
        } catch {
            if port != nil { startListener(port: nil) } else { emit(.stopped(reason: error.localizedDescription)) }
            return
        }

        listener.service = NWListener.Service(name: macName, type: Self.serviceType)
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.listeningPort = listener?.port?.rawValue
                self.emit(.listening(port: listener?.port?.rawValue))
            case .failed(let error):
                listener?.cancel()
                if port != nil, case .posix(.EADDRINUSE) = error {
                    self.startListener(port: nil)
                } else {
                    self.emit(.stopped(reason: error.localizedDescription))
                    self.queue.asyncAfter(deadline: .now() + 3) { self.startListener(port: port) }
                }
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] nw in
            guard let self else { nw.cancel(); return }
            let connection = PhoneConnection(connection: nw, server: self)
            self.connections[connection.id] = connection
            connection.start()
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    // MARK: - QR pairing

    /// A fresh pairing link for a QR code: this Mac's address, name and key plus a one-time
    /// secret. Making a new one cancels the previous one. Nil until the listener is up.
    func makeInvite() -> URL? {
        queue.sync {
            let hosts = NetworkInfo.localIPv4s()
            guard let port = listeningPort, !hosts.isEmpty else { return nil }
            var token = Data(count: 16)
            let status = token.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
            guard status == errSecSuccess else { return nil }
            invite = (token, Date().addingTimeInterval(120))
            var components = URLComponents()
            components.scheme = "wallhop"
            components.host = "pair"
            components.queryItems = [
                URLQueryItem(name: "v", value: "1"),
                URLQueryItem(name: "n", value: macName),
                // Every address, comma-separated: the phone uses whichever it can reach.
                URLQueryItem(name: "h", value: hosts.joined(separator: ",")),
                URLQueryItem(name: "p", value: String(port)),
                URLQueryItem(name: "k", value: identity.publicKey.base64URL),
                URLQueryItem(name: "t", value: token.base64URL),
            ]
            return components.url
        }
    }

    /// Stops the QR on screen from working, e.g. when the panel closes.
    func cancelInvite() {
        queue.async { self.invite = nil }
    }

    // MARK: - Called by PhoneConnection (on queue)

    /// Checks a phone's QR proof and uses up the secret if it matches.
    func redeemInvite(proof: Data, phonePub: Data) -> Bool {
        guard let current = invite, current.expires > Date() else { return false }
        let expected = Handshake.qrProof(token: current.token, phonePub: phonePub, macPub: identity.publicKey)
        // Constant-time comparison, so timing doesn't leak how much of the proof was right.
        guard expected.count == proof.count, zip(expected, proof).reduce(UInt8(0), { $0 | ($1.0 ^ $1.1) }) == 0 else { return false }
        invite = nil
        return true
    }

    func pairedWithQR(name: String) {
        emit(.pairedWithQR(name: name))
    }

    func requestPairing(id: UUID, phoneName: String, code: String, decision: @escaping (Bool) -> Void) {
        let decideOnce = Once<Bool> { [weak self] accepted in
            self?.queue.async {
                decision(accepted)
                self?.emit(.pairingEnded(id: id))
            }
        }
        if ProcessInfo.processInfo.environment["WALLHOP_AUTO_PAIR"] == "1" {
            decideOnce.call(true)
            return
        }
        emit(.pairingRequested(PairingRequest(id: id, phoneName: phoneName, code: code, decide: decideOnce.call)))
    }

    func connectionReady(_ connection: PhoneConnection, phone: PairedPhone) {
        // One active connection per Mac; a reconnecting phone replaces the old one.
        if let old = active, old !== connection { old.close() }
        active = connection
        emit(.connected(phone, host: connection.remoteHost))
    }

    func connectionClosed(_ connection: PhoneConnection) {
        connections[connection.id] = nil
        if let id = connection.pairingId { emit(.pairingEnded(id: id)) }
        if active === connection {
            active = nil
            files.cancelAll()
            if let phone = connection.phone { emit(.disconnected(phone)) }
        }
    }

    func handle(_ message: Message, from connection: PhoneConnection) {
        if Self.debugLog { NSLog("Lync: ← \(message.type) \(message.header.filter { $0.key != "t" && $0.key != "text" })") }
        guard let phone = connection.phone else { return }
        switch message.type {
        case "ping":
            connection.send(Message("pong"))

        case "text":
            emit(.text(message.bodyText, from: phone))
            ack(message, on: connection)

        case "otp":
            if let code = message.string("code"), !code.isEmpty {
                emit(.otp(code: code, sender: message.string("sender") ?? "", app: message.string("app") ?? ""))
            }
            ack(message, on: connection)

        case "status":
            emit(.status(battery: message.int("battery") ?? -1, charging: message.bool("charging") ?? false))

        case "batch_start":
            if let batch = message.string("batch") {
                emit(.batchStarted(id: batch, count: message.int("count") ?? 1,
                                   bytes: Int64(message.int("bytes") ?? 0)))
            }

        case "file_start":
            files.begin(message)

        case "file_chunk":
            if let (batch, bytes) = files.append(message) {
                emit(.batchProgress(id: batch, bytes: bytes))
            }

        case "file_end":
            let result = files.finish(message)
            switch result {
            case .saved(let batch, let url):
                emit(.fileSaved(batch: batch, url: url))
                connection.send(Message("ack", ["id": message.string("id") ?? "", "ok": true]))
            case .failed(let batch, let name, let reason):
                emit(.fileFailed(batch: batch, name: name))
                connection.send(Message("ack", ["id": message.string("id") ?? "", "ok": false, "err": reason]))
            }

        case "batch_end":
            if let batch = message.string("batch") { emit(.batchFinished(id: batch)) }

        case "notif":
            guard let key = message.string("key"), let pkg = message.string("pkg") else { break }
            let time = (message.header["time"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) } ?? Date()
            let actions = (message.header["actions"] as? [[String: Any]] ?? []).compactMap { item -> NotificationAction? in
                guard let index = (item["i"] as? NSNumber)?.intValue, let title = item["title"] as? String else { return nil }
                return NotificationAction(index: index, title: title, isReply: (item["reply"] as? NSNumber)?.boolValue ?? false)
            }
            let notification = PhoneNotification(
                key: key, packageName: pkg, appName: message.string("app") ?? pkg,
                title: message.string("title") ?? "", text: message.string("text") ?? "",
                time: time, canReply: message.bool("reply") ?? false, actions: actions
            )
            emit(.notification(notification, icon: message.body.isEmpty ? nil : message.body))

        case "media":
            if message.bool("active") == true {
                let nowPlaying = NowPlaying(app: message.string("app") ?? "", title: message.string("title") ?? "",
                                            artist: message.string("artist") ?? "", playing: message.bool("playing") ?? false)
                emit(.media(nowPlaying, art: message.bool("art") == true && !message.body.isEmpty ? message.body : nil))
            } else {
                emit(.media(nil, art: nil))
            }

        case "call":
            if let id = message.string("id"), let state = PhoneCall.State(rawValue: message.string("state") ?? "") {
                emit(.call(PhoneCall(id: id, state: state, number: message.string("number"),
                                     name: message.string("name"), sim: message.string("sim"))))
            }

        case "sms_result":
            if let id = message.string("id"), let callback = pendingResults.removeValue(forKey: id) {
                callback(message)
            }

        case "open_url":
            if let raw = message.string("url"), let url = URL(string: raw),
               let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                DispatchQueue.main.async { NSWorkspace.shared.open(url) }
                emit(.linkOpened(url))
                ack(message, on: connection)
            }

        case "theme":
            if let seed = (message.header["p"] as? NSNumber)?.int64Value {
                // Not "t": that's the message type.
                let accent = (message.header["a"] as? NSNumber)?.int64Value
                emit(.theme(seed: UInt32(truncatingIfNeeded: seed), accent: accent.map { UInt32(truncatingIfNeeded: $0) }))
            }

        case "sms_changed":
            emit(.smsChanged)

        case "notif_removed":
            if let key = message.string("key") { emit(.notificationRemoved(key: key)) }

        case "ack":
            if let id = message.string("id"), let callback = pendingAcks.removeValue(forKey: id) {
                callback(message.bool("ok") ?? true)
            }

        default:
            break // Unknown types are ignored so newer phones keep working.
        }
    }

    // MARK: - Sending to the phone

    var isConnected: Bool {
        queue.sync { active != nil }
    }

    /// Sends text to the connected phone. `completion` runs on the main thread.
    func sendText(_ text: String, completion: @escaping (Bool) -> Void) {
        queue.async {
            guard let active = self.active else {
                DispatchQueue.main.async { completion(false) }
                return
            }
            let id = UUID().uuidString
            self.pendingAcks[id] = { ok in DispatchQueue.main.async { completion(ok) } }
            active.send(Message("text", ["id": id], body: Data(text.utf8))) { sent in
                if !sent, let callback = self.pendingAcks.removeValue(forKey: id) { callback(false) }
            }
            self.queue.asyncAfter(deadline: .now() + 10) {
                if let callback = self.pendingAcks.removeValue(forKey: id) { callback(false) }
            }
        }
    }

    /// Replies through the notification's own quick-reply action on the phone.
    func replyToNotification(key: String, text: String, completion: @escaping (Bool) -> Void) {
        queue.async {
            guard let active = self.active else {
                DispatchQueue.main.async { completion(false) }
                return
            }
            let id = UUID().uuidString
            self.pendingAcks[id] = { ok in DispatchQueue.main.async { completion(ok) } }
            active.send(Message("notif_reply", ["id": id, "key": key], body: Data(text.utf8)))
            self.queue.asyncAfter(deadline: .now() + 10) {
                if let callback = self.pendingAcks.removeValue(forKey: id) { callback(false) }
            }
        }
    }

    /// Taps a notification's action button on the phone.
    func performNotificationAction(key: String, index: Int) {
        queue.async { self.active?.send(Message("notif_action", ["key": key, "i": index])) }
    }

    /// Asks the phone something and waits for its `sms_result` reply. `completion` runs on the main thread
    /// with nil if the phone isn't connected or doesn't answer in time.
    func request(_ type: String, _ fields: [String: Any] = [:], timeout: TimeInterval = 15,
                 completion: @escaping (Message?) -> Void) {
        queue.async {
            guard let active = self.active else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            let id = UUID().uuidString
            var header = fields
            header["id"] = id
            self.pendingResults[id] = { message in DispatchQueue.main.async { completion(message) } }
            active.send(Message(type, header))
            self.queue.asyncAfter(deadline: .now() + timeout) {
                if let callback = self.pendingResults.removeValue(forKey: id) { callback(nil) }
            }
        }
    }

    /// Sends a text from the phone. `completion` runs on the main thread with an error, or nil on success.
    func sendSMS(to address: String, text: String, sim: Int?, completion: @escaping (String?) -> Void) {
        queue.async {
            guard let active = self.active else {
                DispatchQueue.main.async { completion("Your phone isn't connected") }
                return
            }
            let id = UUID().uuidString
            var header: [String: Any] = ["id": id, "address": address]
            if let sim { header["sim"] = sim }
            self.pendingAcks[id] = { ok in DispatchQueue.main.async { completion(ok ? nil : "Your phone couldn't send it") } }
            active.send(Message("sms_send", header, body: Data(text.utf8)))
            self.queue.asyncAfter(deadline: .now() + 20) {
                if let callback = self.pendingAcks.removeValue(forKey: id) { callback(false) }
            }
        }
    }

    /// Offers a link on the phone (it shows as a notification to tap).
    func openOnPhone(_ url: URL) {
        queue.async { self.active?.send(Message("open_url", ["url": url.absoluteString])) }
    }

    /// Starts or stops the phone ringing so it can be found.
    func ringPhone(_ start: Bool) {
        queue.async { self.active?.send(Message("ring", ["action": start ? "start" : "stop"])) }
    }

    /// Play, pause, skip or change volume on the phone.
    /// Sends a message that needs no reply. Dropped when no phone is connected.
    func send(_ message: Message) {
        queue.async { self.active?.send(message) }
    }

    func sendMediaCommand(_ action: String) {
        queue.async { self.active?.send(Message("media_cmd", ["action": action])) }
    }

    /// Answers or declines the ringing call on the phone.
    func sendCallCommand(_ action: String) {
        queue.async { self.active?.send(Message("call_cmd", ["action": action])) }
    }

    /// Re-announces this Mac on the network, for example after waking, so the phone reconnects fast.
    func refreshAdvertising() {
        queue.async {
            guard let listener = self.listener else { return }
            listener.service = nil
            listener.service = NWListener.Service(name: self.macName, type: Self.serviceType)
        }
    }

    /// Clears a notification on the phone.
    func dismissNotification(key: String) {
        queue.async { self.active?.send(Message("notif_dismiss", ["key": key])) }
    }

    /// Streams files to the connected phone.
    func sendFiles(_ urls: [URL]) -> String? {
        let batch = UUID().uuidString
        let readable = urls.filter { FileManager.default.isReadableFile(atPath: $0.path) }
        guard !readable.isEmpty else { return nil }
        queue.async {
            guard let active = self.active else {
                self.emit(.outgoingFinished(id: batch, ok: false))
                return
            }
            FileSender(batch: batch, urls: readable, connection: active, queue: self.queue) { [weak self] event in
                switch event {
                case .progress(let sent, let total): self?.emit(.outgoingProgress(id: batch, sent: sent, total: total))
                case .finished(let ok): self?.emit(.outgoingFinished(id: batch, ok: ok))
                }
            } registerAck: { [weak self] id, callback in
                self?.pendingAcks[id] = callback
            }.start()
        }
        return batch
    }

    // MARK: - Helpers

    private func ack(_ message: Message, on connection: PhoneConnection) {
        guard let id = message.string("id") else { return }
        connection.send(Message("ack", ["id": id, "ok": true]))
    }

    private func emit(_ event: LinkEvent) {
        DispatchQueue.main.async { self.onEvent?(event) }
    }
}

/// Runs a callback at most once, from any thread.
final class Once<Value> {
    private let lock = NSLock()
    private var callback: ((Value) -> Void)?

    init(_ callback: @escaping (Value) -> Void) {
        self.callback = callback
    }

    func call(_ value: Value) {
        lock.lock()
        let callback = self.callback
        self.callback = nil
        lock.unlock()
        callback?(value)
    }
}
