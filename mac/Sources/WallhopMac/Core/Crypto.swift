import CryptoKit
import Foundation
import Security

/// Key derivation and the pairing code. See docs/PROTOCOL.md.
enum Handshake {
    static let infoPhoneToMac = Data("wallhop v1 phone->mac".utf8)
    static let infoMacToPhone = Data("wallhop v1 mac->phone".utf8)

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

    static func sessionKeys(ikm: Data, transcript: Data) -> (phoneToMac: SymmetricKey, macToPhone: SymmetricKey) {
        let salt = Data(SHA256.hash(data: transcript))
        let key = SymmetricKey(data: ikm)
        let p2m = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: salt, info: infoPhoneToMac, outputByteCount: 32)
        let m2p = HKDF<SHA256>.deriveKey(inputKeyMaterial: key, salt: salt, info: infoMacToPhone, outputByteCount: 32)
        return (p2m, m2p)
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

enum ChannelError: Error {
    case tooShort
}

/// Encrypts and decrypts frames for one connection, with counter nonces per direction.
final class SecureChannel {
    private let sendKey: SymmetricKey
    private let receiveKey: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var receiveCounter: UInt64 = 0

    init(sendKey: SymmetricKey, receiveKey: SymmetricKey) {
        self.sendKey = sendKey
        self.receiveKey = receiveKey
    }

    func seal(_ plaintext: Data) throws -> Data {
        let nonce = try ChaChaPoly.Nonce(data: Self.nonce(sendCounter))
        sendCounter += 1
        let box = try ChaChaPoly.seal(plaintext, using: sendKey, nonce: nonce)
        return box.ciphertext + box.tag
    }

    func open(_ sealed: Data) throws -> Data {
        guard sealed.count >= 16 else { throw ChannelError.tooShort }
        let nonce = try ChaChaPoly.Nonce(data: Self.nonce(receiveCounter))
        receiveCounter += 1
        let box = try ChaChaPoly.SealedBox(
            nonce: nonce,
            ciphertext: sealed.prefix(sealed.count - 16),
            tag: sealed.suffix(16)
        )
        return try ChaChaPoly.open(box, using: receiveKey)
    }

    private static func nonce(_ counter: UInt64) -> Data {
        var data = Data(repeating: 0, count: 4)
        var big = counter.bigEndian
        withUnsafeBytes(of: &big) { data.append(contentsOf: $0) }
        return data
    }
}

extension Data {
    /// Base64 without padding, URL-safe: for QR codes.
    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
