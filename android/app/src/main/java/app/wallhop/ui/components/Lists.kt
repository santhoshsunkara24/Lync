package app.wallhop.ui.components

import androidx.compose.ui.graphics.graphicsLayer

import app.wallhop.ui.design.pressedVisibly

import app.wallhop.ui.design.WIcons
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.addOutline
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.AnimatedChangeText
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Fades
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.rounded

// Inset grouped lists. See docs/DESIGN.md §5.4 and §5.5.

/** Collects rows for an [InsetSection] so dividers can go between them. */
class SectionScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

@Composable
fun InsetSection(
    modifier: Modifier = Modifier,
    footer: String? = null,
    dividerInset: Dp = 16.dp,
    content: SectionScope.() -> Unit,
) {
    val colors = Wallhop.colors
    val scope = SectionScope().apply(content)
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(rounded(SectionRadius))
                .background(SectionSurface),
        ) {
            scope.rows.forEachIndexed { index, row ->
                row()
                if (index < scope.rows.lastIndex) {
                    Box(
                        Modifier
                            .padding(start = dividerInset)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(colors.separator),
                    )
                }
            }
        }
        if (footer != null) {
            Text(footer, style = Type.footnote, color = colors.labelSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
        }
    }
}

/**
 * [InsetSection] for long lists: each row is its own lazy item, so only the rows on screen
 * are composed. Looks the same — the first and last rows carry the section's corners.
 */
fun <T> androidx.compose.foundation.lazy.LazyListScope.insetSectionItems(
    items: List<T>,
    key: (T) -> Any,
    dividerInset: Dp = 16.dp,
    row: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        val colors = Wallhop.colors
        val reduceMotion = Wallhop.a11y.reduceMotion
        val first = index == 0
        val last = index == items.lastIndex
        Column(
            Modifier
                // Rows added or removed fade; the rest slide to their new places (DESIGN.md §7.2).
                .animateItem(
                    fadeInSpec = if (reduceMotion) null else Fades.fadeIn(),
                    placementSpec = if (reduceMotion) null else Springs.smooth(),
                    fadeOutSpec = if (reduceMotion) null else Fades.fadeOut(),
                )
                .fillMaxWidth()
                .clip(SectionSliceShape(rounded(SectionRadius), roundTop = first, roundBottom = last))
                .background(SectionSurface),
        ) {
            row(item)
            if (!last) {
                Box(Modifier.padding(start = dividerInset).fillMaxWidth().height(0.5.dp).background(colors.separator))
            }
        }
    }
}

