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
        val uri: Uri,
        val stream: OutputStream,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
    )

    private val files = HashMap<String, Incoming>()
    private val saved = HashMap<String, MutableList<Uri>>()

    fun batchStart(message: Message) {
        message.string("batch")?.let { saved[it] = mutableListOf() }
    }

    fun fileStart(message: Message) {
        val id = message.string("id") ?: return
        val name = sanitize(message.string("name") ?: "File")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, message.string("mime") ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Lync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        val stream = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            return
        }
        files[id] = Incoming(message.string("batch") ?: id, name, uri, stream)
    }

    fun fileChunk(message: Message) {
        val file = message.string("id")?.let { files[it] } ?: return
        file.stream.write(message.body)
        file.digest.update(message.body)
    }

    /** Returns an error message, or null when the file was saved. */
    fun fileEnd(message: Message): String? {
        val file = message.string("id")?.let { files.remove(it) } ?: return "unknown file"
        runCatching { file.stream.close() }
        val resolver = context.contentResolver
        if (file.digest.digest().toHex() != message.string("sha256")?.lowercase()) {
            resolver.delete(file.uri, null, null)
            return "checksum mismatch"
        }
        resolver.update(file.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        saved.getOrPut(file.batch) { mutableListOf() } += file.uri
        return null
    }

    /** Returns a summary like "2 files from your Mac", and shows a notification. */
    fun batchEnd(message: Message, macName: String): String? {
        val batch = message.string("batch") ?: return null
        val uris = saved.remove(batch).orEmpty()
        if (uris.isEmpty()) return null
        val summary = "${uris.size} ${if (uris.size == 1) "file" else "files"} from $macName"
        Notifications.filesFromMac(context, summary)
        return summary
    }

    fun cancelAll() {
        for (file in files.values) {
            runCatching { file.stream.close() }
            runCatching { context.contentResolver.delete(file.uri, null, null) }
        }
        files.clear()
        saved.clear()
    }

    private fun sanitize(name: String): String =
        name.replace('/', '-').replace('\u0000', ' ').trim().trimStart('.').ifEmpty { "File" }.take(200)
}
