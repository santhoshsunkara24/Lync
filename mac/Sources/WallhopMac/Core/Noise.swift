import CryptoKit
import Foundation

/// Noise_IK_25519_ChaChaPoly_SHA256 (noiseprotocol.org, revision 34): the session handshake on
/// Wi‑Fi and Bluetooth (PROTOCOL.md, v3). The initiator already knows the responder's static key
/// (both sides learn each other's at pairing) and sends its own encrypted, so neither key ever
/// crosses the air in the clear. Checked against the official test vectors (scripts/CryptoCheck).
enum Noise {
    static let protocolName = Data("Noise_IK_25519_ChaChaPoly_SHA256".utf8)
    static let keySize = 32
    static let tagSize = 16

    enum Failure: Error {
        case badMessage
        case wrongOrder
        case nonceExhausted
    }

    /// One direction's key and counter. Nonces are 4 zero bytes and a 64-bit little-endian counter.
    struct Cipher {
        fileprivate(set) var key: SymmetricKey?
        fileprivate(set) var counter: UInt64 = 0

        init(key: SymmetricKey? = nil) { self.key = key }

        mutating func encrypt(ad: Data = Data(), _ plaintext: Data) throws -> Data {
            guard let key else { return plaintext }
            guard counter < .max else { throw Failure.nonceExhausted }
            let box = try ChaChaPoly.seal(plaintext, using: key, nonce: ChaChaPoly.Nonce(data: Self.nonce(counter)), authenticating: ad)
            counter += 1
            return box.ciphertext + box.tag
        }

        mutating func decrypt(ad: Data = Data(), _ ciphertext: Data) throws -> Data {
            guard let key else { return ciphertext }
            guard ciphertext.count >= Noise.tagSize, counter < .max else { throw Failure.badMessage }
            let box = try ChaChaPoly.SealedBox(nonce: ChaChaPoly.Nonce(data: Self.nonce(counter)),
                                               ciphertext: ciphertext.prefix(ciphertext.count - Noise.tagSize),
                                               tag: ciphertext.suffix(Noise.tagSize))
            let plaintext = try ChaChaPoly.open(box, using: key, authenticating: ad)
            counter += 1
            return plaintext
        }

        private static func nonce(_ counter: UInt64) -> Data {
            var data = Data(count: 4)
            withUnsafeBytes(of: counter.littleEndian) { data.append(contentsOf: $0) }
            return data
        }
    }

    /// The chaining key, the handshake hash and the current cipher.
    private struct SymmetricState {
        var ck: Data
        var h: Data
        var cipher = Cipher()

        init() {
            // The name is exactly 32 bytes, so it is the initial hash as it stands.
            h = Noise.protocolName
            ck = h
        }

        mutating func mixHash(_ data: Data) {
            h = Data(SHA256.hash(data: h + data))
        }

        mutating func mixKey(_ ikm: Data) {
            let (next, key) = Noise.hkdf(ck, ikm)
            ck = next
            cipher = Cipher(key: SymmetricKey(data: key))
        }

        mutating func encryptAndHash(_ plaintext: Data) throws -> Data {
            let ciphertext = try cipher.encrypt(ad: h, plaintext)
            mixHash(ciphertext)
            return ciphertext
        }

        mutating func decryptAndHash(_ ciphertext: Data) throws -> Data {
            let plaintext = try cipher.decrypt(ad: h, ciphertext)
            mixHash(ciphertext)
            return plaintext
        }

        func split() -> (Cipher, Cipher) {
            let (k1, k2) = Noise.hkdf(ck, Data())
            return (Cipher(key: SymmetricKey(data: k1)), Cipher(key: SymmetricKey(data: k2)))
        }
    }

