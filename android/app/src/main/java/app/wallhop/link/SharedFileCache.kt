package app.wallhop.link

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Copies shared files into the app's cache when their read permission can't be passed on. */
object SharedFileCache {
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** The copies made, and whether any failed because the phone's storage is full. */
    class Copied(val uris: List<Uri>, val storageFull: Boolean)

    fun copy(context: Context, uris: List<Uri>): Copied {
        val folder = File(context.cacheDir, "shared").apply { mkdirs() }
        // Clear out copies from earlier shares.
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MAX_AGE_MS }?.forEach { it.deleteRecursively() }

        var full = false
        val copies = uris.mapNotNull { uri ->
            val target = File(File(folder, System.nanoTime().toString()).apply { mkdirs() }, displayName(context, uri))
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(target)
            }.onFailure { e ->
                runCatching { target.parentFile?.deleteRecursively() } // No half copies left behind.
                if (app.wallhop.core.Problems.writeErr(e) == "storage_full") full = true
            }.getOrNull()
        }
        return Copied(copies, full)
    }

    private fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "File"
        return name.replace('/', '-').trimStart('.').ifEmpty { "File" }
    }
}
