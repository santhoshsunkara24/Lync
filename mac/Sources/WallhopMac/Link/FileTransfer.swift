import CoreServices
import CryptoKit
import Foundation

/// Where files from the phone go. They land in a private inbox and on the clipboard; they reach
/// Downloads › From Phone only when the user saves them. The inbox empties itself after a day.
enum ReceivedFiles {
    /// ~/Library/Application Support/Wallhop/Inbox/<batch>/<name>
    static var inbox: URL {
        let url = Storage.directory.appendingPathComponent("Inbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        return url
    }

    static func inboxURL(batch: String, name: String) -> URL {
        let folder = inbox.appendingPathComponent(sanitize(batch), isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return uniqueURL(for: name, in: folder)
    }

    /// Removes inbox batches older than `age` (files the user never saved).
    static func cleanInbox(olderThan age: TimeInterval = 24 * 60 * 60) {
        let fm = FileManager.default
        guard let batches = try? fm.contentsOfDirectory(at: inbox, includingPropertiesForKeys: [.creationDateKey]) else { return }
        for batch in batches {
            let created = (try? batch.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
            if Date().timeIntervalSince(created) > age { try? fm.removeItem(at: batch) }
        }
    }

    /// Moves inbox files into Downloads › From Phone. Returns where each one is now and whether
    /// its move worked; a file that couldn't be moved stays in the inbox.
    static func save(_ urls: [URL]) -> [(url: URL, ok: Bool)] {
        urls.map { url in
            guard url.path.hasPrefix(inbox.path) else { return (url, true) } // Already saved.
            let destination = uniqueURL(for: url.lastPathComponent, in: folder)
            do {
                try FileManager.default.moveItem(at: url, to: destination)
                return (destination, true)
            } catch {
                NSLog("Lync: couldn't save \(url.lastPathComponent) to Downloads: \(error.localizedDescription)")
                return (url, false)
            }
        }
    }

    /// Marks a file as downloaded by Lync, so Gatekeeper checks it before it's opened.
    static func quarantine(_ url: URL) {
        var values = URLResourceValues()
        values.quarantineProperties = [
            kLSQuarantineAgentNameKey as String: "Lync",
            kLSQuarantineTypeKey as String: kLSQuarantineTypeOtherDownload as String,
        ]
        var target = url
        try? target.setResourceValues(values)
    }

    static var folder: URL {
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        let url = downloads.appendingPathComponent("From Phone", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// `name`, or `name 2`, `name 3`… if that file already exists.
    static func uniqueURL(for name: String, in folder: URL = folder) -> URL {
        let safe = sanitize(name)
        let base = (safe as NSString).deletingPathExtension
        let ext = (safe as NSString).pathExtension
        var candidate = folder.appendingPathComponent(safe)
        var index = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            let numbered = ext.isEmpty ? "\(base) \(index)" : "\(base) \(index).\(ext)"
            candidate = folder.appendingPathComponent(numbered)
            index += 1
        }
        return candidate
    }

    /// Strips path separators and control characters so a name can't escape the folder.
    static func sanitize(_ name: String) -> String {
        let cleaned = name
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
            .filter { !$0.isNewline && $0.asciiValue.map { $0 >= 0x20 } ?? true }
            .trimmingCharacters(in: .whitespaces)
        let trimmed = cleaned.hasPrefix(".") ? "_" + cleaned.dropFirst() : cleaned
        return trimmed.isEmpty ? "File" : String(trimmed.prefix(200))
    }
}

/// Receives files from the phone into a temporary file, checks the hash, then moves them into place.
/// One per connection; runs on the link queue.
final class FileReceiver {
    enum Result {
        case saved(batch: String, url: URL)
        case failed(batch: String, name: String, reason: String)
    }

    private struct Incoming {
        let batch: String
        let name: String
        let size: Int64
        let tempURL: URL
        let handle: FileHandle
        var received: Int64 = 0
        var hasher = SHA256()
        /// More arrived than `file_start` announced; the rest is dropped and the file fails.
        var overflowed = false
    }

    private var incoming: [String: Incoming] = [:]
    private var batchBytes: [String: Int64] = [:]
    /// Batches between `batch_start` and `batch_end`.
    private var openBatches: Set<String> = []

    static var tempFolder: URL {
        let url = Storage.directory.appendingPathComponent("incoming", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Removes partial downloads left behind by a crash or a quit mid-transfer. Call at launch only.
    static func cleanTempFolder() {
        let fm = FileManager.default
        for url in (try? fm.contentsOfDirectory(at: tempFolder, includingPropertiesForKeys: nil)) ?? [] {
            try? fm.removeItem(at: url)
        }
    }

    func startBatch(_ batch: String) {
        openBatches.insert(batch)
    }

    func endBatch(_ batch: String) {
        openBatches.remove(batch)
        batchBytes[batch] = nil
    }

    func begin(_ message: Message) {
        guard let id = message.string("id") else { return }
        // The same id again: drop the half-written copy and start over.
        if let old = incoming.removeValue(forKey: id) { discard(old) }
        let tempURL = Self.tempFolder.appendingPathComponent(UUID().uuidString)
        FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: tempURL) else { return }
        let batch = message.string("batch") ?? id
        openBatches.insert(batch)
        incoming[id] = Incoming(
            batch: batch,
            name: message.string("name") ?? "File",
            size: Int64(max(0, message.int("size") ?? 0)),
            tempURL: tempURL,
            handle: handle
        )
    }

    /// Returns the batch and its total bytes received so far.
    func append(_ message: Message) -> (String, Int64)? {
        guard let id = message.string("id"), var file = incoming[id], !file.overflowed else { return nil }
        guard file.received + Int64(message.body.count) <= file.size else {
            // Never write past the announced size.
            file.overflowed = true
            try? file.handle.close()
            try? FileManager.default.removeItem(at: file.tempURL)
            incoming[id] = file
            return nil
        }
        do {
            try file.handle.write(contentsOf: message.body)
        } catch {
            return nil
        }
        file.received += Int64(message.body.count)
        file.hasher.update(data: message.body)
        incoming[id] = file
        let total = (batchBytes[file.batch] ?? 0) + Int64(message.body.count)
        batchBytes[file.batch] = total
        return (file.batch, total)
    }

    func finish(_ message: Message) -> Result {
        guard let id = message.string("id"), let file = incoming.removeValue(forKey: id) else {
            return .failed(batch: message.string("batch") ?? "", name: "File", reason: "unknown file")
        }
        guard !file.overflowed else {
            return .failed(batch: file.batch, name: file.name, reason: "larger than announced")
        }
        try? file.handle.close()

        let digest = Data(file.hasher.finalize()).hex
        guard digest == message.string("sha256")?.lowercased() else {
            try? FileManager.default.removeItem(at: file.tempURL)
            return .failed(batch: file.batch, name: file.name, reason: "checksum mismatch")
        }
        let destination = ReceivedFiles.inboxURL(batch: file.batch, name: file.name)
        do {
            try FileManager.default.moveItem(at: file.tempURL, to: destination)
            ReceivedFiles.quarantine(destination)
            return .saved(batch: file.batch, url: destination)
        } catch {
            try? FileManager.default.removeItem(at: file.tempURL)
            return .failed(batch: file.batch, name: file.name, reason: error.localizedDescription)
        }
    }

    /// Drops every unfinished file. Returns the batches that were still open, so the app can
    /// tell the user they were interrupted.
    @discardableResult
    func cancelAll() -> [String] {
        let open = openBatches.union(incoming.values.map(\.batch))
        for file in incoming.values { discard(file) }
        incoming.removeAll()
        batchBytes.removeAll()
        openBatches.removeAll()
        return Array(open)
    }

    private func discard(_ file: Incoming) {
        try? file.handle.close()
        try? FileManager.default.removeItem(at: file.tempURL)
    }
}

/// Streams files to the phone in 256 KiB chunks. Runs on the link queue. Reports success only
/// once the phone has acknowledged every file.
final class FileSender {
    enum Event {
        case progress(sent: Int64, total: Int64)
        case finished(ok: Bool)
    }

    private static let chunkSize = 256 * 1024
    /// How long to wait for the phone's acks after the last chunk went out.
    private static let ackTimeout: TimeInterval = 30

    private let batch: String
    private let urls: [URL]
    private let connection: PhoneConnection
    private let queue: DispatchQueue
    private let onEvent: (Event) -> Void
    private let registerAck: (String, @escaping (Bool) -> Void) -> Void
    private var sent: Int64 = 0
    private var total: Int64 = 0
    private var failures = 0
    private var awaitingAcks = 0
    private var allSent = false
    private var done = false
    private var lastReport = Date.distantPast

    init(batch: String, urls: [URL], connection: PhoneConnection, queue: DispatchQueue,
         onEvent: @escaping (Event) -> Void,
         registerAck: @escaping (String, @escaping (Bool) -> Void) -> Void) {
        self.batch = batch
        self.urls = urls
        self.connection = connection
        self.queue = queue
        self.onEvent = onEvent
        self.registerAck = registerAck
    }

    func start() {
        total = urls.reduce(0) { $0 + Self.size(of: $1) }
        connection.send(Message("batch_start", ["batch": batch, "count": urls.count, "bytes": total]))
        sendFile(at: 0)
    }

    /// Reports the result once. Every later event is ignored.
    private func finish(ok: Bool) {
        guard !done else { return }
        done = true
        onEvent(.finished(ok: ok))
    }

    private func finishIfAcked() {
        guard allSent, awaitingAcks == 0 else { return }
        finish(ok: failures == 0)
    }

    private func sendFile(at index: Int) {
        guard !done else { return }
        guard index < urls.count else {
            connection.send(Message("batch_end", ["batch": batch]))
            allSent = true
            finishIfAcked()
            queue.asyncAfter(deadline: .now() + Self.ackTimeout) { [self] in
                if !done { finish(ok: false) }
            }
            return
        }
        let url = urls[index]
        let id = UUID().uuidString
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            failures += 1
            sendFile(at: index + 1)
            return
        }
        connection.send(Message("file_start", [
            "id": id, "batch": batch, "name": url.lastPathComponent,
            "size": Self.size(of: url), "mime": Self.mimeType(of: url),
        ]))
        var hasher = SHA256()
        sendChunks(handle: handle, id: id, hasher: &hasher) { [weak self] hash in
            guard let self else { return }
            try? handle.close()
            self.awaitingAcks += 1
            // Called on the link queue: by the phone's ack, or with false if the connection drops.
            self.registerAck(id) { ok in
                self.queue.async {
                    self.awaitingAcks -= 1
                    if !ok { self.failures += 1 }
                    self.finishIfAcked()
                }
            }
            self.connection.send(Message("file_end", ["id": id, "sha256": hash]))
            self.sendFile(at: index + 1)
        }
    }

    /// Sends one chunk, waits until the network has taken it, then sends the next.
    private func sendChunks(handle: FileHandle, id: String, hasher: inout SHA256, done: @escaping (String) -> Void) {
        var localHasher = hasher
        func next() {
            guard let chunk = try? handle.read(upToCount: Self.chunkSize), !chunk.isEmpty else {
                done(Data(localHasher.finalize()).hex)
                return
            }
            localHasher.update(data: chunk)
            connection.send(Message("file_chunk", ["id": id], body: chunk)) { [weak self] ok in
                guard let self else { return }
                guard ok else {
                    try? handle.close()
                    self.queue.async { self.finish(ok: false) }
                    return
                }
                self.queue.async {
                    guard !self.done else { try? handle.close(); return }
                    self.sent += Int64(chunk.count)
                    if Date().timeIntervalSince(self.lastReport) > 0.1 {
                        self.lastReport = Date()
                        self.onEvent(.progress(sent: self.sent, total: self.total))
                    }
                    next()
                }
            }
        }
        next()
    }

    static func size(of url: URL) -> Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }

    static func mimeType(of url: URL) -> String {
        (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType?.preferredMIMEType) ?? "application/octet-stream"
    }
}
