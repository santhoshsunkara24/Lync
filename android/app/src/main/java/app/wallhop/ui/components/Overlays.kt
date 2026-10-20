package app.wallhop.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import app.wallhop.ui.design.GlassVariant
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Radius
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.glass
import app.wallhop.ui.design.pressHighlight
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.rounded
import app.wallhop.ui.design.tappable
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Sheets, menus, alerts, action sheets and toasts. See docs/DESIGN.md §4.2 (materialization),
// §5.10–5.14.

class SheetSpec(
    val title: String?,
    val leading: (@Composable () -> Unit)?,
    val trailing: (@Composable () -> Unit)?,
    /** Tall sheets attach to the screen edges and become opaque, like iOS at the large detent. */
    val large: Boolean,
    /** Called when the sheet is closed without its main action: Cancel, a swipe, a tap outside, back. */
    val onDismiss: (() -> Unit)?,
    val content: @Composable ColumnScope.() -> Unit,
)

/** [onBack] runs when back closes the alert; by default back just closes it. */
class AlertSpec(val title: String, val message: String?, val actions: List<AlertAction>, val onBack: (() -> Unit)? = null)

data class AlertAction(val label: String, val destructive: Boolean = false, val default: Boolean = false, val onClick: () -> Unit = {})

class ActionSheetSpec(val message: String?, val actions: List<AlertAction>, val cancel: String = "Cancel")

data class MenuItem(val label: String, val icon: ImageVector? = null, val destructive: Boolean = false, val onClick: () -> Unit)

/** A menu that grows out of the button at [anchor] (root coordinates). */
class MenuSpec(val anchor: Rect, val items: List<MenuItem>)

/** One button on a toast, e.g. "Undo". */
class ToastAction(val label: String, val onClick: () -> Unit)

data class ToastSpec(
    val text: String,
    val icon: ImageVector?,
    val tint: Color? = null,
    val action: ToastAction? = null,
    val id: Long = System.nanoTime(),
)

/** Everything that floats above the app. One instance at the app root. */
@Stable
class Overlays {
    var sheet by mutableStateOf<SheetSpec?>(null); private set
    var alert by mutableStateOf<AlertSpec?>(null); private set
    var actionSheet by mutableStateOf<ActionSheetSpec?>(null); private set
    var menu by mutableStateOf<MenuSpec?>(null); private set
    var toast by mutableStateOf<ToastSpec?>(null); private set

    /** Something modal is up: the screen behind is hidden from TalkBack until it closes. */
    val modal: Boolean get() = sheet != null || alert != null || actionSheet != null || menu != null

    fun showSheet(
        title: String? = null,
        large: Boolean = false,
        leading: (@Composable () -> Unit)? = null,
        trailing: (@Composable () -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        sheet = SheetSpec(title, leading, trailing, large, onDismiss, content)
    }

    /** Closes the sheet. [cancelled] false when its main action closed it, so onDismiss doesn't run. */
    fun dismissSheet(cancelled: Boolean = true) {
        val closing = sheet
        sheet = null
        if (cancelled) closing?.onDismiss?.invoke()
    }
    fun showAlert(spec: AlertSpec) { alert = spec }
    fun dismissAlert() { alert = null }
    fun showActionSheet(spec: ActionSheetSpec) { actionSheet = spec }
    fun dismissActionSheet() { actionSheet = null }
    fun showMenu(anchor: Rect, items: List<MenuItem>) { menu = MenuSpec(anchor, items) }
    fun dismissMenu() { menu = null }
    fun toast(text: String, icon: ImageVector? = null, tint: Color? = null, action: ToastAction? = null) {
        toast = ToastSpec(text, icon, tint, action)
    }
    internal fun clearToast(id: Long) { if (toast?.id == id) toast = null }
}

val LocalOverlays = staticCompositionLocalOf<Overlays?> { null }

/**
 * How long something with a timeout should stay: Android's recommended time for the user's
 * accessibility settings (longer with TalkBack or "Time to take action"), at least [ms].
 */
fun recommendedTimeout(context: android.content.Context, ms: Long, controls: Boolean = false): Long {
    val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return ms
    val flags = android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
        android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS or
        (if (controls) android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS else 0)
    return manager.getRecommendedTimeoutMillis(ms.toInt(), flags).toLong().coerceAtLeast(ms)
}

/** Takes focus once when it appears, so TalkBack moves into a dialog rather than staying behind it. */
@Composable
private fun Modifier.focusOnShow(): Modifier {
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150) // After the enter transition has placed it.
        runCatching { requester.requestFocus() }
    }
    return focusRequester(requester).focusable()
}

