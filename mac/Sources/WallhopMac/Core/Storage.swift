import CryptoKit
import Foundation

/// Files under ~/Library/Application Support/Wallhop.
enum Storage {
    static let directory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        // Development: WALLHOP_HOME runs a separate test copy with its own keys and pairings.
        let url = ProcessInfo.processInfo.environment["WALLHOP_HOME"].map { URL(fileURLWithPath: $0, isDirectory: true) }
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
}

/// This Mac's long-term X25519 key.
///
/// Development builds keep it in a user-only file. Release builds should move it to the
/// Keychain once the app is signed with a stable Developer ID (rebuilt ad-hoc binaries would
/// otherwise trigger Keychain access prompts on every build).
final class Identity {
    let privateKey: Curve25519.KeyAgreement.PrivateKey

    var publicKey: Data { privateKey.publicKey.rawRepresentation }
    var deviceId: String { Handshake.deviceId(of: publicKey) }

    private init(privateKey: Curve25519.KeyAgreement.PrivateKey) {
        self.privateKey = privateKey
    }

    /// The new key couldn't be written: phones paired now won't recognise this Mac after a restart.
    nonisolated(unsafe) private(set) static var saveFailed = false

    static func loadOrCreate() -> Identity {
        let file = "identity.key"
        if let raw = Storage.read(file),
           let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw) {
            return Identity(privateKey: key)
        }
        let key = Curve25519.KeyAgreement.PrivateKey()
        do {
            try Storage.writePrivate(key.rawRepresentation, to: file)
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
