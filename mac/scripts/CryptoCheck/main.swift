// Checks the link's cryptography against fixed vectors, without a phone:
// - Noise_IK_25519_ChaChaPoly_SHA256 against the official vector (cacophony), handshake and
//   transport messages, both roles;
// - Lync's own v3 vector (docs/vectors/lync-v3.json), which the Android tests check too, so both
//   implementations are known to produce the same bytes;
// - the pairing code and the Bluetooth tag (PROTOCOL.md).
//
//   scripts/crypto-check.sh            # exits non-zero on any mismatch
//   scripts/crypto-check.sh --print    # also prints the Lync vector (to regenerate the JSON)
import CryptoKit
import Foundation

var failures = 0
func check(_ what: String, _ ok: Bool) {
    print("\(ok ? "ok  " : "FAIL") \(what)")
    if !ok { failures += 1 }
}
func hex(_ s: String) -> Data {
    var data = Data(); var i = s.startIndex
    while i < s.endIndex { let j = s.index(i, offsetBy: 2); data.append(UInt8(s[i..<j], radix: 16)!); i = j }
    return data
}
func key(_ s: String) -> Curve25519.KeyAgreement.PrivateKey { try! .init(rawRepresentation: hex(s)) }

// MARK: Noise official vector (cacophony, Noise_IK_25519_ChaChaPoly_SHA256)

