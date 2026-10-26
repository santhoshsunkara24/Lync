package app.wallhop.ui.screens

import androidx.compose.runtime.setValue

import kotlinx.coroutines.launch

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wallhop.link.TransferPhase
import app.wallhop.link.TransferState
import app.wallhop.ui.components.FileThumb
import app.wallhop.ui.components.Liftoff
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Easings
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.tappable

private val Ink = Color(0xFFF1FBF6)
private val Soft = Color(0xFF86A99A)
private val Mint = Color(0xFF5FD9A0)

/**
 * Sending to the Mac: its own screen over Home while photos or files go across. The link lifts off
 * in the middle; under it the percentage with the total size, what's being sent (a thumbnail and its
 * name), and where it's going. Hide (⌄) keeps it sending in the background; Cancel stops it.
 * When it lands it says "On your Mac" and closes by itself. If it fails it stays, saying why, with
 * Try again (or Choose again); while it waits, it says why the Mac isn't there, with its button.
 */
@Composable
fun SendingScreen(
    transfer: TransferState,
    macName: String,
    onHide: () -> Unit,
    onCancel: () -> Unit,
    /** Why the Mac isn't there while this waits (Wi‑Fi off, not on this Wi‑Fi…). */
    waitingFor: app.wallhop.core.Problem? = null,
    /** A problem's button: Try again, Choose again, Wi‑Fi settings… */
    onFix: (app.wallhop.core.Fix) -> Unit = {},
) {
    val context = LocalContext.current
    val done = transfer.phase == TransferPhase.Done
    val waiting = transfer.phase == TransferPhase.Waiting
    val failed = transfer.phase == TransferPhase.Failed
    // Cancel: the link powers down first (about half a second), then the screen goes. Nothing
    // else on the screen can be pressed meanwhile.
    var cancelling by remember { androidx.compose.runtime.mutableStateOf(false) }
    val reduceMotion = app.wallhop.ui.design.Wallhop.a11y.reduceMotion
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun cancel() {
        if (cancelling) return
        if (reduceMotion) { onCancel(); return }
        cancelling = true
        scope.launch {
            kotlinx.coroutines.delay(480)
            onCancel()
        }
    }
    // What the screen says went wrong (failed), or why it's still waiting.
    val problem = if (failed) transfer.problem ?: app.wallhop.core.Problems.MAC_STOPPED_ANSWERING else if (waiting) waitingFor else null
    BackHandler(onBack = onHide)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF07100C), Color(0xFF050807))))
            .background(Brush.radialGradient(listOf(Mint.copy(alpha = 0.14f), Color(0xFF13875A).copy(alpha = 0.05f), Color.Transparent), radius = 1100f))
            .semantics { paneTitle = "Sending to $macName" },
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale(source)
                        .clip(RoundedCornerShape(50))
                        .tappable(source, onClick = onHide)
                        .semantics { contentDescription = "Hide. It keeps sending." },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(WIcons.ChevronDown, null, tint = Color(0xFFD7EAE2), modifier = Modifier.size(16.dp)) }
                }
            }
            Spacer(Modifier.weight(1f))
            Liftoff(
                sending = !waiting && !failed && !cancelling, done = done, failed = failed, cancelled = cancelling,
                modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f),
            )
            // The number, or what happened.
            AnimatedContent(
                targetState = when { cancelling -> "cancelled"; done -> "done"; failed -> "failed"; waiting -> "waiting"; else -> "sending" },
                transitionSpec = {
                    (fadeIn(tween(Durations.Enter, easing = Easings.Out)) + scaleIn(Springs.pop(), initialScale = 0.94f)) togetherWith
                        fadeOut(tween(Durations.Quick, easing = Easings.Out)) using SizeTransform(clip = false)
                },
                label = "sendingHeadline",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) { state ->
                when (state) {
                    "done" -> Text("On your Mac", style = app.wallhop.ui.design.Type.pageTitle.copy(fontSize = 32.sp), color = Ink)
                    "waiting" -> Text("Waiting for your Mac", style = Type.title2, color = Ink)
                    "failed" -> Text("Didn't send", style = Type.title2, color = Ink)
                    "cancelled" -> Text("Cancelled", style = Type.title2, color = Ink)
                    else -> Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${(transfer.progress * 100).toInt()}",
                            style = Type.largeTitle.copy(fontSize = 64.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp,
                                fontFeatureSettings = "tnum"),
                            color = Ink,
                        )
                        Text("%", style = Type.title2.copy(fontSize = 24.sp), color = Color(0xFF8FBFAA), modifier = Modifier.padding(bottom = 8.dp))
                        Text(
                            Formatter.formatShortFileSize(context, transfer.totalBytes),
                            style = Type.title3.copy(fontWeight = FontWeight.Medium), color = Soft,
                            modifier = Modifier.padding(start = 12.dp, bottom = 9.dp),
                        )
                    }
                }
            }
            // What's going.
            Row(
                Modifier
                    .padding(top = 14.dp)
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                    .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FileThumb(transfer.items.map { it.uri }, transfer.items.map { it.mime }, 44.dp)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(itemsTitle(transfer), style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(itemsDetail(transfer), style = Type.caption1, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(WIcons.Laptop, null, tint = Mint, modifier = Modifier.size(18.dp))
                Text("to $macName", style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFE6F4EE), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Why it failed, or why it's waiting: a few words, then what to do.
            if (problem != null) {
                Column(
                    Modifier.padding(top = 18.dp).widthIn(max = 320.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(problem.title, style = Type.headline, color = Ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(problem.action, style = Type.subheadline, color = Soft, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            // The problem's own button (Try again, Wi‑Fi settings…) above Cancel or Close.
            val fix = problem?.fix ?: if (failed) app.wallhop.core.Fix.TryAgain else null
            if (fix != null) {
                val fixSource = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .pressScale(fixSource)
                        .clip(RoundedCornerShape(50))
                        .background(Mint)
                        .tappable(fixSource, onClick = { onFix(fix) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(fix.label, style = Type.headline, color = Color(0xFF062015))
                }
                Spacer(Modifier.height(10.dp))
            }
            val source = remember { MutableInteractionSource() }
            // Cancel turns into Done as the files land: the pill fills with mint and the word
            // cross-fades, instead of both changing in one frame.
            val fill by animateColorAsState(if (done) Mint else Color.White.copy(alpha = 0.08f), tween(Durations.Enter, easing = Easings.Out), label = "pillFill")
            val edge by animateColorAsState(if (done) Mint.copy(alpha = 0f) else Color.White.copy(alpha = 0.08f), tween(Durations.Enter, easing = Easings.Out), label = "pillEdge")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .pressScale(source)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(fill, cornerRadius = r)
                        val line = 1.dp.toPx()
                        drawRoundRect(edge, topLeft = Offset(line / 2, line / 2), size = Size(size.width - line, size.height - line),
                            cornerRadius = CornerRadius((size.height - line) / 2), style = Stroke(line))
                    }
                    .clip(RoundedCornerShape(50))
                    // A failed send has nothing left to cancel: this closes it.
                    .tappable(source, onClick = if (done || failed) onHide else ({ cancel() })),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = done,
                    transitionSpec = { fadeIn(tween(Durations.Enter, easing = Easings.Out)) togetherWith fadeOut(tween(Durations.Quick, easing = Easings.Out)) },
                    label = "pillLabel",
                ) { landed ->
                    Text(if (landed) "Done" else if (failed) "Close" else "Cancel", style = Type.headline, color = if (landed) Color(0xFF062015) else Color(0xFFE6F4EE))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun itemsTitle(t: TransferState): String {
    val first = t.items.firstOrNull() ?: return "Files"
    if (t.items.size == 1) return first.name
    val photos = t.items.all { it.mime.startsWith("image/") }
    val videos = t.items.all { it.mime.startsWith("video/") }
    return "${t.items.size} " + when { photos -> "photos"; videos -> "videos"; else -> "files" }
}

private fun itemsDetail(t: TransferState): String {
    val first = t.items.firstOrNull() ?: return ""
    return when {
        t.items.size > 1 -> "${first.name} and ${t.items.size - 1} more"
        first.mime.startsWith("video/") -> "Video"
        first.mime.startsWith("image/") -> "Photo"
        else -> first.name.substringAfterLast('.', "").uppercase().ifEmpty { "File" }
    }
}
