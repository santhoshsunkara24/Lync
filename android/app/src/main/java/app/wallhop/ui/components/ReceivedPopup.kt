package app.wallhop.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.wallhop.link.Notifications
import app.wallhop.link.ReceivedBatch
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.rounded
import app.wallhop.ui.design.tappable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "3 photos from MacBook Air": files that arrived while Lync is open pop up from the bottom with
 * thumbnails, Open and Share. Goes by itself after about 6 s (longer with TalkBack; never while
 * a finger is on it), or with a swipe down. It doesn't block the screen: taps elsewhere go
 * straight through to the app. [bottom] keeps it clear of the tab bar.
 */
@Composable
fun BoxScope.ReceivedPopup(batch: ReceivedBatch?, onDismiss: () -> Unit, bottom: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    var shown by remember { mutableStateOf(batch) }
    if (batch != null) shown = batch
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(batch) {
        if (batch != null) {
            var left = recommendedTimeout(context, 6_000, controls = true)
            while (left > 0) {
                delay(100)
                if (held) left = maxOf(left, 1_500) else left -= 100
            }
            onDismiss()
        }
    }
    val drag = rememberSwipeDismiss(shown) { onDismiss() }
    AnimatedVisibility(
        batch != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduceMotion) fadeIn(tween(Durations.Quick)) else slideInVertically(Springs.sheetIn()) { it } + fadeIn(),
        // Swiped away, it has already left at the finger's speed.
        exit = if (drag.gone) androidx.compose.animation.ExitTransition.None
            else if (reduceMotion) fadeOut(tween(Durations.Quick)) else slideOutVertically(Springs.dematerialize()) { it } + fadeOut(),
    ) {
        val current = shown ?: return@AnimatedVisibility
        val dragState = rememberDraggableState { delta -> drag.drag(delta) }
        val announce = current.summary.replaceFirst(" from ", " received from ")
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = bottom)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .offset { IntOffset(0, drag.offset.roundToInt()) }
                .onSizeChanged { drag.extent = it.height.toFloat() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        held = true
                        do { val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial) }
                        while (event.changes.any { it.pressed })
                        held = false
                    }
                }
                .draggable(
                    dragState, Orientation.Vertical,
                    onDragStarted = { drag.grab() },
                    onDragStopped = { velocity -> drag.release(velocity, onDismiss, commitAt = 0.35f) },
                )
                .clip(rounded(28.dp))
                .background(colors.backgroundElevated)
                .semantics {
                    paneTitle = announce
                    liveRegion = LiveRegionMode.Polite
                }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ThumbnailStack(current.uris)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = announce }) {
                    Text(current.summary, style = Type.headline, color = colors.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (current.uris.size == 1) current.names.first() else "Saved to Downloads › Lync",
                        style = Type.footnote, color = colors.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WButton("Open", {
                    onDismiss()
                    val intent = if (current.uris.size == 1) Notifications.viewIntent(current.uris.first(), current.mimes.firstOrNull())
                        else Notifications.downloadsIntent()
                    runCatching { context.startActivity(intent) }
                }, style = ButtonStyle.Filled, modifier = Modifier.weight(1f))
                WButton("Share", {
                    onDismiss()
                    runCatching { context.startActivity(Notifications.shareChooser(current.uris, current.mimes)) }
                }, style = ButtonStyle.Glass, modifier = Modifier.weight(1f))
            }
        }
    }
}
