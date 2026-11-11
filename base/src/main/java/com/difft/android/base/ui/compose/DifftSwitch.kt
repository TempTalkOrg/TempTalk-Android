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




object DifftSwitchDefaults {
    
    val TrackWidth: Dp = 52.dp
    val TrackHeight: Dp = 32.dp

    
    val TrackBorderWidth: Dp = 2.dp

    
    val KnobSizeOn: Dp = 24.dp
    val KnobSizeOff: Dp = 16.dp
    val KnobInsetOn: Dp = 4.dp
    val KnobInsetOff: Dp = 8.dp

    
    val KnobOffsetOn: Dp = TrackWidth - KnobInsetOn - KnobSizeOn
    val KnobOffsetOff: Dp = KnobInsetOff

    val KnobShape: Shape = CircleShape

    
    const val DisabledAlpha: Float = 0.4f

    val KnobAnimationDurationMillis: Int = 150

    
    val KnobAnimationSpec: AnimationSpec<Dp> = tween(KnobAnimationDurationMillis)
}
















@Composable
fun DifftSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track = if (checked) DifftTheme.colors.primary else DifftTheme.colors.backgroundDisabled
    val knob = if (checked) DifftTheme.colors.onPrimary else DifftTheme.colors.textDisabled
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
        
        
        
        
        modifier = modifier
            .then(interaction)
            .defaultMinSize(DifftSwitchDefaults.TrackWidth, DifftSwitchDefaults.TrackHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            
            
            
            
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
                
                
                modifier = Modifier
                    .offset { IntOffset(knobOffsetX.roundToPx(), 0) }
                    .size(knobSize)
                    .background(knob, DifftSwitchDefaults.KnobShape),
            )
        }
    }
}
