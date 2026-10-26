import CoreServices
import CryptoKit
import Foundation

/// Where files from the phone go. They arrive in a private inbox and move to Downloads › From Phone
/// when the user saves them; anything never saved stays in the inbox for a day.
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

    /// Moves inbox files into Downloads › From Phone. Returns where each one is now and, if its
    /// move failed, why; a file that couldn't be moved stays in the inbox.
    static func save(_ urls: [URL]) -> [(url: URL, error: FileErrorKind?)] {
        urls.map { url in
            guard url.path.hasPrefix(inbox.path) else { return (url, nil) } // Already saved.
            // Cleaned out of the inbox after a day.
            guard FileManager.default.fileExists(atPath: url.path) else { return (url, .missing) }
            let destination = uniqueURL(for: url.lastPathComponent, in: folder)
            do {
                try FileManager.default.moveItem(at: url, to: destination)
                return (destination, nil)
            } catch {
                NSLog("Lync: couldn't save \(url.lastPathComponent) to Downloads: \(error.localizedDescription)")
                return (url, FileErrorKind(error))
            }
        }
    }

    /// Whether `bytes` more fit on the disk with the inbox, with a little to spare.
    static func hasRoom(for bytes: Int64) -> Bool {
        guard bytes > 0,
              let free = try? Storage.directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage, free > 0 else { return true }
        return bytes + 50_000_000 < free
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
    /// `reason`/`code`: the file-ack `err` code sent back to the phone (docs/PROTOCOL.md).
    enum Result {
        case saved(batch: String, url: URL)
        case failed(batch: String, name: String, reason: String)
        /// Part of a batch refused up front; the app already said so.
        case refused(reason: String)
    }

    private struct Incoming {
        let batch: String
        let name: String
        let size: Int64
        let tempURL: URL
        let handle: FileHandle?
        var received: Int64 = 0
        var hasher = SHA256()
        /// Why the file fails (the ack code), once it does: nothing more is written, the rest of
        /// it is dropped. More than `file_start` announced counts as damaged (`checksum`).
        var failure: String?
    }

    private var incoming: [String: Incoming] = [:]
    private var batchBytes: [String: Int64] = [:]
    /// Batches between `batch_start` and `batch_end`.
    private var openBatches: Set<String> = []
    /// Batches turned away at `batch_start`, with the ack code for each of their files.
    private var refusedBatches: [String: String] = [:]

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

    /// The phone cancelled the send: its half-written file goes at once, nothing is left behind.
    /// Files of the batch that already arrived whole are kept.
    func cancelBatch(_ batch: String) {
        for (id, file) in incoming where file.batch == batch {
            discard(file)
            incoming[id] = nil
        }
        openBatches.remove(batch)
        batchBytes[batch] = nil
        refusedBatches[batch] = nil
    }

    /// Refuses a whole batch (e.g. not enough space): its files are acked with `reason` and
    /// nothing is written.
    func refuseBatch(_ batch: String, reason: String) {
        refusedBatches[batch] = reason
    }

    /// False for a refused batch, which the app was already told about.
    @discardableResult
    func endBatch(_ batch: String) -> Bool {
        openBatches.remove(batch)
        batchBytes[batch] = nil
        return refusedBatches.removeValue(forKey: batch) == nil
    }

    func begin(_ message: Message) {
        guard let id = message.string("id") else { return }
        // The same id again: drop the half-written copy and start over.
        if let old = incoming.removeValue(forKey: id) { discard(old) }
        let batch = message.string("batch") ?? id
        let tempURL = Self.tempFolder.appendingPathComponent(UUID().uuidString)
        var handle: FileHandle?
        var failure = refusedBatches[batch]
        if failure == nil {
            FileManager.default.createFile(atPath: tempURL.path, contents: nil)
            do {
                handle = try FileHandle(forWritingTo: tempURL)
            } catch {
                failure = FileErrorKind(error).ackCode
            }
        }
        if refusedBatches[batch] == nil { openBatches.insert(batch) }
        incoming[id] = Incoming(
            batch: batch,
            name: message.string("name") ?? "File",
            size: Int64(max(0, message.int("size") ?? 0)),
            tempURL: tempURL,
            handle: handle,
            failure: failure
        )
    }

    /// Returns the batch and its total bytes received so far.
    func append(_ message: Message) -> (String, Int64)? {
        guard let id = message.string("id"), var file = incoming[id], file.failure == nil, let handle = file.handle else { return nil }
        guard file.received + Int64(message.body.count) <= file.size else {
            // Never write past the announced size.
            fail(&file, id: id, code: PhoneError.checksum)
            return nil
        }
        do {
            try handle.write(contentsOf: message.body)
        } catch {
            // Out of space, or the folder went away: the rest of this file is dropped.
            fail(&file, id: id, code: FileErrorKind(error).ackCode)
            return nil
        }
        file.received += Int64(message.body.count)
        file.hasher.update(data: message.body)
        incoming[id] = file
        let total = (batchBytes[file.batch] ?? 0) + Int64(message.body.count)
        batchBytes[file.batch] = total
        return (file.batch, total)
    }

    private func fail(_ file: inout Incoming, id: String, code: String) {
        file.failure = code
        try? file.handle?.close()
        try? FileManager.default.removeItem(at: file.tempURL)
        incoming[id] = file
    }

    func finish(_ message: Message) -> Result {
        guard let id = message.string("id"), let file = incoming.removeValue(forKey: id) else {
            // Not started here (e.g. the connection was reset in between).
            return .failed(batch: message.string("batch") ?? "", name: "File", reason: PhoneError.cancelled)
        }
        if let refused = refusedBatches[file.batch] { return .refused(reason: refused) }
        if let failure = file.failure {
            return .failed(batch: file.batch, name: file.name, reason: failure)
        }
        try? file.handle?.close()

        let digest = Data(file.hasher.finalize()).hex
        guard digest == message.string("sha256")?.lowercased() else {
            try? FileManager.default.removeItem(at: file.tempURL)
            return .failed(batch: file.batch, name: file.name, reason: PhoneError.checksum)
        }
        let destination = ReceivedFiles.inboxURL(batch: file.batch, name: file.name)
        do {
            try FileManager.default.moveItem(at: file.tempURL, to: destination)
            ReceivedFiles.quarantine(destination)
            return .saved(batch: file.batch, url: destination)
        } catch {
            try? FileManager.default.removeItem(at: file.tempURL)
            return .failed(batch: file.batch, name: file.name, reason: FileErrorKind(error).ackCode)
        }
    }

    /// Drops every unfinished file. Returns the batches that were still open, so the app can
    /// tell the user they were interrupted.
    @discardableResult
    func cancelAll() -> [String] {
        let open = openBatches.union(incoming.values.map(\.batch)).subtracting(refusedBatches.keys)
        for file in incoming.values { discard(file) }
        incoming.removeAll()
        batchBytes.removeAll()
        openBatches.removeAll()
        refusedBatches.removeAll()
        return Array(open)
    }

    private func discard(_ file: Incoming) {
        try? file.handle?.close()
        try? FileManager.default.removeItem(at: file.tempURL)
    }
}

