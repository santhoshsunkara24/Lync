import Foundation

/// Length-prefixed frames: 4-byte big-endian length, then the payload. See docs/PROTOCOL.md.
enum Framing {
    static let maxPayload = 1 << 20
    /// The most this Mac ever sends in one frame: a little under the limit, so a frame is never
    /// rejected by the phone for being a few bytes too big.
    static let maxOutgoingPayload = maxPayload - 64

    static func encode(_ payload: Data) -> Data {
        var data = Data(capacity: payload.count + 4)
        var length = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: &length) { data.append(contentsOf: $0) }
        data.append(payload)
        return data
    }
}

enum FramingError: Error {
    case tooLarge(Int)
}

/// Collects incoming bytes and hands back complete frames.
struct FrameReader {
    private var buffer = Data()

    mutating func push(_ bytes: Data) throws -> [Data] {
        buffer.append(bytes)
        var frames: [Data] = []
        while buffer.count >= 4 {
            let start = buffer.startIndex
            let length = buffer[start..<start + 4].reduce(0) { ($0 << 8) | Int($1) }
            guard length <= Framing.maxPayload else { throw FramingError.tooLarge(length) }
            guard buffer.count >= 4 + length else { break }
            frames.append(Data(buffer[start + 4..<start + 4 + length]))
            buffer.removeSubrange(start..<start + 4 + length)
        }
        return frames
    }
}