do {
    let prologue = hex("4a6f686e2047616c74")
    let iStatic = key("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1")
    let iEph = key("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
    let rStatic = key("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893")
    let rEph = key("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")
    let messages: [(String, String)] = [
        ("4c756477696720766f6e204d69736573", "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944718da798efbcd91528520204f904b9bd6c7413dccdc214d951e15253e39987f18146e8cd0873654207148333479d4d16c289f0294b29960a72f48e0b7bba2e89083169825e59642148d492020664ccf7"),
        ("4d757272617920526f746862617264", "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f1448088435361e70b2ed446e6c9ec387d1d6b3b840f194e373979d241b203c4acafccf5"),
        ("462e20412e20486179656b", "050e9f3c8fac16b68dbce8f8c4bfbf6617c897f9ada4aa29aa19c8"),
        ("4361726c204d656e676572", "344233a6cabb7141d80f3da2fedc311d9646bbb0f505afe403a667"),
        ("4a65616e2d426170746973746520536179", "62cdeeb172ad7ade7aa7d9e069da5790f12331bfa00177787a1d0810c67dc3b2b4"),
        ("457567656e2042f6686d20766f6e2042617765726b", "029bead1b40992327044d409d9a1f3ad8f36c3c452775d557e18bbeb2e8dfcead32d514024"),
    ]
    let initiator = try Noise.Handshake(role: .initiator, prologue: prologue, staticKey: iStatic,
                                        remoteStatic: rStatic.publicKey.rawRepresentation, ephemeral: iEph)
    let responder = try Noise.Handshake(role: .responder, prologue: prologue, staticKey: rStatic, ephemeral: rEph)
    let m1 = try initiator.writeMessage(payload: hex(messages[0].0))
    check("noise IK message 1", m1 == hex(messages[0].1))
    let p1 = try responder.readMessage(m1)
    check("noise IK message 1 payload", p1 == hex(messages[0].0))
    check("noise IK learns initiator static", responder.remoteStatic?.rawRepresentation == iStatic.publicKey.rawRepresentation)
    let m2 = try responder.writeMessage(payload: hex(messages[1].0))
    check("noise IK message 2", m2 == hex(messages[1].1))
    let p2 = try initiator.readMessage(m2)
    check("noise IK message 2 payload", p2 == hex(messages[1].0))
    check("noise IK handshake hash", initiator.hash == hex("0b0f68fb0c27e03ce9b97565995ed4838cc0581b762ef72b062f6a546419fad7")
          && responder.hash == initiator.hash)
    var i = try initiator.split(), r = try responder.split()
    for (n, (payload, cipher)) in messages.dropFirst(2).enumerated() {
        // Transport messages alternate, the initiator first.
        if n % 2 == 0 {
            let sent = try i.send.encrypt(hex(payload))
            let back = try r.receive.decrypt(sent)
            check("noise transport \(n + 3)", sent == hex(cipher) && back == hex(payload))
        } else {
            let sent = try r.send.encrypt(hex(payload))
            let back = try i.receive.decrypt(sent)
            check("noise transport \(n + 3)", sent == hex(cipher) && back == hex(payload))
        }
    }
    // A tampered message 1 must not decrypt.
    var bad = m1; bad[bad.count - 1] ^= 1
    let victim = try Noise.Handshake(role: .responder, prologue: prologue, staticKey: rStatic)
    check("noise IK rejects a tampered message", (try? victim.readMessage(bad)) == nil)
    // Nor one made for a different responder key.
    let stranger = try Noise.Handshake(role: .responder, prologue: prologue, staticKey: key(String(repeating: "55", count: 32)))
    check("noise IK rejects another responder's message", (try? stranger.readMessage(m1)) == nil)
} catch {
    check("noise vector threw \(error)", false)
}

// MARK: Lync v3 vector (shared with the Android tests)

let print_ = CommandLine.arguments.contains("--print")
do {
    // TCP: the phone initiates. BLE: the Mac initiates. Same fixed keys on both paths.
    let phone = key(String(repeating: "11", count: 32)), phoneEph = key(String(repeating: "22", count: 32))
    let mac = key(String(repeating: "33", count: 32)), macEph = key(String(repeating: "44", count: 32))
    let hello = Data(#"{"name":"Pixel","app":"1.0.0"}"#.utf8)
    let helloOk = Data(#"{"name":"MacBook","app":"1.0.0","paired":true}"#.utf8)
    let ready = Data([0, 13]) + Data(#"{"t":"ready"}"#.utf8)

    let p = try Noise.Handshake(role: .initiator, prologue: Handshake.tcpPrologue, staticKey: phone,
                                remoteStatic: mac.publicKey.rawRepresentation, ephemeral: phoneEph)
    let m = try Noise.Handshake(role: .responder, prologue: Handshake.tcpPrologue, staticKey: mac, ephemeral: macEph)
    let t1 = try p.writeMessage(payload: hello); _ = try m.readMessage(t1)
    let t2 = try m.writeMessage(payload: helloOk); _ = try p.readMessage(t2)
    var pc = try p.split()
    let t3 = try pc.send.encrypt(ready)

    let mb = try Noise.Handshake(role: .initiator, prologue: Handshake.blePrologue, staticKey: mac,
                                 remoteStatic: phone.publicKey.rawRepresentation, ephemeral: macEph)
    let pb = try Noise.Handshake(role: .responder, prologue: Handshake.blePrologue, staticKey: phone, ephemeral: phoneEph)
    let b1 = try mb.writeMessage(); _ = try pb.readMessage(b1)
    let b2 = try pb.writeMessage(); _ = try mb.readMessage(b2)
    var mbc = try mb.split()
    let b3 = try mbc.send.encrypt(ready)

    let vector: [String: String] = [
        "tcp_msg1": t1.hex, "tcp_msg2": t2.hex, "tcp_ready_phone": t3.hex, "tcp_hash": p.hash.hex,
        "ble_msg1": b1.hex, "ble_msg2": b2.hex, "ble_ready_mac": b3.hex, "ble_hash": mb.hash.hex,
    ]
    if print_ {
        let json = try JSONSerialization.data(withJSONObject: vector, options: [.prettyPrinted, .sortedKeys])
        print(String(decoding: json, as: UTF8.self))
    }
    let file = URL(fileURLWithPath: "../docs/vectors/lync-v3.json")
    if var saved = try? JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: String] {
        saved["_about"] = nil
        check("lync v3 vector matches docs/vectors/lync-v3.json", saved == vector)
    } else {
        check("docs/vectors/lync-v3.json readable", false)
    }
} catch {
    check("lync vector threw \(error)", false)
}

// MARK: Pairing code and Bluetooth tag (PROTOCOL.md test vectors)

let commit = Handshake.pairingCommit(phonePub: Data(repeating: 1, count: 32), phoneNonce: Data(repeating: 3, count: 16))
check("pairing commit", commit.hex == "6d60c260fc54cb7820ca62a0395b94dd3b4aaef9a65e4df4cd282ae0fd2a20e6")
check("pairing code", Handshake.pairingCode(phonePub: Data(repeating: 1, count: 32), macPub: Data(repeating: 2, count: 32),
                                            phoneNonce: Data(repeating: 3, count: 16), macNonce: Data(repeating: 4, count: 16)) == "817595")
do {
    let phone = key(String(repeating: "11", count: 32)), mac = key(String(repeating: "33", count: 32))
    let shared = try mac.sharedSecretFromKeyAgreement(with: phone.publicKey).withUnsafeBytes { Data($0) }
    let tagKey = BleWire.tagKey(staticShared: shared, phonePub: phone.publicKey.rawRepresentation, macPub: mac.publicKey.rawRepresentation)
    check("bluetooth tag", BleWire.tag(key: tagKey, window: 1_000_000).hex == "eb87fa08c61a1082")
} catch {
    check("tag threw", false)
}

print(failures == 0 ? "all checks passed" : "\(failures) check(s) FAILED")
exit(failures == 0 ? 0 : 1)