/// How a send to the phone went, file by file.
struct OutgoingResult {
    var total: Int
    /// The files that didn't arrive, and why.
    var failed: [(url: URL, failure: Problem.SendFailure)]

    var ok: Bool { failed.isEmpty }
    var sent: Int { total - failed.count }
}

/// Streams files to the phone in 256 KiB chunks. Runs on the link queue. Reports success only
/// once the phone has acknowledged every file; otherwise which files failed and why.
final class FileSender {
    enum Event {
        case progress(sent: Int64, total: Int64)
        case finished(OutgoingResult)
    }

    private static let chunkSize = 256 * 1024
    /// How long to wait for the phone's acks after the last chunk went out.
    private static let ackTimeout: TimeInterval = 30

    private let batch: String
    private let urls: [URL]
    private let connection: PhoneConnection
    private let queue: DispatchQueue
    private let onEvent: (Event) -> Void
    private let registerAck: (String, @escaping (AckResult) -> Void) -> Void
    private var sent: Int64 = 0
    private var total: Int64 = 0
    /// By index in `urls`: files that failed, and files the phone confirmed.
    private var failures: [Int: Problem.SendFailure] = [:]
    private var delivered: Set<Int> = []
    private var awaitingAcks = 0
    private var allSent = false
    private var done = false
    private var lastReport = Date.distantPast

    init(batch: String, urls: [URL], connection: PhoneConnection, queue: DispatchQueue,
         onEvent: @escaping (Event) -> Void,
         registerAck: @escaping (String, @escaping (AckResult) -> Void) -> Void) {
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

    /// Reports the result once. Every later event is ignored. `stopped`: why the files not
    /// confirmed or failed by then didn't arrive (the connection dropped, or no answer).
    private func finish(stopped: Problem.SendFailure? = nil) {
        guard !done else { return }
        done = true
        var failed: [(url: URL, failure: Problem.SendFailure)] = []
        for (index, url) in urls.enumerated() where !delivered.contains(index) {
            if let failure = failures[index] ?? stopped { failed.append((url, failure)) }
        }
        onEvent(.finished(OutgoingResult(total: urls.count, failed: failed)))
    }

    private func finishIfAcked() {
        guard allSent, awaitingAcks == 0 else { return }
        finish()
    }

    private func sendFile(at index: Int) {
        guard !done else { return }
        guard index < urls.count else {
            connection.send(Message("batch_end", ["batch": batch]))
            allSent = true
            finishIfAcked()
            queue.asyncAfter(deadline: .now() + Self.ackTimeout) { [self] in
                if !done { finish(stopped: .timedOut) }
            }
            return
        }
        let url = urls[index]
        let id = UUID().uuidString
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            failures[index] = .unreadable
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
            // Called on the link queue: by the phone's ack, or `disconnected` if the connection drops.
            self.registerAck(id) { result in
                self.queue.async {
                    self.awaitingAcks -= 1
                    switch result {
                    case .ok: self.delivered.insert(index)
                    case .refused(let code): self.failures[index] = .refused(code)
                    case .timedOut: self.failures[index] = .timedOut
                    case .disconnected:
                        self.failures[index] = .disconnected
                        self.finish(stopped: .disconnected)
                    }
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
                    self.queue.async { self.finish(stopped: .disconnected) }
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

/// Which picked or dropped files Lync can actually read, before sending them.
enum FileAccess {
    struct Check {
        var readable: [URL] = []
        /// No permission (Privacy & Security › Files and Folders), or gone.
        var unreadable: [URL] = []
        /// iCloud files that are only placeholders on this Mac.
        var notDownloaded: [URL] = []
    }

    static func check(_ urls: [URL]) -> Check {
        var check = Check()
        for url in urls {
            let values = try? url.resourceValues(forKeys: [.isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey])
            if values?.isUbiquitousItem == true, let status = values?.ubiquitousItemDownloadingStatus, status != .current {
                check.notDownloaded.append(url)
            } else if FileManager.default.isReadableFile(atPath: url.path) {
                check.readable.append(url)
            } else {
                check.unreadable.append(url)
            }
        }
        return check
    }
}
