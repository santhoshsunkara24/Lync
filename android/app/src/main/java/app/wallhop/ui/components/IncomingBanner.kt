package app.wallhop.ui.components

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wallhop.link.IncomingTransfer
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Easings
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop

/**
 * Files on their way from the Mac: a small banner slides down from the top with what's coming
 * (its thumbnail once the first file is saved), the size and how far along it is, and a thin mint
 * line. It doesn't take over the screen: the phone didn't start this. When the batch lands it goes,
 * and the "received" popup takes over.
 */
@Composable
fun BoxScope.IncomingBanner(incoming: IncomingTransfer?) {
    val context = LocalContext.current
    val reduceMotion = Wallhop.a11y.reduceMotion
    var last by remember { mutableStateOf<IncomingTransfer?>(null) }
    if (incoming != null) last = incoming
    // Progress arrives in steps; a short linear glide makes the line fill steadily between them.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(incoming?.progress, incoming?.batch) {
        val target = incoming?.progress ?: return@LaunchedEffect
        if (reduceMotion || target < progress.value) progress.snapTo(target)
        else progress.animateTo(target, tween(250, easing = LinearEasing))
    }
    AnimatedVisibility(
        visible = incoming != null,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = if (reduceMotion) fadeIn(tween(Durations.Quick))
            else slideInVertically(Springs.materialize()) { -it } + fadeIn(tween(Durations.Quick, easing = Easings.Out)),
        exit = if (reduceMotion) fadeOut(tween(Durations.Quick))
            else slideOutVertically(Springs.dematerialize()) { -it } + fadeOut(tween(Durations.Exit, easing = Easings.Out)),
    ) {
        val t = last ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(22.dp)
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF0E1612))
                .border(1.dp, Color(0xFF5FD9A0).copy(alpha = 0.18f), shape)
                .padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileThumb(t.uris, t.mimes, 40.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From your Mac", style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFEAF6F0), modifier = Modifier.weight(1f))
                    Text(
                        "${Formatter.formatShortFileSize(context, t.totalBytes)} · ${(t.progress * 100).toInt()}%",
                        style = Type.caption1.copy(fontFeatureSettings = "tnum"), color = Color(0xFF8FB5A5),
                    )
                }
                Box(
                    Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).drawBehind {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(BannerTrack, cornerRadius = r)
                        val w = size.width * progress.value.coerceIn(0f, 1f)
                        if (w > 0f) drawRoundRect(BannerFill, size = Size(maxOf(w, size.height), size.height), cornerRadius = r)
                    },
                )
            }
        }
    }
}

private val BannerTrack = Color.White.copy(alpha = 0.08f)
private val BannerFill = Brush.horizontalGradient(listOf(Color(0xFF13875A), Color(0xFF5FD9A0)))
