package app.wallhop.link

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.wallhop.core.Message
import app.wallhop.core.toHex
import java.io.OutputStream
import java.security.MessageDigest

/** Saves files sent from the Mac into Downloads/Wallhop. Called from the link's read loop. */
class IncomingFiles(private val context: Context) {

    private class Incoming(
        val batch: String,
        val name: String,
        val mime: String,
        val uri: Uri,
        val stream: OutputStream,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
    )

    private val files = HashMap<String, Incoming>()
    private val saved = HashMap<String, MutableList<Incoming>>()
    /** Files that couldn't be saved, with why; their chunks are skipped and `file_end` reports it. */
    private val failed = HashMap<String, String>()

    fun batchStart(message: Message) {
        message.string("batch")?.let { saved[it] = mutableListOf() }
    }

    /** One bad file (storage full, a provider error) fails only that file, never the link. */
    fun fileStart(message: Message) {
        val id = message.string("id") ?: return
        val name = sanitize(message.string("name") ?: "File")
        val mime = message.string("mime") ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Lync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        if (uri == null) {
            failed[id] = "couldn't save"
            return
        }
        val stream = runCatching { resolver.openOutputStream(uri) }.getOrNull()
        if (stream == null) {
            runCatching { resolver.delete(uri, null, null) }
            failed[id] = "couldn't save"
            return
        }
        files[id] = Incoming(message.string("batch") ?: id, name, mime, uri, stream)
    }

    fun fileChunk(message: Message) {
        val id = message.string("id") ?: return
        val file = files[id] ?: return
        try {
            file.stream.write(message.body)
            file.digest.update(message.body)
        } catch (_: Exception) {
            discard(id, file, "couldn't save")
        }
    }

    /** Returns an error message, or null when the file was saved. */
    fun fileEnd(message: Message): String? {
        val id = message.string("id") ?: return "unknown file"
        failed.remove(id)?.let { return it }
        val file = files.remove(id) ?: return "unknown file"
        val resolver = context.contentResolver
        if (runCatching { file.stream.close() }.isFailure) {
            runCatching { resolver.delete(file.uri, null, null) }
            return "couldn't save"
        }
        if (file.digest.digest().toHex() != message.string("sha256")?.lowercase()) {
            runCatching { resolver.delete(file.uri, null, null) }
            return "checksum mismatch"
        }
        val published = runCatching {
            resolver.update(file.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }.isSuccess
        if (!published) {
            runCatching { resolver.delete(file.uri, null, null) }
            return "couldn't save"
        }
        saved.getOrPut(file.batch) { mutableListOf() } += file
        return null
    }

    /** Drops a half-written file, removing its pending Downloads entry. */
    private fun discard(id: String, file: Incoming, reason: String) {
        files.remove(id)
        runCatching { file.stream.close() }
        runCatching { context.contentResolver.delete(file.uri, null, null) }
        failed[id] = reason
    }

    /** The files saved in this batch ("3 photos from MacBook Air"), or null if none were. */
    fun batchEnd(message: Message, macName: String): ReceivedBatch? {
        val batch = message.string("batch") ?: return null
        val files = saved.remove(batch).orEmpty()
        if (files.isEmpty()) return null
        return ReceivedBatch(macName, files.map { it.uri }, files.map { it.name }, files.map { it.mime })
    }

    fun cancelAll() {
        for (file in files.values) {
            runCatching { file.stream.close() }
            runCatching { context.contentResolver.delete(file.uri, null, null) }
        }
        files.clear()
        saved.clear()
        failed.clear()
    }

    private fun sanitize(name: String): String =
        name.replace('/', '-').replace('\u0000', ' ').trim().trimStart('.').ifEmpty { "File" }.take(200)
}
