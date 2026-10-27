import CryptoKit
import Foundation
import Security

/// The pairing code, the QR proof and the Noise prologues. See docs/PROTOCOL.md.
enum Handshake {
    /// Noise prologues (PROTOCOL.md, v3): a handshake made for one path can't be used on the other.
    static let tcpPrologue = Data("lync-tcp-v3".utf8)
    static let blePrologue = Data("lync-ble-v3".utf8)

    /// Pairing v2 commitment: the phone commits to its nonce before it sees the Mac's, so neither
    /// side can grind keys or nonces to make the six digits match (PROTOCOL.md, Pairing).
    static func pairingCommit(phonePub: Data, phoneNonce: Data) -> Data {
        var input = Data("lync-pair-commit-v2".utf8)
        input.append(phonePub)
        input.append(phoneNonce)
        return Data(SHA256.hash(data: input))
    }

    /// The 6-digit code both screens show, from both keys and both nonces.
    static func pairingCode(phonePub: Data, macPub: Data, phoneNonce: Data, macNonce: Data) -> String {
        var input = Data("lync-pair-v2".utf8)
        input.append(phonePub)
        input.append(macPub)
        input.append(phoneNonce)
        input.append(macNonce)
        let digest = Array(SHA256.hash(data: input))
        let value = UInt32(digest[0]) << 24 | UInt32(digest[1]) << 16 | UInt32(digest[2]) << 8 | UInt32(digest[3])
        return String(format: "%06u", value % 1_000_000)
    }

    static func randomBytes(_ count: Int) -> Data? {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        return status == errSecSuccess ? data : nil
    }

    /// Constant-time comparison, so timing doesn't leak how much of a secret matched.
    static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    /// Proof that a phone scanned this Mac's pairing QR: an HMAC of both public keys under the
    /// QR's one-time secret. The secret itself never crosses the network (PROTOCOL.md, QR pairing).
    static func qrProof(token: Data, phonePub: Data, macPub: Data) -> Data {
        var message = Data("wallhop-qr-v1".utf8)
        message.append(phonePub)
        message.append(macPub)
        return Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: token)))
    }

    static func deviceId(of publicKey: Data) -> String {
        Data(SHA256.hash(data: publicKey).prefix(8)).hex
    }
}

extension SharedSecret {
    var data: Data { withUnsafeBytes { Data($0) } }
}

/// Encrypts and decrypts frames for one connection: the two ciphers from the Noise handshake,
/// one per direction (PROTOCOL.md, Sealed frames).
final class SecureChannel {
    private var sender: Noise.Cipher
    private var receiver: Noise.Cipher

    init(_ ciphers: (send: Noise.Cipher, receive: Noise.Cipher)) {
        sender = ciphers.send
        receiver = ciphers.receive
    }

    func seal(_ plaintext: Data) throws -> Data { try sender.encrypt(plaintext) }

    func open(_ sealed: Data) throws -> Data { try receiver.decrypt(sealed) }
}

extension Data {
    /// Base64 without padding, URL-safe: for QR codes.
    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
