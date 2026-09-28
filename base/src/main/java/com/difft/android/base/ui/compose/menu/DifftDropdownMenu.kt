package com.difft.android.base.ui.compose.menu

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftRadius
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Geometry and colours of the design library's context menu, "App" (mobile) variant — file
 * `16QV9AE1wA37o9D2Xdc4sO`, node `3979:1265`. Public so surfaces that cannot use the anchored
 * [DifftDropdownMenu] (in-composition overlays, `Popup`-based menus) still draw the same numbers.
 */
object DifftDropdownMenuDefaults {
    /** Panel corner radius (library "Radius 8"). */
    val PanelShape: Shape = DifftRadius.largeShape

    /** Drop shadow. The library's three-layer Elevation-2/3 styles have no renderer yet. */
    val PanelElevation: Dp = 4.dp

    val MinWidth: Dp = 180.dp
    val MaxWidth: Dp = 280.dp

    /** Top/bottom caps of the panel (library `top`/`bot`, 4.5px). */
    val PanelVerticalPadding: Dp = 4.dp

    /** Row box: 44 = 24 content + 2 × [ItemVerticalPadding]. */
    val ItemHeight: Dp = 44.dp
    val ItemHorizontalPadding: Dp = 16.dp
    val ItemVerticalPadding: Dp = 10.dp
    val IconSize: Dp = 20.dp
    val IconGap: Dp = 8.dp
    val TrailingIconSize: Dp = 16.dp

    /** Divider item: 8 tall, a centred 1dp `line` rule. */
    val DividerHeight: Dp = 8.dp
    val DividerThickness: Dp = 1.dp

    /** Pressed row highlight: an inset pill instead of the Material ripple. */
    internal val PressedPillHorizontalInset: Dp = 4.dp
    internal val PressedPillVerticalInset: Dp = 2.dp
    internal val PressedPillShape: Shape = DifftRadius.mediumShape

    /** Panel background: `bgpopup` in light, `bgmodal` in dark. */
    val panelColor: Color
        @Composable @ReadOnlyComposable
        get() = if (DifftTheme.colors.isDark) {
            DifftTheme.colors.backgroundModal
        } else {
            DifftTheme.colors.backgroundPopup
        }

    /** Pressed-pill fill: `bg3` in light, `bg4` in dark (`bg3` equals the dark panel colour). */
    internal val pressedPillColor: Color
        @Composable @ReadOnlyComposable
        get() = if (DifftTheme.colors.isDark) {
            DifftTheme.colors.backgroundQuaternary
        } else {
            DifftTheme.colors.backgroundTertiary
        }
}

/**
 * Dropdown menu anchored to the composable it is placed next to, styled by the design library.
 *
 * Built on the Material3 [DropdownMenu] so anchoring, the open/close transition and outside-tap
 * dismissal stay Material's; only the panel skin and the rows are ours. Rows are
 * [DifftDropdownMenuItem] / [DifftDropdownMenuDivider], not `DropdownMenuItem`, because the library
 * asks for a 44dp row and an inset pressed pill that Material's item cannot express.
 *
 * Surfaces that cannot live in their own window — an overlay inside a `BottomSheetDialog` whose
 * anchor coordinates drift, or a menu positioned at the finger — use [DifftDropdownMenuPanel]
 * with the same rows instead.
 *
 * @param modifier applied after the default width bounds, so a caller may pin an exact width.
 */
@Composable
fun DifftDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .widthIn(
                min = DifftDropdownMenuDefaults.MinWidth,
                max = DifftDropdownMenuDefaults.MaxWidth,
            )
            .then(modifier),
        offset = offset,
        properties = properties,
        shape = DifftDropdownMenuDefaults.PanelShape,
        containerColor = DifftDropdownMenuDefaults.panelColor,
        tonalElevation = 0.dp,
        shadowElevation = DifftDropdownMenuDefaults.PanelElevation,
        content = content,
    )
}

/**
 * The menu panel on its own — same skin as [DifftDropdownMenu] without the popup window.
 *
 * For menus that must share their host's window: in-composition overlays (a long-press menu inside
 * a bottom sheet, where a popup's anchor coordinates no longer map to the screen) and menus placed
 * by their own [androidx.compose.ui.window.Popup] position provider.
 *
 * @param color panel fill; override only for a surface that is deliberately theme-independent.
 */