/** How glass materializes: refraction and light ramp up rather than a plain fade. */
private val materialize = Springs.materialize<Float>()
private val dematerialize = Springs.dematerialize<Float>()

/** Slides for things entering and leaving; plain fades with Reduce Motion (DESIGN.md §7.3). */
private fun slideUpIn(reduceMotion: Boolean, from: (Int) -> Int) =
    if (reduceMotion) fadeIn(tween(Durations.Quick)) else slideInVertically(Springs.smooth(), from)

private fun slideDownOut(reduceMotion: Boolean) =
    if (reduceMotion) fadeOut(tween(Durations.Quick)) else slideOutVertically(Springs.smooth()) { it }

@Composable
fun BoxScope.OverlayHost(overlays: Overlays) {
    val dimmed = overlays.sheet != null || overlays.alert != null || overlays.actionSheet != null

    AnimatedVisibility(dimmed, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (overlays.alert != null) 0.2f else 0.25f))
                .tappable(source) {
                    when {
                        overlays.alert != null -> Unit // Alerts need an answer.
                        overlays.actionSheet != null -> overlays.dismissActionSheet()
                        else -> overlays.dismissSheet()
                    }
                },
        )
    }

    SheetLayer(overlays)
    ActionSheetLayer(overlays)
    MenuLayer(overlays)
    AlertLayer(overlays)
    ToastLayer(overlays)
}

// MARK: - Sheet

@Composable
private fun BoxScope.SheetLayer(overlays: Overlays) {
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    var shown by remember { mutableStateOf<SheetSpec?>(null) }
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(overlays.sheet) {
        if (overlays.sheet != null) shown = overlays.sheet
        visible.targetState = overlays.sheet != null
    }
    if (visible.isIdle && !visible.currentState && overlays.sheet == null) shown = null
    val spec = shown ?: return
    BackHandler(enabled = overlays.sheet != null) { overlays.dismissSheet() }

    AnimatedVisibility(
        visibleState = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideUpIn(reduceMotion) { it / 2 },
        exit = slideDownOut(reduceMotion),
    ) {
        // Fully drawn as soon as it's on screen; the enter/exit transition does the motion.
        // (An animated "appear" here could stop short on some phones and leave the sheet
        // half see-through, blended with the screen behind it.)
        val appear = 1f
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        var height by remember { mutableStateOf(1) }
        val dragState = rememberDraggableState { delta ->
            scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
        }
        val shape = rounded(Radius.sheet)
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (spec.large) Modifier else Modifier.padding(horizontal = 8.dp))
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = if (spec.large) 0.dp else 8.dp)
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .onSizeChanged { height = it.height }
                // Always a solid surface: the slide is the entrance.
                .clip(shape)
                .background(colors.backgroundElevated)
                .then(if (spec.large) Modifier else Modifier.border(0.5.dp, colors.separator, shape))
                .semantics {
                    paneTitle = spec.title ?: "Sheet"
                    dialog()
                }
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (drag.value > height * 0.25f || velocity > 1800f) {
                            overlays.dismissSheet()
                            drag.snapTo(0f)
                        } else {
                            drag.animateTo(0f, Springs.bouncy())
                        }
                    },
                )
                
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(36.dp, 5.dp)
                    .clip(capsule())
                    .background(colors.labelTertiary),
            )
            if (spec.title != null || spec.leading != null || spec.trailing != null) {
                Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) { spec.leading?.invoke() }
                    if (spec.title != null) {
                        Text(spec.title, style = Type.title3, color = colors.label,
                            modifier = Modifier.align(Alignment.Center).semantics { heading() })
                    }
                    Box(Modifier.align(Alignment.CenterEnd)) { spec.trailing?.invoke() }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), content = spec.content)
        }
    }
}

// MARK: - Menu that morphs out of its button

