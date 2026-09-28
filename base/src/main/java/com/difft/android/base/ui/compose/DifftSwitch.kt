package com.difft.android.base.ui.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Switch geometry and motion, from the library Switch page's "Android switcher" (Material 3)
 * frames. Single source for the shell and every Compose call site.
 */
object DifftSwitchDefaults {
    /** Track box: a 52×32 pill, the same box in both states. */
    val TrackWidth: Dp = 52.dp
    val TrackHeight: Dp = 32.dp

    /** Off-track outline; the on track is a flat fill. */
    val TrackBorderWidth: Dp = 2.dp

    /** The knob grows as it slides: 24dp inset 4dp when on, 16dp inset 8dp when off. */
    val KnobSizeOn: Dp = 24.dp
    val KnobSizeOff: Dp = 16.dp
    val KnobInsetOn: Dp = 4.dp
    val KnobInsetOff: Dp = 8.dp

    /** Knob start edge, measured from the track start. Vertical centring is the layout's job. */
    val KnobOffsetOn: Dp = TrackWidth - KnobInsetOn - KnobSizeOn
    val KnobOffsetOff: Dp = KnobInsetOff

    val KnobShape: Shape = CircleShape

    /** Figma Disable-On / Disable-Off: the state colours stay and the whole control is veiled. */
    const val DisabledAlpha: Float = 0.4f

    val KnobAnimationDurationMillis: Int = 150

    /** Knob slide and grow. Shared so the shell and every call site animate identically. */
    val KnobAnimationSpec: AnimationSpec<Dp> = tween(KnobAnimationDurationMillis)
}

/**
 * The app's switch: follows the library Switch page's "Android switcher" (Material 3) frames — a
 * 52×32 pill whose knob is 24dp inset 4dp at the end when on, and 16dp inset 8dp at the start over
 * a `textDisabled`-bordered grey track when off.
 *
 * Mirrors the Material3 `Switch` parameter shape so call sites migrate by renaming. A non-null
 * [onCheckedChange] makes it toggleable in its own right with switch semantics and a 48dp-tall
 * touch target; `null` renders the visual only, for rows (`DifftSwitchRow`) or View shells that own
 * the click and the semantics.
 *
 * Tap only — no drag. `SwitchCompat` does support dragging the thumb; dropping it is a deliberate
 * trade-off: Figma specifies no drag, and a draggable knob would fight the label row's whole-row
 * click, where the switch is a child of the tappable row rather than the target itself.
 *
 * Disabled follows Figma's Disable-On / Disable-Off, which keep the enabled colours and veil the
 * whole control at [DifftSwitchDefaults.DisabledAlpha]: Disable-On stays recognisably blue instead
 * of collapsing onto the same grey as Disable-Off.
 */
@Composable
fun DifftSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track = if (checked) DifftTheme.colors.primary else DifftTheme.colors.backgroundDisabled
    val knob = if (checked) DifftTheme.colors.iconOnPrimary else DifftTheme.colors.textDisabled
    val knobSize by animateDpAsState(
        targetValue = if (checked) DifftSwitchDefaults.KnobSizeOn else DifftSwitchDefaults.KnobSizeOff,
        animationSpec = DifftSwitchDefaults.KnobAnimationSpec,
        label = "difft_switch_knob_size",
    )
    val knobOffsetX by animateDpAsState(
        targetValue = if (checked) DifftSwitchDefaults.KnobOffsetOn else DifftSwitchDefaults.KnobOffsetOff,
        animationSpec = DifftSwitchDefaults.KnobAnimationSpec,
        label = "difft_switch_knob_offset",
    )

    val interaction = if (onCheckedChange != null) {
        Modifier
            // Height only; the width stays the track width so row alignment does not shift.
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
        // wrap_content → the design's track box; an explicit size arrives as EXACTLY and wins.
        // Never fillMaxSize: wrap_content children receive AT_MOST, which fillMaxSize expands to.
        // The interaction's touch-target min goes first: defaultMinSize only applies when the
        // incoming min is 0, so the geometry min would otherwise swallow it.
        modifier = modifier
            .then(interaction)
            .defaultMinSize(DifftSwitchDefaults.TrackWidth, DifftSwitchDefaults.TrackHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // background(shape) rather than clip(shape): clipping would trim the knob at the pill
            // edge. The border draws inside the bounds, so the off track keeps the same 52×32 box
            // as the on track. The disabled veil sits here so it covers the knob too, and leaves
            // the touch target untouched.
            modifier = Modifier
                .size(DifftSwitchDefaults.TrackWidth, DifftSwitchDefaults.TrackHeight)
                .alpha(if (enabled) 1f else DifftSwitchDefaults.DisabledAlpha)
                .background(track, DifftTheme.radius.pill)
                .then(
                    if (checked) {
                        Modifier
                    } else {
                        Modifier.border(
                            width = DifftSwitchDefaults.TrackBorderWidth,
                            color = DifftTheme.colors.textDisabled,
                            shape = DifftTheme.radius.pill,
                        )
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                // Size and offset run on one spec, so the knob grows and slides in step; the size
                // change re-measures the knob each frame, the offset only moves its placement.
                modifier = Modifier
                    .offset { IntOffset(knobOffsetX.roundToPx(), 0) }
                    .size(knobSize)
                    .background(knob, DifftSwitchDefaults.KnobShape),
            )
        }
    }
}