@Composable
fun DifftDropdownMenuPanel(
    modifier: Modifier = Modifier,
    color: Color = DifftDropdownMenuDefaults.panelColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = DifftDropdownMenuDefaults.PanelShape,
        color = color,
        shadowElevation = DifftDropdownMenuDefaults.PanelElevation,
    ) {
        Column(
            // widthIn before width(IntrinsicSize.Max), as Material's own menu content does: the
            // intrinsic width is then clamped into the min/max, not the other way round.
            modifier = Modifier
                .widthIn(
                    min = DifftDropdownMenuDefaults.MinWidth,
                    max = DifftDropdownMenuDefaults.MaxWidth,
                )
                .width(IntrinsicSize.Max)
                .padding(vertical = DifftDropdownMenuDefaults.PanelVerticalPadding),
            content = content,
        )
    }
}

/** Row tone, mirroring the library's `dropdown_cell` variants. */
enum class DifftDropdownMenuItemTone {
    /** Icon `icon`, label `textPrimary`. */
    Default,

    /** Icon and label `textError` ("Delete" in the library). */
    Destructive,

    /** Label `textInfo` and, with no trailing slot given, a 16dp tick. */
    Selected;

    companion object {
        /** [Selected] or [Default] — for a single-choice menu. */
        fun selection(selected: Boolean): DifftDropdownMenuItemTone = if (selected) Selected else Default
    }
}

/**
 * Content and pressed-pill colours for a row on a panel that is deliberately theme-independent
 * (a forced-dark surface), where the theme tokens would resolve against the wrong background.
 */
@Immutable
data class DifftDropdownMenuItemColors(val content: Color, val pressedPill: Color)

/**
 * One menu row: 44dp tall, 16dp horizontal padding, optional 20dp leading icon, 8dp gap,
 * single-line `p2` label, optional trailing slot.
 *
 * Pressed feedback is the library's inset pill (4dp/2dp inset, radius 4, `bg3`/`bg4`) rather than
 * the Material ripple, so the indication is suppressed and the pill drawn here.
 *
 * @param colors overrides the token colours; `null` (default) resolves them from [tone].
 */
@Composable
fun DifftDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    tone: DifftDropdownMenuItemTone = DifftDropdownMenuItemTone.Default,
    colors: DifftDropdownMenuItemColors? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val labelColor = when {
        !enabled -> DifftTheme.colors.textDisabled
        colors != null -> colors.content
        tone == DifftDropdownMenuItemTone.Destructive -> DifftTheme.colors.textError
        tone == DifftDropdownMenuItemTone.Selected -> DifftTheme.colors.textInfo
        else -> DifftTheme.colors.textPrimary
    }
    val iconTint = when {
        !enabled -> DifftTheme.colors.textDisabled
        colors != null -> colors.content
        tone == DifftDropdownMenuItemTone.Destructive -> DifftTheme.colors.textError
        tone == DifftDropdownMenuItemTone.Selected -> DifftTheme.colors.textInfo
        else -> DifftTheme.colors.icon
    }

    val defaultPillColor = DifftDropdownMenuDefaults.pressedPillColor
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pillColor = when {
        !pressed || !enabled -> Color.Transparent
        colors != null -> colors.pressedPill
        else -> defaultPillColor
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DifftDropdownMenuDefaults.ItemHeight)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(
                horizontal = DifftDropdownMenuDefaults.PressedPillHorizontalInset,
                vertical = DifftDropdownMenuDefaults.PressedPillVerticalInset,
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(pillColor, DifftDropdownMenuDefaults.PressedPillShape)
                .padding(
                    horizontal = DifftDropdownMenuDefaults.ItemHorizontalPadding -
                        DifftDropdownMenuDefaults.PressedPillHorizontalInset
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DifftDropdownMenuDefaults.IconGap),
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(DifftDropdownMenuDefaults.IconSize),
                )
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = DifftTheme.typography.p2,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                trailing != null -> trailing()
                tone == DifftDropdownMenuItemTone.Selected -> Icon(
                    painter = painterResource(R.drawable.base_ic_check),
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(DifftDropdownMenuDefaults.TrailingIconSize),
                )
            }
        }
    }
}

/** Separator between row groups: 8dp tall, a centred 1dp `line` rule. */
@Composable
fun DifftDropdownMenuDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DifftDropdownMenuDefaults.DividerHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DifftDropdownMenuDefaults.DividerThickness)
                .background(DifftTheme.colors.line)
        )
    }
}
