import Foundation

/// A message inside a sealed frame: a small JSON header plus an optional binary body.
struct Message {
    var header: [String: Any]
    var body: Data

    init(_ type: String, _ fields: [String: Any] = [:], body: Data = Data()) {
        var header = fields
        header["t"] = type
        self.header = header
        self.body = body
    }

    private init(header: [String: Any], body: Data) {
        self.header = header
        self.body = body
    }

    var type: String { header["t"] as? String ?? "" }

    func string(_ key: String) -> String? { header[key] as? String }

    func int(_ key: String) -> Int? { (header[key] as? NSNumber)?.intValue }

    func bool(_ key: String) -> Bool? { (header[key] as? NSNumber)?.boolValue }

    var bodyText: String { String(decoding: body, as: UTF8.self) }

    func encoded() throws -> Data {
        let json = try JSONSerialization.data(withJSONObject: header)
        guard json.count <= Int(UInt16.max) else { throw MessageError.headerTooLarge }
        var data = Data(capacity: 2 + json.count + body.count)
        data.append(UInt8(json.count >> 8))
        data.append(UInt8(json.count & 0xFF))
        data.append(json)
        data.append(body)
        return data
    }

    static func decode(_ data: Data) throws -> Message {
        guard data.count >= 2 else { throw MessageError.malformed }
        let start = data.startIndex
        let length = Int(data[start]) << 8 | Int(data[start + 1])
        guard data.count >= 2 + length else { throw MessageError.malformed }
        let json = data[start + 2..<start + 2 + length]
        guard let header = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw MessageError.malformed
        }
        return Message(header: header, body: Data(data[(start + 2 + length)...]))
    }
}

enum MessageError: Error {
    case malformed
    case headerTooLarge
}

/// Plain JSON used before the session is encrypted.
enum HandshakeJSON {
    static func encode(_ object: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: object)
    }

    static func decode(_ data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
