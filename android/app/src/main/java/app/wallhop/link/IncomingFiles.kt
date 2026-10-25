package app.wallhop.link

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import app.wallhop.core.Message
import app.wallhop.core.Problems
import app.wallhop.core.toHex
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Saves files sent from the Mac into Downloads/Lync. Called from the link's read loop. A file that
 * can't be saved is reported in its `file_end` ack with a code the Mac can word (PROTOCOL.md, file
 * acks): `storage_full`, `checksum`, `write_failed` or `permission`.
 */
class IncomingFiles(private val context: Context) {

    private class Incoming(
        val batch: String,
        val name: String,
        val mime: String,
        val uri: Uri,
        val stream: OutputStream,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
    )

    /** A batch so far: how many files it should have, the ones saved, and the first failure's code. */
    private class Batch(val expected: Int) {
        val saved = mutableListOf<Incoming>()
        var failed = 0
        var err: String? = null
        /** Not enough free space for the whole batch: every file is turned down straight away. */
        var full = false
    }

    /** How a batch ended: the files saved (null if none), and how many of [total] didn't save, with the first's code. */
    class Finished(val batch: ReceivedBatch?, val total: Int, val failed: Int, val err: String?)

    /** A batch the connection cut off: the files that did save, of [total]. */
    class Stopped(val saved: List<Uri>, val total: Int)

    private val files = HashMap<String, Incoming>()
    private val batches = HashMap<String, Batch>()
    /** Files that couldn't be saved, with their code; their chunks are skipped and `file_end` reports it. */
    private val failed = HashMap<String, String>()
    /** The last file saved (its Downloads entry and type), for the receiving banner's thumbnail. */
    var lastSaved: Pair<Uri, String>? = null
        private set

    fun batchStart(message: Message) {
        val id = message.string("batch") ?: return
        val batch = Batch(message.header.optInt("count", 1))
        // Checked up front against the whole batch, so the Mac hears "storage full" at once
        // instead of after sending most of it.
        val bytes = message.header.optLong("bytes", 0L)
        if (bytes > 0 && bytes + SPACE_MARGIN > freeBytes()) batch.full = true
        batches[id] = batch
    }

    /** One bad file (storage full, a provider error) fails only that file, never the link. */
    fun fileStart(message: Message) {
        val id = message.string("id") ?: return
        val batchId = message.string("batch") ?: id
        val name = sanitize(message.string("name") ?: "File")
        val mime = message.string("mime") ?: "application/octet-stream"
        val size = message.header.optLong("size", -1L)
        if (batches[batchId]?.full == true || (size > 0 && size + SPACE_MARGIN > freeBytes())) {
            fail(id, batchId, "storage_full")
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Lync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val inserted = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
        val uri = inserted.getOrNull()
        if (uri == null) {
            fail(id, batchId, Problems.writeErr(inserted.exceptionOrNull()))
            return
        }
        val opened = runCatching { resolver.openOutputStream(uri) }
        val stream = opened.getOrNull()
        if (stream == null) {
            runCatching { resolver.delete(uri, null, null) }
            fail(id, batchId, Problems.writeErr(opened.exceptionOrNull()))
            return
        }
        files[id] = Incoming(batchId, name, mime, uri, stream)
    }

    fun fileChunk(message: Message) {
        val id = message.string("id") ?: return
        val file = files[id] ?: return
        try {
            file.stream.write(message.body)
            file.digest.update(message.body)
        } catch (e: Exception) {
            discard(id, file, Problems.writeErr(e))
        }
    }

    /** Returns the file's ack code, or null when it was saved. */
    fun fileEnd(message: Message): String? {
        val id = message.string("id") ?: return "write_failed"
        failed.remove(id)?.let { return it }
        val file = files.remove(id) ?: return "write_failed"
        val resolver = context.contentResolver
        val closed = runCatching { file.stream.close() }
        val err = when {
            closed.isFailure -> Problems.writeErr(closed.exceptionOrNull())
            file.digest.digest().toHex() != message.string("sha256")?.lowercase() -> "checksum"
            runCatching {
                resolver.update(file.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }.isFailure -> "write_failed"
            else -> null
        }
        val batch = batches.getOrPut(file.batch) { Batch(1) }
        if (err != null) {
            runCatching { resolver.delete(file.uri, null, null) }
            batch.failed++
            if (batch.err == null) batch.err = err
            return err
        }
        batch.saved += file
        lastSaved = file.uri to file.mime
        return null
    }

    /** Drops a half-written file, removing its pending Downloads entry. */
    private fun discard(id: String, file: Incoming, err: String) {
        files.remove(id)
        runCatching { file.stream.close() }
        runCatching { context.contentResolver.delete(file.uri, null, null) }
        fail(id, file.batch, err)
    }

    private fun fail(id: String, batchId: String, err: String) {
        failed[id] = err
        val batch = batches.getOrPut(batchId) { Batch(1) }
        batch.failed++
        if (batch.err == null) batch.err = err
    }

    /** The files saved in this batch ("3 photos from MacBook Air") and any that weren't. */
    fun batchEnd(message: Message, macName: String): Finished? {
        val id = message.string("batch") ?: return null
        val batch = batches.remove(id) ?: return null
        val saved = batch.saved
        val received = if (saved.isEmpty()) null else ReceivedBatch(macName, saved.map { it.uri }, saved.map { it.name }, saved.map { it.mime })
        return Finished(received, maxOf(batch.expected, saved.size + batch.failed), batch.failed, batch.err)
    }

    /** The connection dropped: half-written files go. Returns the batch that was cut off, if any. */
    fun cancelAll(): Stopped? {
        for (file in files.values) {
            runCatching { file.stream.close() }
            runCatching { context.contentResolver.delete(file.uri, null, null) }
        }
        val cut = batches.values.firstOrNull()?.let { Stopped(it.saved.map { file -> file.uri }, it.expected) }
        files.clear()
        batches.clear()
        failed.clear()
        return cut
    }

    /** Free space where Downloads lives (the phone's shared storage), or "plenty" if it can't be read. */
    private fun freeBytes(): Long = runCatching {
        context.getSystemService(StorageManager::class.java).getAllocatableBytes(StorageManager.UUID_DEFAULT)
    }.getOrDefault(Long.MAX_VALUE / 2)

    private fun sanitize(name: String): String =
        name.replace('/', '-').replace('\u0000', ' ').trim().trimStart('.').ifEmpty { "File" }.take(200)

    private companion object {
        /** Room kept free beyond the files themselves, so saving doesn't fill the phone to the brim. */
        const val SPACE_MARGIN = 50L * 1024 * 1024
    }
}