    /// HKDF as Noise defines it (two outputs), on HMAC-SHA256.
    static func hkdf(_ chainingKey: Data, _ ikm: Data) -> (Data, Data) {
        let temp = SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(for: ikm, using: SymmetricKey(data: chainingKey))))
        let out1 = Data(HMAC<SHA256>.authenticationCode(for: Data([1]), using: temp))
        let out2 = Data(HMAC<SHA256>.authenticationCode(for: out1 + Data([2]), using: temp))
        return (out1, out2)
    }

    /// One IK handshake: two messages, then two ciphers.
    ///
    ///     <- s
    ///     ...
    ///     -> e, es, s, ss
    ///     <- e, ee, se
    final class Handshake {
        enum Role { case initiator, responder }

        let role: Role
        private var state = SymmetricState()
        private let s: Curve25519.KeyAgreement.PrivateKey
        private var e: Curve25519.KeyAgreement.PrivateKey?
        private var re: Curve25519.KeyAgreement.PublicKey?
        /// The other side's static key: given to the initiator, learned by the responder from message 1.
        private(set) var remoteStatic: Curve25519.KeyAgreement.PublicKey?
        private var step = 0

        /// `remoteStatic` is required for the initiator. `ephemeral` is for test vectors only.
        init(role: Role, prologue: Data, staticKey: Curve25519.KeyAgreement.PrivateKey,
             remoteStatic: Data? = nil, ephemeral: Curve25519.KeyAgreement.PrivateKey? = nil) throws {
            self.role = role
            s = staticKey
            e = ephemeral
            state.mixHash(prologue)
            switch role {
            case .initiator:
                guard let remoteStatic else { throw Failure.wrongOrder }
                let rs = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: remoteStatic)
                self.remoteStatic = rs
                state.mixHash(rs.rawRepresentation)
            case .responder:
                state.mixHash(staticKey.publicKey.rawRepresentation)
            }
        }

        /// The handshake hash once both messages are done (the same on both sides).
        var hash: Data { state.h }
        var isComplete: Bool { step == 2 }

        private func dh(_ key: Curve25519.KeyAgreement.PrivateKey, _ pub: Curve25519.KeyAgreement.PublicKey?) throws -> Data {
            guard let pub else { throw Failure.wrongOrder }
            return try key.sharedSecretFromKeyAgreement(with: pub).withUnsafeBytes { Data($0) }
        }

        /// Message 1 for the initiator, message 2 for the responder.
        func writeMessage(payload: Data = Data()) throws -> Data {
            let ephemeral = e ?? Curve25519.KeyAgreement.PrivateKey()
            e = ephemeral
            var out = ephemeral.publicKey.rawRepresentation
            state.mixHash(out)
            switch (role, step) {
            case (.initiator, 0):
                state.mixKey(try dh(ephemeral, remoteStatic))                       // es
                out += try state.encryptAndHash(s.publicKey.rawRepresentation)      // s
                state.mixKey(try dh(s, remoteStatic))                               // ss
            case (.responder, 1):
                state.mixKey(try dh(ephemeral, re))                                 // ee
                state.mixKey(try dh(ephemeral, remoteStatic))                       // se
            default:
                throw Failure.wrongOrder
            }
            out += try state.encryptAndHash(payload)
            step += 1
            return out
        }

        /// Message 1 for the responder, message 2 for the initiator. Returns the payload.
        func readMessage(_ message: Data) throws -> Data {
            guard message.count >= Noise.keySize else { throw Failure.badMessage }
            let remoteEphemeral = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: message.prefix(Noise.keySize))
            re = remoteEphemeral
            state.mixHash(remoteEphemeral.rawRepresentation)
            var rest = Data(message.dropFirst(Noise.keySize))
            switch (role, step) {
            case (.responder, 0):
                state.mixKey(try dh(s, re))                                         // es
                let sealedStatic = Noise.keySize + Noise.tagSize
                guard rest.count >= sealedStatic else { throw Failure.badMessage }
                let rs = try state.decryptAndHash(rest.prefix(sealedStatic))         // s
                remoteStatic = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: rs)
                rest = Data(rest.dropFirst(sealedStatic))
                state.mixKey(try dh(s, remoteStatic))                               // ss
            case (.initiator, 1):
                guard let e else { throw Failure.wrongOrder }
                state.mixKey(try dh(e, re))                                         // ee
                state.mixKey(try dh(s, re))                                         // se
            default:
                throw Failure.wrongOrder
            }
            let payload = try state.decryptAndHash(rest)
            step += 1
            return payload
        }

        /// The transport ciphers, once both messages are done: (send, receive) for this side.
        func split() throws -> (send: Cipher, receive: Cipher) {
            guard isComplete else { throw Failure.wrongOrder }
            let (c1, c2) = state.split()
            return role == .initiator ? (c1, c2) : (c2, c1)
        }
    }
}
