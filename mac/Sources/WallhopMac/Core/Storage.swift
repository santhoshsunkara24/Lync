import CryptoKit
import Foundation

/// Files under ~/Library/Application Support/Wallhop.
enum Storage {
    static let directory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        // Development: WALLHOP_HOME runs a separate test copy with its own keys and pairings.
        let url = DevEnv.value("WALLHOP_HOME").map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? base.appendingPathComponent("Wallhop", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o700])
        return url
    }()

    /// Writes a file readable only by the current user.
    static func writePrivate(_ data: Data, to name: String) throws {
        let url = directory.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    static func read(_ name: String) -> Data? {
        try? Data(contentsOf: directory.appendingPathComponent(name))
    }

    /// Overwrites a file with zeros, then removes it (an old plain copy of a key).
    static func shred(_ name: String) {
        let url = directory.appendingPathComponent(name)
        if let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int, size > 0,
           let handle = try? FileHandle(forWritingTo: url) {
            try? handle.write(contentsOf: Data(count: size))
            try? handle.synchronize()
            try? handle.close()
        }
        try? FileManager.default.removeItem(at: url)
    }
}

/// Seals a secret with a key that lives in this Mac's Secure Enclave and never leaves it: the
/// sealed file is useless copied to any other machine. ECIES on P-256 (the Enclave's own curve):
/// a one-off key agreement with the Enclave key, HKDF-SHA256, then AES-GCM.
enum EnclaveSeal {
    private static let info = Data("lync-identity-seal-v1".utf8)

    private struct Sealed: Codable {
        var v = 1
        let enclave: Data
        let ephemeral: Data
        let box: Data
    }

    static var available: Bool { SecureEnclave.isAvailable }

    static func seal(_ secret: Data) -> Data? {
        guard available,
              let enclaveKey = try? SecureEnclave.P256.KeyAgreement.PrivateKey() else { return nil }
        let ephemeral = P256.KeyAgreement.PrivateKey()
        guard let shared = try? ephemeral.sharedSecretFromKeyAgreement(with: enclaveKey.publicKey),
              let box = try? AES.GCM.seal(secret, using: key(shared, ephemeral.publicKey)).combined else { return nil }
        return try? JSONEncoder().encode(Sealed(enclave: enclaveKey.dataRepresentation,
                                                ephemeral: ephemeral.publicKey.x963Representation, box: box))
    }

    static func open(_ data: Data) -> Data? {
        guard available,
              let sealed = try? JSONDecoder().decode(Sealed.self, from: data), sealed.v == 1,
              let enclaveKey = try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: sealed.enclave),
              let ephemeral = try? P256.KeyAgreement.PublicKey(x963Representation: sealed.ephemeral),
              let shared = try? enclaveKey.sharedSecretFromKeyAgreement(with: ephemeral),
              let box = try? AES.GCM.SealedBox(combined: sealed.box) else { return nil }
        return try? AES.GCM.open(box, using: key(shared, ephemeral))
    }

    private static func key(_ shared: SharedSecret, _ ephemeral: P256.KeyAgreement.PublicKey) -> SymmetricKey {
        shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(), sharedInfo: info + ephemeral.x963Representation,
                                       outputByteCount: 32)
    }
}

/// This Mac's long-term X25519 key, sealed by the Secure Enclave (`identity.sealed`), so a copy
/// of the file taken off this Mac can't be used to pretend to be it. Macs without a Secure
/// Enclave (older Intel ones) keep it in a user-only file, as earlier builds all did; a plain
/// file from an earlier build is sealed and shredded on first launch.
final class Identity {
    let privateKey: Curve25519.KeyAgreement.PrivateKey

    var publicKey: Data { privateKey.publicKey.rawRepresentation }
    var deviceId: String { Handshake.deviceId(of: publicKey) }

    private init(privateKey: Curve25519.KeyAgreement.PrivateKey) {
        self.privateKey = privateKey
    }

    /// The new key couldn't be written: phones paired now won't recognise this Mac after a restart.
    nonisolated(unsafe) private(set) static var saveFailed = false

    private static let plainFile = "identity.key"
    private static let sealedFile = "identity.sealed"

    static func loadOrCreate() -> Identity {
        if let sealed = Storage.read(sealedFile),
           let raw = EnclaveSeal.open(sealed) ?? EnclaveSeal.open(sealed), // The Enclave, once more if busy.
           let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw) {
            return Identity(privateKey: key)
        }
        if let raw = Storage.read(plainFile),
           let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw) {
            // From an earlier build, in the clear: seal it, then shred the plain copy.
            if let sealed = EnclaveSeal.seal(raw), (try? Storage.writePrivate(sealed, to: sealedFile)) != nil {
                Storage.shred(plainFile)
            }
            return Identity(privateKey: key)
        }
        // A new key (first launch, or a sealed one moved here from another Mac, which only that
        // Mac's Enclave could open: phones then see "a different Mac" and pair again).
        let key = Curve25519.KeyAgreement.PrivateKey()
        do {
            if let sealed = EnclaveSeal.seal(key.rawRepresentation) {
                try Storage.writePrivate(sealed, to: sealedFile)
            } else {
                try Storage.writePrivate(key.rawRepresentation, to: plainFile)
            }
        } catch {
            NSLog("Lync: couldn't save this Mac's key: \(error.localizedDescription)")
            saveFailed = true
        }
        return Identity(privateKey: key)
    }
}

/// A phone the user has paired with this Mac.
struct PairedPhone: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var publicKey: Data
    var pairedAt: Date
}

/// Thread-safe list of paired phones, saved to disk.
final class PairedPhones {
    private let file = "paired-phones.json"
    private let lock = NSLock()
    private var phones: [PairedPhone]
    /// Called (on the caller's thread) when the list couldn't be written to disk.
    var onSaveFailed: (() -> Void)?

    init() {
        if let data = Storage.read(file),
           let saved = try? JSONDecoder().decode([PairedPhone].self, from: data) {
            phones = saved
        } else {
            phones = []
        }
    }

    var all: [PairedPhone] {
        lock.lock(); defer { lock.unlock() }
        return phones
    }

    func phone(withPublicKey key: Data) -> PairedPhone? {
        lock.lock(); defer { lock.unlock() }
        return phones.first { $0.publicKey == key }
    }

    func add(_ phone: PairedPhone) {
        lock.lock()
        phones.removeAll { $0.publicKey == phone.publicKey }
        phones.append(phone)
        let snapshot = phones
        lock.unlock()
        save(snapshot)
    }

    func remove(id: String) {
        lock.lock()
        phones.removeAll { $0.id == id }
        let snapshot = phones
        lock.unlock()
        save(snapshot)
    }

    private func save(_ snapshot: [PairedPhone]) {
        do {
            try Storage.writePrivate(JSONEncoder().encode(snapshot), to: file)
        } catch {
            NSLog("Lync: couldn't save the paired phones: \(error.localizedDescription)")
            onSaveFailed?()
        }
    }
}
