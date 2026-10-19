package app.wallhop.ui.components

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Radius
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
    header: String? = null,
    footer: String? = null,
    dividerInset: Dp = 16.dp,
    content: SectionScope.() -> Unit,
) {
    val colors = Wallhop.colors
    val scope = SectionScope().apply(content)
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(header, style = Type.footnote, color = colors.labelSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp))
        }
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
        val first = index == 0
        val last = index == items.lastIndex
        Column(
            Modifier
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
 * [trailing] holds a value, switch, button or nothing; [chevron] adds the navigation arrow.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    titleColor: Color? = null,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = Wallhop.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = rememberHaptics()
    val highlight by animateColorAsState(
        if (pressed && (onClick != null || onLongClick != null)) colors.fill else Color.Transparent,
        animationSpec = tween(if (pressed) 0 else 250),
        label = "rowHighlight",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(highlight)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = source,
                        indication = null,
                        role = role,
                        onLongClick = onLongClick?.let { long -> { haptics.perform(Haptic.Medium); long() } },
                        onClick = onClick ?: {},
                    )
                } else {
                    Modifier
                },
            )
            .defaultMinSize(minHeight = if (subtitle != null) 64.dp else 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = titleColor ?: if (destructive) colors.red else colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = Type.footnote, color = colors.labelSecondary, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Text(value, style = Type.callout, color = colors.labelSecondary, maxLines = 1)
        }
        trailing?.invoke()
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

/** Large title placed at the top of scrolling content. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(horizontal = 4.dp)) {
        Text(text, style = Type.largeTitle, color = Wallhop.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = Type.callout, color = Wallhop.colors.labelSecondary)
        }
    }
}

@Composable
fun SectionGap() = Spacer(Modifier.height(32.dp).width(1.dp))
