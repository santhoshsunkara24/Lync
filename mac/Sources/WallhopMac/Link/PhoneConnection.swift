import CryptoKit
import Foundation
import Network

/// One TCP connection from a phone: pairing, the session handshake, then sealed messages.
/// Every method runs on the server's queue.
final class PhoneConnection {
    enum State {
        case handshake
        case awaitingPairingDecision
        case awaitingReady
        case ready
        case closed
    }

    let id = UUID()
    private let connection: NWConnection
    private unowned let server: LinkServer
    private var reader = FrameReader()
    private var channel: SecureChannel?
    private(set) var state: State = .handshake
    private(set) var phone: PairedPhone?
    private(set) var pairingId: UUID?

    var remoteHost: String {
        if case let .hostPort(host, _) = connection.endpoint { return "\(host)" }
        return "unknown"
    }

    init(connection: NWConnection, server: LinkServer) {
        self.connection = connection
        self.server = server
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        connection.start(queue: server.queue)
        receive()
    }

    func close() {
        guard state != .closed else { return }
        state = .closed
        connection.cancel()
        server.connectionClosed(self)
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
            guard message.type == "ready", let phone else { close(); return }
            state = .ready
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
        let name = (json["name"] as? String).map { String($0.prefix(60)) } ?? "Android phone"
        let code = Handshake.pairingCode(phonePub: pub, macPub: server.identity.publicKey)

        state = .awaitingPairingDecision
        sendHandshake(["t": "pair_wait", "name": server.macName, "pub": server.identity.publicKey.base64EncodedString()])

        // Scanned the QR: the proof shows this phone saw the secret, so no confirmation is needed.
        if let proof = (json["qr"] as? String).flatMap({ Data(base64Encoded: $0) }) {
            guard server.redeemInvite(proof: proof, phonePub: pub) else {
                sendHandshake(["t": "pair_no"], thenClose: true)
                return
            }
            server.paired.add(PairedPhone(id: Handshake.deviceId(of: pub), name: name, publicKey: pub, pairedAt: Date()))
            state = .handshake
            sendHandshake(["t": "pair_ok"])
            server.pairedWithQR(name: name)
            return
        }

        let requestId = UUID()
        pairingId = requestId
        server.requestPairing(id: requestId, phoneName: name, code: code) { [weak self] accepted in
            guard let self, self.state == .awaitingPairingDecision else { return }
            self.pairingId = nil
            if accepted {
                let phone = PairedPhone(id: Handshake.deviceId(of: pub), name: name, publicKey: pub, pairedAt: Date())
                self.server.paired.add(phone)
                self.state = .handshake
                self.sendHandshake(["t": "pair_ok"])
            } else {
                self.sendHandshake(["t": "pair_no"], thenClose: true)
            }
        }
    }

    private func handleHello(_ json: [String: Any]) {
        guard
            let pub = (json["pub"] as? String).flatMap({ Data(base64Encoded: $0) }),
            let eph = (json["eph"] as? String).flatMap({ Data(base64Encoded: $0) }),
            let phoneStatic = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: pub),
            let phoneEphemeral = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: eph)
        else {
            close()
            return
        }
        guard var phone = server.paired.phone(withPublicKey: pub) else {
            // With this Mac's key, so the phone only forgets if it's really its own Mac.
            sendHandshake(["t": "unpaired", "pub": server.identity.publicKey.base64EncodedString()], thenClose: true)
            return
        }
        if let name = json["name"] as? String, !name.isEmpty, name != phone.name {
            phone.name = String(name.prefix(60))
            server.paired.add(phone)
        }

        let macEphemeral = Curve25519.KeyAgreement.PrivateKey()
        do {
            let dh1 = try macEphemeral.sharedSecretFromKeyAgreement(with: phoneEphemeral).data
            let dh2 = try macEphemeral.sharedSecretFromKeyAgreement(with: phoneStatic).data
            let dh3 = try server.identity.privateKey.sharedSecretFromKeyAgreement(with: phoneEphemeral).data
            let macEphPub = macEphemeral.publicKey.rawRepresentation
            let transcript = pub + eph + server.identity.publicKey + macEphPub
            let keys = Handshake.sessionKeys(ikm: dh1 + dh2 + dh3, transcript: transcript)

            sendHandshake([
                "t": "hello_ok",
                "name": server.macName,
                "pub": server.identity.publicKey.base64EncodedString(),
                "eph": macEphPub.base64EncodedString(),
            ])
            channel = SecureChannel(sendKey: keys.macToPhone, receiveKey: keys.phoneToMac)
            self.phone = phone
            state = .awaitingReady
        } catch {
            close()
        }
    }

    // MARK: - Writing

    private func sendHandshake(_ object: [String: Any], thenClose: Bool = false) {
        guard let data = try? HandshakeJSON.encode(object) else { return }
        connection.send(content: Framing.encode(data), completion: .contentProcessed { [weak self] _ in
            if thenClose { self?.close() }
        })
    }

    /// Seals and sends a message. Only valid once the session is established.
    func send(_ message: Message, completion: ((Bool) -> Void)? = nil) {
        guard state == .ready || (state == .awaitingReady && message.type == "ready"),
              let channel,
              let plaintext = try? message.encoded(),
              let sealed = try? channel.seal(plaintext)
        else {
            completion?(false)
            return
        }
        connection.send(content: Framing.encode(sealed), completion: .contentProcessed { [weak self] error in
            if error != nil { self?.close() }
            completion?(error == nil)
        })
    }
}