@Composable
private fun BoxScope.MenuLayer(overlays: Overlays) {
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    var shown by remember { mutableStateOf<MenuSpec?>(null) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(overlays.menu) {
        val target = overlays.menu
        if (target != null) {
            shown = target
            haptics.perform(Haptic.Light)
            if (reduceMotion) progress.snapTo(1f) else progress.animateTo(1f, Springs.menu())
        } else if (shown != null) {
            if (reduceMotion) progress.snapTo(0f) else progress.animateTo(0f, Springs.dematerialize())
            shown = null
        }
    }
    val spec = shown ?: return
    BackHandler(enabled = overlays.menu != null) { overlays.dismissMenu() }

    // Tapping anywhere else closes it; menus don't dim the screen.
    val outside = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().tappable(outside) { overlays.dismissMenu() })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val p = progress.value
        val anchor = with(density) {
            androidx.compose.ui.geometry.Rect(spec.anchor.left.toDp().value, spec.anchor.top.toDp().value, spec.anchor.right.toDp().value, spec.anchor.bottom.toDp().value)
        }
        val menuWidth = 250.dp
        // Rows grow with the font size, so large text never clips.
        val rowHeight = 48.dp * density.fontScale.coerceAtLeast(1f)
        // A thin line sets a destructive item (e.g. "Forget this Mac") apart from the rest.
        val separatorAt = spec.items.indexOfFirst { it.destructive }.takeIf { it > 0 }
        val separator = if (separatorAt != null) 9.dp else 0.dp
        val menuHeight = rowHeight * spec.items.size + 12.dp + separator
        val width = lerp(anchor.width.dp, menuWidth, p)
        val height = lerp(anchor.height.dp, menuHeight, p)
        val radius = lerp((anchor.height / 2).dp, Radius.l, p.coerceIn(0f, 1f))
        // Grow away from the nearest screen edge, keeping the button's corner fixed.
        val growsLeft = anchor.center.x > maxWidth.value / 2
        val x = if (growsLeft) anchor.right.dp - width else anchor.left.dp
        val y = anchor.top.dp
        Column(
            Modifier
                .offset(x = x, y = y)
                .requiredSize(width, height)
                .glass(RoundedRectangle(radius), GlassVariant.Regular, appear = { 0.6f + 0.4f * progress.value })
                .semantics { paneTitle = "Menu" }
                .padding(vertical = 6.dp)
                .alpha(((p - 0.4f) / 0.6f).coerceIn(0f, 1f)),
        ) {
            spec.items.forEachIndexed { index, item ->
                if (index == separatorAt) {
                    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(colors.separator))
                }
                val source = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .pressHighlight(source)
                        .tappable(source) {
                            overlays.dismissMenu()
                            item.onClick()
                        }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.label, style = Type.body, color = if (item.destructive) colors.red else colors.label,
                        modifier = Modifier.weight(1f))
                    if (item.icon != null) {
                        Icon(item.icon, null, tint = if (item.destructive) colors.red else colors.label, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// MARK: - Alert

@Composable
private fun BoxScope.AlertLayer(overlays: Overlays) {
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    var shown by remember { mutableStateOf<AlertSpec?>(null) }
    if (overlays.alert != null) shown = overlays.alert
    BackHandler(enabled = overlays.alert != null) {
        val spec = overlays.alert
        overlays.dismissAlert()
        spec?.onBack?.invoke()
    }
    AnimatedVisibility(
        overlays.alert != null,
        modifier = Modifier.align(Alignment.Center),
        enter = if (reduceMotion) fadeIn(tween(Durations.Quick)) else fadeIn(tween(200)) + scaleIn(Springs.materialize(), initialScale = 1.08f),
        exit = fadeOut(tween(200)),
    ) {
        // Fully drawn as soon as it's on screen; the enter/exit transition does the motion.
        // (An animated "appear" here could stop short on some phones and leave the alert
        // half see-through, blended with the screen behind it.)
        val appear = 1f
        val spec = shown ?: return@AnimatedVisibility
        Column(
            Modifier
                .width(270.dp)
                .glass(rounded(Radius.xl), GlassVariant.Regular, appear = { appear })
                .semantics {
                    paneTitle = spec.title
                    dialog()
                }
                .padding(20.dp)
                .alpha(((appear - 0.3f) / 0.7f).coerceIn(0f, 1f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(spec.title, style = Type.headline, color = colors.label, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.focusOnShow())
            if (spec.message != null) {
                Spacer(Modifier.height(6.dp))
                Text(spec.message, style = Type.footnote, color = colors.labelSecondary, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.actions.forEach { action ->
                    AlertButton(action) { overlays.dismissAlert(); action.onClick() }
                }
            }
        }
    }
}

/** The default action is a pale pill; the others are see-through (DESIGN.md §5.11). */
@Composable
private fun AlertButton(action: AlertAction, onClick: () -> Unit) {
    val colors = Wallhop.colors
    val source = remember { MutableInteractionSource() }
    val background = when {
        action.default && action.destructive -> colors.red
        action.default -> Wallhop.home.pale
        else -> colors.label.copy(alpha = 0.1f)
    }
    val label = when {
        action.default && action.destructive -> Color.White
        action.default -> Wallhop.home.onPale
        action.destructive -> colors.red
        else -> colors.label
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressScale(source)
            .clip(capsule())
            .background(background)
            .tappable(source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(action.label, style = Type.headline, color = label)
    }
}

// MARK: - Action sheet

@Composable
private fun BoxScope.ActionSheetLayer(overlays: Overlays) {
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    var shown by remember { mutableStateOf<ActionSheetSpec?>(null) }
    if (overlays.actionSheet != null) shown = overlays.actionSheet
    BackHandler(enabled = overlays.actionSheet != null) { overlays.dismissActionSheet() }
    AnimatedVisibility(
        overlays.actionSheet != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideUpIn(reduceMotion) { it / 3 },
        exit = slideDownOut(reduceMotion),
    ) {
        // Fully drawn as soon as it's on screen; the enter/exit transition does the motion.
        // (An animated "appear" here could stop short on some phones and leave the actions
        // half see-through, blended with the screen behind it.)
        val appear = 1f
        val spec = shown ?: return@AnimatedVisibility
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp).alpha(((appear - 0.2f) / 0.8f).coerceIn(0f, 1f))
                .semantics {
                    paneTitle = spec.message ?: "Actions"
                    dialog()
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.fillMaxWidth().glass(rounded(Radius.xl), GlassVariant.Regular, appear = { appear }).padding(8.dp)) {
                if (spec.message != null) {
                    Text(spec.message, style = Type.footnote, color = colors.labelSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(12.dp).focusOnShow())
                } else {
                    Box(Modifier.size(1.dp).focusOnShow())
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    spec.actions.forEach { action ->
                        AlertButton(action.copy(default = false)) { overlays.dismissActionSheet(); action.onClick() }
                    }
                }
            }
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .pressScale(source)
                    .glass(capsule(), GlassVariant.Regular, appear = { appear })
                    .tappable(source) { overlays.dismissActionSheet() },
                contentAlignment = Alignment.Center,
            ) {
                Text(spec.cancel, style = Type.headline, color = colors.tint)
            }
        }
    }
}

// MARK: - Toast

@Composable
private fun BoxScope.ToastLayer(overlays: Overlays) {
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    val haptics = rememberHaptics()
    val context = androidx.compose.ui.platform.LocalContext.current
    var shown by remember { mutableStateOf<ToastSpec?>(null) }
    val current = overlays.toast
    if (current != null) shown = current
    LaunchedEffect(current?.id) {
        if (current != null) {
            if (current.tint == colors.red) haptics.perform(Haptic.Failure)
            // Longer when there's a button to reach (Undo), and as long as the user's
            // accessibility settings ask for (TalkBack, "Time to take action").
            delay(toastDuration(context, current.action != null))
            overlays.clearToast(current.id)
        }
    }
    AnimatedVisibility(
        current != null,
        modifier = Modifier.align(Alignment.TopCenter),
        // Drops from the top with a little bounce; leaves back up (DESIGN.md §5.14).
        enter = if (reduceMotion) fadeIn(tween(Durations.Quick)) else slideInVertically(Springs.bouncy()) { -it },
        exit = if (reduceMotion) fadeOut(tween(Durations.Quick))
            else slideOutVertically(Springs.smooth()) { -it } + fadeOut(tween(Durations.Fade)),
    ) {
        // Fully drawn as soon as it's on screen; the enter/exit transition does the motion.
        // (An animated "appear" here could stop short on some phones and leave the toast
        // half see-through, blended with the screen behind it.)
        val appear = 1f
        val spec = shown ?: return@AnimatedVisibility
        Row(
            Modifier
                .statusBarsPadding()
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp)
                .widthIn(max = 360.dp)
                .alpha(appear.coerceIn(0f, 1f))
                .clip(capsule())
                .background(Wallhop.home.pale)
                // TalkBack reads it out without moving focus.
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 18.dp, end = if (spec.action != null) 6.dp else 18.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Pale pill, dark text (like the reference's buttons). Icons keep their colour
            // (red for errors, green for success), darkened enough to read on the pale pill.
            if (spec.icon != null) {
                val tint = spec.tint?.let { androidx.compose.ui.graphics.lerp(it, Wallhop.home.onPale, 0.3f) } ?: Wallhop.home.onPale
                Icon(spec.icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(spec.text, style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Wallhop.home.onPale,
                modifier = Modifier.weight(1f, fill = false))
            spec.action?.let { action ->
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .pressScale(source)
                        .clip(capsule())
                        .background(Wallhop.home.onPale.copy(alpha = 0.1f))
                        .tappable(source) {
                            overlays.clearToast(spec.id)
                            action.onClick()
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action.label, style = Type.subheadline.copy(fontWeight = FontWeight.Bold), color = Wallhop.home.onPale)
                }
            }
        }
    }
}

/** How long a toast stays; one with a button (Undo) gets the time the user's settings recommend. */
fun toastDuration(context: android.content.Context, hasAction: Boolean): Long =
    if (hasAction) recommendedTimeout(context, 5_000, controls = true) else recommendedTimeout(context, 2_500)
