package app.wallhop.ui.components

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.WIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What's being sent, at a glance: the file's own thumbnail (photos and videos), with a play mark and
 * the length on a video, a fanned stack for several photos, or a tile with the file type's icon.
 * [uris] may be empty while a file is still on its way; then only [mimes] decides the tile.
 */
@Composable
fun FileThumb(uris: List<Uri>, mimes: List<String>, size: Dp, modifier: Modifier = Modifier) {
    val firstMime = mimes.firstOrNull().orEmpty()
    val photos = uris.size > 1 && mimes.take(3).all { it.startsWith("image/") }
    if (photos) {
        // Up to three, fanned like a little pile of prints.
        Box(modifier.size(size + size * 0.18f, size)) {
            uris.take(3).reversed().forEachIndexed { i, uri ->
                val angle = listOf(-8f, 4f, -1f).getOrElse(i) { 0f }
                SingleThumb(uri, "image/*", size * 0.86f, Modifier.offset(x = size * 0.09f * i, y = size * 0.07f).rotate(angle))
            }
        }
    } else {
        SingleThumb(uris.firstOrNull(), firstMime, size, modifier)
    }
}

@Composable
private fun SingleThumb(uri: Uri?, mime: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(48)
    val visual = mime.startsWith("image/") || mime.startsWith("video/")
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null || !visual) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(px * 2, px * 2), null).asImageBitmap() }.getOrNull()
        }
    }
    val duration by produceState<String?>(null, uri) {
        value = if (uri == null || !mime.startsWith("video/")) null else withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ms ->
                        val s = ms / 1000
                        if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
                    }
                }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(size * 0.25f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Color(0xFF1C2622))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(
                when {
                    mime.startsWith("image/") -> WIcons.Image
                    else -> WIcons.File
                },
                null, tint = Color(0xFF9FE6C3), modifier = Modifier.size(size * 0.45f),
            )
        }
        if (mime.startsWith("video/")) {
            // A play mark, and the length when it's known.
            Box(Modifier.size(size * 0.42f).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = (size.value * 0.16f).sp, modifier = Modifier.offset(x = size * 0.015f))
            }
            duration?.let {
                Text(
                    it, color = Color.White,
                    style = Type.caption2.copy(fontSize = (size.value * 0.17f).coerceAtLeast(8f).sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                        .clip(RoundedCornerShape(3.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
        }
    }
}
