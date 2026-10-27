import CryptoKit
import Foundation
import Network

/// One TCP connection from a phone: pairing, the session handshake, then sealed messages.
/// Every method runs on the server's queue.
final class PhoneConnection {
    enum State {
        case handshake
        /// Sent `pair_wait` with this Mac's nonce; waiting for the phone to reveal its own.
        case awaitingPairNonce
        case awaitingPairingDecision
        case awaitingReady
        case ready
        case closed
    }

    /// Time allowed for an unauthenticated connection to finish the handshake.
    static let handshakeTimeout: TimeInterval = 15
    /// Time a pairing request waits for the user before it's declined.
    static let pairingDecisionTimeout: TimeInterval = 120
    /// The session protocol (`v` in `hello`) this Mac speaks: 3 is the Noise handshake.
    static let protocolVersion = 3
    static let supportedProtocols = 3...3
    /// This app's version, sent as `app` in `hello_ok`.
    static let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev"

    let id = UUID()
    let openedAt = Date()
    private let connection: NWConnection
    private unowned let server: LinkServer
    private var reader = FrameReader()
    private var channel: SecureChannel?
    private(set) var state: State = .handshake
    private(set) var phone: PairedPhone?
    private(set) var pairingId: UUID?
    /// Files the phone is sending on this connection.
    let files = FileReceiver()
    private var deadline: DispatchWorkItem?
    /// Pairing v2 in progress: the phone's key, name and commitment, and this Mac's nonce.
    private var pairing: (pub: Data, name: String, commit: Data, macNonce: Data)?
    /// A new name from `hello`, saved only once `ready` proves the phone holds its key.
    private var pendingName: String?
    /// What the phone said in `hello`: its app version (`app`) and protocol version (`v`).
    private(set) var phoneApp: String?
    private(set) var phoneProtocol: Int?
    /// The pairing request ran out of time waiting for the user (not the phone giving up).
    private(set) var pairingTimedOut = false

    var remoteHost: String {
        if case let .hostPort(host, _) = connection.endpoint { return "\(host)" }
        return "unknown"
    }

    init(connection: NWConnection, server: LinkServer) {
        self.connection = connection
        self.server = server
    }

    /// The largest frame before the session is up (PROTOCOL.md, Framing).
    static let handshakeFrameLimit = 16 * 1024

    func start() {
        reader.limit = Self.handshakeFrameLimit
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        connection.start(queue: server.queue)
        armDeadline(Self.handshakeTimeout)
        receive()
    }

    func close(_ why: String = "closed", line: Int = #line) {
        guard state != .closed else { return }
        if LinkServer.debugLog { NSLog("Lync: connection closed (\(why), line \(line), state \(state))") }
        state = .closed
        deadline?.cancel()
        connection.cancel()
        server.connectionClosed(self)
    }