/** A slice of a rounded section: [base]'s corners only on the ends that are rounded. */
private class SectionSliceShape(
    private val base: androidx.compose.ui.graphics.Shape,
    private val roundTop: Boolean,
    private val roundBottom: Boolean,
) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        if (!roundTop && !roundBottom) return androidx.compose.ui.graphics.Outline.Rectangle(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size))
        // Draw the full shape taller than the row, pushing the unrounded ends out of view.
        val extra = with(density) { 64.dp.toPx() }
        val top = if (roundTop) 0f else extra
        val bottom = if (roundBottom) 0f else extra
        val outline = base.createOutline(size.copy(height = size.height + top + bottom), layoutDirection, density)
        val path = androidx.compose.ui.graphics.Path().apply {
            addOutline(outline)
            translate(androidx.compose.ui.geometry.Offset(0f, -top))
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

/**
 * One row. Highlights the instant it's touched and fades back on release — no ripple.
 * [trailing] holds a value, button or nothing; [chevron] adds the navigation arrow.
 * With [checked] the row is a switch: a tap anywhere on it toggles, and TalkBack reads the
 * title with the switch's state. A row with only [onLongClick] isn't announced as a button;
 * TalkBack offers the long press as an action named [onLongClickLabel].
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    /** Draws the title instead of plain text (e.g. a code rolling in); [title] still names the row. */
    titleContent: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    /** Longer help text (e.g. maker-specific steps) can show in full. */
    subtitleMaxLines: Int = 3,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    /** Roll [value] when it changes (numbers like "12 ms" or "4 apps"). */
    animateValue: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    titleColor: Color? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    switchEnabled: Boolean = true,
    onLongClickLabel: String? = null,
    /** What a tap does, for TalkBack ("Copy code"). */
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = Wallhop.colors
    val source = remember { MutableInteractionSource() }
    val pressed = source.pressedVisibly()
    val haptics = rememberHaptics()
    val isSwitch = checked != null && onCheckedChange != null
    val highlight by animateColorAsState(
        if (pressed && (onClick != null || onLongClick != null || isSwitch)) colors.fill else Color.Transparent,
        animationSpec = tween(if (pressed) 0 else Durations.Highlight),
        label = "rowHighlight",
    )
    val currentLongClick by rememberUpdatedState(onLongClick)
    // Held down on a row with a long press: it sinks slowly over the hold, so the menu opening at
    // the end feels like the result of pressing, not a surprise. Springs back when let go.
    val reduceMotion = Wallhop.a11y.reduceMotion
    val sink by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && onLongClick != null && !isSwitch && !reduceMotion) 0.97f else 1f,
        if (pressed) tween(android.view.ViewConfiguration.getLongPressTimeout(), easing = androidx.compose.animation.core.LinearOutSlowInEasing)
        else Springs.release(),
        label = "rowSink",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .background(highlight)
            .then(
                when {
                    isSwitch -> Modifier.toggleable(
                        value = checked,
                        interactionSource = source,
                        indication = null,
                        enabled = switchEnabled,
                        role = Role.Switch,
                    ) {
                        haptics.perform(Haptic.Selection)
                        onCheckedChange(it)
                    }
                    onClick != null -> Modifier.combinedClickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = onClickLabel,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick?.let { long -> { haptics.perform(Haptic.Medium); long() } },
                        onClick = onClick,
                    )
                    // Long press only: no tap, so no button role; TalkBack gets a named action.
                    onLongClick != null -> Modifier
                        .pointerInput(source) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val press = PressInteraction.Press(offset)
                                    source.emit(press)
                                    source.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                                },
                                onLongPress = {
                                    haptics.perform(Haptic.Medium)
                                    currentLongClick?.invoke()
                                },
                            )
                        }
                        .semantics(mergeDescendants = true) {
                            val label = onLongClickLabel ?: "More options"
                            onLongClick(label) { currentLongClick?.invoke(); true }
                            customActions = listOf(CustomAccessibilityAction(label) { currentLongClick?.invoke(); true })
                        }
                    else -> Modifier
                },
            )
            .defaultMinSize(minHeight = if (subtitle != null) 64.dp else 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    title,
                    style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    color = titleColor ?: if (destructive) colors.red else colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(subtitle, style = Type.footnote, color = colors.labelSecondary, maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            if (animateValue) {
                AnimatedChangeText(value, style = Type.callout, color = colors.labelSecondary, maxLines = 1)
            } else {
                Text(value, style = Type.callout, color = colors.labelSecondary, maxLines = 1)
            }
        }
        trailing?.invoke()
        if (isSwitch) {
            // Drawn only: the whole row is the control.
            WSwitch(checked, onCheckedChange = null, enabled = switchEnabled, interactionSource = source)
        }
        if (chevron) {
            Icon(WIcons.ChevronRight, null, tint = colors.labelTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

/** Grouped sections are a soft see-through white over the page's colour (DESIGN.md §5.4). */
val SectionSurface = Color.White.copy(alpha = 0.07f)
val SectionRadius = 24.dp

/**
 * Round icon: the glyph in its colour, lightened, on a soft wash of the same colour. Colour
 * still tells the kind of row apart, but quietly (DESIGN.md §3.3).
 */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = androidx.compose.ui.graphics.lerp(color, Color.White, 0.35f), modifier = Modifier.size(size * 0.5f))
    }
}
