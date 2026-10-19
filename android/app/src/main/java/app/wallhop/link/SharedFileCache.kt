package app.wallhop.link

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Copies shared files into the app's cache when their read permission can't be passed on. */
object SharedFileCache {
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    fun copy(context: Context, uris: List<Uri>): List<Uri> {
        val folder = File(context.cacheDir, "shared").apply { mkdirs() }
        // Clear out copies from earlier shares.
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MAX_AGE_MS }?.forEach { it.deleteRecursively() }

        return uris.mapNotNull { uri ->
            runCatching {
                val name = displayName(context, uri)
                val target = File(File(folder, System.nanoTime().toString()).apply { mkdirs() }, name)
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(target)
            }.getOrNull()
        }
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
