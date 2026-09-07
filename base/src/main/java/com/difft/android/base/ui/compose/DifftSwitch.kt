package com.difft.android.base.ui.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme

/** Figma switch geometry (51×31 iOS pill). Single source for the shell and every Compose call site. */
object DifftSwitchDefaults {
    val TrackWidth: Dp = 51.dp
    val TrackHeight: Dp = 31.dp
    val KnobSize: Dp = 27.dp
    val KnobInset: Dp = 2.dp

    /** 51 − 27 − 2×2 */
    val KnobTravel: Dp = TrackWidth - KnobSize - KnobInset * 2
    val TrackShape: Shape = RoundedCornerShape(percent = 50)
    val KnobShape: Shape = CircleShape

    /** Figma filter0_dd: dy 3 / black 6% + dy 3 / blur 4 / black 15%. */
    val KnobElevation: Dp = 2.dp
    val KnobShadowAmbient: Color = Color.Black.copy(alpha = 0.06f)
    val KnobShadowSpot: Color = Color.Black.copy(alpha = 0.15f)

    val KnobAnimationDurationMillis: Int = 150

    /** Knob slide. Shared so the shell and every call site animate identically. */
    val KnobAnimationSpec: AnimationSpec<Dp> = tween(KnobAnimationDurationMillis)

    /** Label-row metrics shared by [DifftSwitchRow] and the XML label-row shell. */
    val RowMinHeight: Dp = 52.dp
    val LabelSpacing: Dp = 12.dp

    /**
     * Row label: the theme's bodyLarge with letterSpacing zeroed. bodyLarge carries 0.5sp, which
     * neither the migrated XML rows (16sp, default spacing) nor the migrated Compose rows had.
     */
    val LabelTextStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = DifftTheme.typography.bodyLarge.copy(letterSpacing = 0.sp)
}

/**
 * The app's switch (Figma `Switch`, 51×31 pill with a 27dp knob inset 2dp).
 *
 * Mirrors the Material3 `Switch` parameter shape so call sites migrate by renaming. A non-null
 * [onCheckedChange] makes it toggleable in its own right with switch semantics and a 48dp-tall
 * touch target; `null` renders the visual only, for rows ([DifftSwitchRow]) or View shells that own
 * the click and the semantics.
 *
 * Tap only — no drag. `SwitchCompat` does support dragging the thumb; dropping it is a deliberate
 * trade-off: Figma specifies no drag, and a draggable knob would fight the label row's whole-row
 * click, where the switch is a child of the tappable row rather than the target itself.
 *
 * Disabled states are not in Figma; they follow the [DifftCheckBox] treatment — distinct colours
 * rather than an alpha veil: the track drops to `backgroundDisabled` in both states while the knob
 * keeps its position, so the state stays readable on the light and dark palettes alike (0.38 alpha
 * over `bg.disable` is all but invisible in both).
 */
@Composable
fun DifftSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track = if (enabled && checked) DifftTheme.colors.primary else DifftTheme.colors.backgroundDisabled
    val knob = DifftTheme.colors.iconOnPrimary
    val offsetX by animateDpAsState(
        targetValue = if (checked) DifftSwitchDefaults.KnobTravel else 0.dp,
        animationSpec = DifftSwitchDefaults.KnobAnimationSpec,
        label = "difft_switch_knob",
    )

    val interaction = if (onCheckedChange != null) {
        Modifier
            // Height only; the width stays 51dp so row alignment does not shift.
            .defaultMinSize(minHeight = DifftTheme.spacing.minTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
    } else {
        Modifier
    }

    Box(
        // wrap_content → the design's 51×31 box; an explicit size arrives as EXACTLY and wins.
        // Never fillMaxSize: wrap_content children receive AT_MOST, which fillMaxSize expands to.
        // The interaction's touch-target min goes first: defaultMinSize only applies when the
        // incoming min is 0, so the geometry min would otherwise swallow it.
        modifier = modifier
            .then(interaction)
            .defaultMinSize(DifftSwitchDefaults.TrackWidth, DifftSwitchDefaults.TrackHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // background(shape) rather than clip(shape): clipping the track would also clip the
            // knob's drop shadow at the pill edge and erase it.
            modifier = Modifier
                .size(DifftSwitchDefaults.TrackWidth, DifftSwitchDefaults.TrackHeight)
                .background(track, DifftSwitchDefaults.TrackShape)
                .padding(DifftSwitchDefaults.KnobInset),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                // Lambda offset: it changes placement only, so the animation stays out of both
                // measurement (the track remains exactly 51×31) and recomposition — the value is
                // read in the placement lambda instead of in this composable's scope.
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToPx(), 0) }
                    .size(DifftSwitchDefaults.KnobSize)
                    .shadow(
                        elevation = DifftSwitchDefaults.KnobElevation,
                        shape = DifftSwitchDefaults.KnobShape,
                        clip = false,
                        ambientColor = DifftSwitchDefaults.KnobShadowAmbient,
                        spotColor = DifftSwitchDefaults.KnobShadowSpot,
                    )
                    .background(knob, DifftSwitchDefaults.KnobShape),
            )
        }
    }
}
