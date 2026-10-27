import CryptoKit
import Foundation

/// The Bluetooth LE fallback's keys and framing (docs/PROTOCOL.md, Bluetooth fallback).
/// Must match `BleWire.kt` on the phone; both are checked against the test vector there. The
/// handshake itself is Noise (Noise.swift), as on Wi‑Fi.
enum BleWire {
    static let service = "6C796E63-0001-4B1E-9C3A-5D2F7E8A1B40"
    static let characteristic = "6C796E63-0002-4B1E-9C3A-5D2F7E8A1B40"

    /// The advertised tag changes every 15 minutes, so it can't be used to follow the phone around.
    static let tagWindowSeconds: TimeInterval = 900
    static let tagSize = 8
    /// Largest message after reassembly; anything bigger closes the link.
    static let maxMessage = 16 * 1024

    /// HKDF of DH(phone static, Mac static): only the two paired devices can work it out.
    static func tagKey(staticShared: Data, phonePub: Data, macPub: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: staticShared),
                               salt: Data(SHA256.hash(data: phonePub + macPub)),
                               info: Data("lync ble v1 tag".utf8), outputByteCount: 32)
    }

    static func tag(key: SymmetricKey, window: Int64) -> Data {
        var message = Data("lync-ble-tag".utf8)
        var big = window.bigEndian
        withUnsafeBytes(of: &big) { message.append(contentsOf: $0) }
        return Data(HMAC<SHA256>.authenticationCode(for: message, using: key).prefix(tagSize))
    }

    static func window(_ date: Date) -> Int64 {
        Int64(floor(date.timeIntervalSince1970 / tagWindowSeconds))
    }

    /// Splits a frame into pieces of at most `maxChunk` bytes, each led by 1 (last) or 0 (more follow).
    static func fragment(_ frame: Data, maxChunk: Int) -> [Data] {
        let room = max(maxChunk - 1, 1)
        guard !frame.isEmpty else { return [Data([1])] }
        var pieces: [Data] = []
        var start = frame.startIndex
        while start < frame.endIndex {
            let end = min(start + room, frame.endIndex)
            var piece = Data([end == frame.endIndex ? 1 : 0])
            piece.append(frame[start..<end])
            pieces.append(piece)
            start = end
        }
        return pieces
    }

    enum FrameError: Error { case bad, tooLarge }

    /// Puts fragments back together; one per direction of one connection.
    struct Reassembler {
        private var buffer = Data()

        /// Adds one piece; returns the whole frame once its last piece is in.
        mutating func add(_ chunk: Data) throws -> Data? {
            guard let flags = chunk.first, flags <= 1 else { throw FrameError.bad }
            guard buffer.count + chunk.count - 1 <= BleWire.maxMessage else { throw FrameError.tooLarge }
            buffer.append(chunk.dropFirst())
            guard flags == 1 else { return nil }
            defer { buffer = Data() }
            return buffer
        }
    }
}