    /// Closes the connection unless it's established by then.
    private func armDeadline(_ seconds: TimeInterval) {
        deadline?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.state != .ready, self.state != .closed else { return }
            if self.pairingId != nil { self.pairingTimedOut = true }
            self.close()
        }
        deadline = work
        server.queue.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    // MARK: - Reading

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 512 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, self.state != .closed else { return }
            if let data, !data.isEmpty {
                do {
                    for frame in try self.reader.push(data) {
                        self.handle(frame: frame)
                        if self.state == .closed { return }
                    }
                } catch {
                    self.close()
                    return
                }
            }
            if isComplete || error != nil {
                self.close()
            } else {
                self.receive()
            }
        }
    }

    private func handle(frame: Data) {
        guard let channel else {
            handleHandshake(frame)
            return
        }
        guard let plaintext = try? channel.open(frame), let message = try? Message.decode(plaintext) else {
            close()
            return
        }
        if state == .awaitingReady {
            guard message.type == "ready", var phone else { close(); return }
            // `ready` decrypted, so the phone holds its key: now a new name can be trusted.
            if let pendingName {
                phone.name = pendingName
                server.paired.add(phone)
                self.phone = phone
                self.pendingName = nil
            }
            state = .ready
            deadline?.cancel()
            reader.limit = Framing.maxPayload
            send(Message("ready"))
            server.connectionReady(self, phone: phone)
            return
        }
        guard state == .ready else { return }
        server.handle(message, from: self)
    }

    // MARK: - Handshake

    private func handleHandshake(_ frame: Data) {
        guard let json = HandshakeJSON.decode(frame), let type = json["t"] as? String else {
            close()
            return
        }
        switch (type, state) {
        case ("pair", .handshake):
            handlePairRequest(json)
        case ("pair_nonce", .awaitingPairNonce):
            handlePairNonce(json)
        case ("pair_nonce", .handshake):
            break // After QR pairing the nonce isn't needed (the QR's secret already authenticated both keys).
        case ("hello", .handshake):
            handleHello(json)
        default:
            close()
        }
    }

    private func handlePairRequest(_ json: [String: Any]) {
        guard let pub = (json["pub"] as? String).flatMap({ Data(base64Encoded: $0) }), pub.count == 32 else {
            close()
            return
        }
        // Pairing v3: the v2 exchange from a phone that speaks the v3 (Noise) session; older
        // phones couldn't connect once paired, so they're told to update now (PROTOCOL.md).
        guard (json["v"] as? NSNumber)?.intValue == Self.protocolVersion else {
            if ((json["v"] as? NSNumber)?.intValue ?? 0) < Self.protocolVersion { server.refusedOldPhone() }
            sendHandshake(["t": "pair_no", "err": PhoneError.version, "v": Self.protocolVersion], thenClose: true)
            return
        }
        guard server.allowPairAttempt(from: remoteHost) else {
            sendHandshake(["t": "pair_no", "err": PhoneError.rate], thenClose: true)
            return
        }
        let name = (json["name"] as? String).map { String($0.prefix(60)) } ?? "Android phone"
        let macPub = server.identity.publicKey.base64EncodedString()

        // Scanned the QR: the proof binds both keys to the QR's secret, so no code is needed.
        // `pair_wait` still carries a nonce so phones can use one code path; a `pair_nonce` that
        // follows is ignored, and so is a `commit`.
        if let proof = (json["qr"] as? String).flatMap({ Data(base64Encoded: $0) }) {
            state = .awaitingPairingDecision
            sendHandshake(["t": "pair_wait", "name": server.macName, "pub": macPub,
                           "nonce": (Handshake.randomBytes(16) ?? Data(count: 16)).base64EncodedString()])
            // Expired, used or replaced (`expired`), or not this QR's proof (`mismatch`).
            if let refusal = server.redeemInvite(proof: proof, phonePub: pub) {
                sendHandshake(["t": "pair_no", "err": refusal], thenClose: true)
                return
            }
            server.paired.add(PairedPhone(id: Handshake.deviceId(of: pub), name: name, publicKey: pub, pairedAt: Date()))
            state = .handshake
            armDeadline(Self.handshakeTimeout)
            sendHandshake(["t": "pair_ok"])
            server.pairedWithQR(name: name)
            return
        }

        guard let commit = (json["commit"] as? String).flatMap({ Data(base64Encoded: $0) }), commit.count == 32,
              let macNonce = Handshake.randomBytes(16) else {
            close()
            return
        }
        // One pairing request on screen at a time.
        guard server.beginPairing(self) else {
            sendHandshake(["t": "pair_no", "err": PhoneError.busy], thenClose: true)
            return
        }
        pairing = (pub, name, commit, macNonce)
        state = .awaitingPairNonce
        sendHandshake(["t": "pair_wait", "name": server.macName, "pub": macPub, "nonce": macNonce.base64EncodedString()])
    }

    /// The phone reveals the nonce it committed to; only then is a code shown.
    private func handlePairNonce(_ json: [String: Any]) {
        guard let pairing,
              let nonce = (json["nonce"] as? String).flatMap({ Data(base64Encoded: $0) }), nonce.count == 16,
              Handshake.constantTimeEqual(Handshake.pairingCommit(phonePub: pairing.pub, phoneNonce: nonce), pairing.commit)
        else {
            close()
            return
        }
        let code = Handshake.pairingCode(phonePub: pairing.pub, macPub: server.identity.publicKey,
                                         phoneNonce: nonce, macNonce: pairing.macNonce)
        state = .awaitingPairingDecision
        armDeadline(Self.pairingDecisionTimeout)

        let requestId = UUID()
        pairingId = requestId
        server.requestPairing(id: requestId, phoneName: pairing.name, code: code) { [weak self] accepted in
            guard let self, self.state == .awaitingPairingDecision else { return }
            self.pairingId = nil
            self.server.endPairing(self)
            if accepted {
                let phone = PairedPhone(id: Handshake.deviceId(of: pairing.pub), name: pairing.name,
                                        publicKey: pairing.pub, pairedAt: Date())
                self.server.paired.add(phone)
                self.state = .handshake
                self.armDeadline(Self.handshakeTimeout)
                self.sendHandshake(["t": "pair_ok"])
            } else {
                self.sendHandshake(["t": "pair_no", "err": PhoneError.declined], thenClose: true)
            }
        }
    }

    /// The session handshake (PROTOCOL.md, v3): Noise IK, the phone initiating. Its key and name
    /// arrive encrypted; only a phone that knows this Mac's key can write a hello it can read.
    private func handleHello(_ json: [String: Any]) {
        guard (json["v"] as? NSNumber)?.intValue == Self.protocolVersion,
              let message = (json["noise"] as? String).flatMap({ Data(base64Encoded: $0) }),
              let noise = try? Noise.Handshake(role: .responder, prologue: Handshake.tcpPrologue, staticKey: server.identity.privateKey)
        else {
            // Another session protocol: tell the phone which one this Mac speaks (it says "update"),
            // and the user here, once, which side needs it.
            server.refusedSession(protocolVersion: (json["v"] as? NSNumber)?.intValue)
            sendHandshake(["t": "hello_no", "v": Self.protocolVersion], thenClose: true)
            return
        }
        guard let payload = try? noise.readMessage(message), let pub = noise.remoteStatic?.rawRepresentation else {
            // Not written for this Mac's key: Lync was reinstalled here, or it's meant for another
            // Mac. Say so (a hint the phone can't rely on), and nothing else.
            sendHandshake(["t": "not_me"], thenClose: true)
            return
        }
        let about = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] ?? [:]
        let phone = server.paired.phone(withPublicKey: pub)
        var answer: [String: Any] = ["name": server.macName, "app": Self.appVersion, "paired": phone != nil]
        if phone == nil { answer["name"] = nil }
        guard let reply = try? noise.writeMessage(payload: (try? JSONSerialization.data(withJSONObject: answer)) ?? Data()),
              let ciphers = try? noise.split()
        else {
            close()
            return
        }
        // Not paired (forgotten here): the phone hears it from this Mac, authenticated, and stops.
        guard let phone else {
            sendHandshake(["t": "hello_ok", "noise": reply.base64EncodedString()], thenClose: true)
            return
        }
        if let name = about["name"] as? String, !name.isEmpty, name != phone.name {
            pendingName = String(name.prefix(60))
        }
        phoneApp = (about["app"] as? String).map { String($0.prefix(40)) }
        phoneProtocol = Self.protocolVersion
        sendHandshake(["t": "hello_ok", "noise": reply.base64EncodedString()])
        channel = SecureChannel(ciphers)
        self.phone = phone
        state = .awaitingReady
    }

    // MARK: - Writing

    private func sendHandshake(_ object: [String: Any], thenClose: Bool = false) {
        guard let data = try? HandshakeJSON.encode(object), data.count <= Framing.maxOutgoingPayload else { return }
        connection.send(content: Framing.encode(data), completion: .contentProcessed { [weak self] _ in
            if thenClose { self?.close() }
        })
    }

    /// Seals and sends a message. Only valid once the session is established. A message that
    /// wouldn't fit in one frame is refused (completion false) and never sent.
    func send(_ message: Message, completion: ((Bool) -> Void)? = nil) {
        guard state == .ready || (state == .awaitingReady && message.type == "ready"),
              let channel,
              let plaintext = try? message.encoded()
        else {
            completion?(false)
            return
        }
        // Checked before sealing so a refused message doesn't use up a nonce.
        guard plaintext.count + 16 <= Framing.maxOutgoingPayload else {
            NSLog("Lync: not sending \(message.type), \(plaintext.count) bytes is over the frame limit")
            completion?(false)
            return
        }
        guard let sealed = try? channel.seal(plaintext) else {
            completion?(false)
            return
        }
        connection.send(content: Framing.encode(sealed), completion: .contentProcessed { [weak self] error in
            if error != nil { self?.close() }
            completion?(error == nil)
        })
    }
}
