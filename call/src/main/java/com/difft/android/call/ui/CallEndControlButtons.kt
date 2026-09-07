package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R
import com.difft.android.call.ui.actionbar.ActionButtonStyle
import com.difft.android.call.ui.actionbar.CallActionBarPlanner
import com.difft.android.call.ui.actionbar.CallActionButton
import com.difft.android.call.ui.actionbar.actionButtonShadow

/** 1v1 hang-up: a plain error-red circle with a white handset glyph. */
@Composable
fun OneOnOneHangupButton(
    onHangup: () -> Unit,
    size: Dp = CallActionBarPlanner.BUTTON_DP.dp,
) {
    CallActionButton(
        painter = painterResource(id = R.drawable.call_ic_hangup),
        contentDescription = "hangup",
        style = ActionButtonStyle.END,
        size = size,
        testTag = "call_btn_hangup",
        onClick = {
            L.i { "[call] LCallActivity onClick Hangup" }
            onHangup()
        },
    )
}

/**
 * Group leave: the red leave circle plus a bg2 chevron tail that opens the end menu. The tail
 * overlaps the circle by half its diameter, so the whole control is `size + tail - size / 2`
 * wide (78 at 48dp, 65 at 40dp).
 */
@Composable
fun GroupCallLeaveButton(
    onLeave: () -> Unit,
    onShowEndMenu: () -> Unit,
    size: Dp = CallActionBarPlanner.BUTTON_DP.dp,
) {
    val compact = size < CallActionBarPlanner.BUTTON_DP.dp
    val tailWidth = if (compact) CallActionBarPlanner.COMPACT_GROUP_END_TAIL_DP.dp else CallActionBarPlanner.GROUP_END_TAIL_DP.dp
    // Same arithmetic the planner budgets with, so the rendered control can never drift from it.
    val totalWidth = CallActionBarPlanner.groupEndWidth(size.value.toInt()).dp
    val chevronSize = if (compact) 12.dp else 14.dp
    val tailShape = RoundedCornerShape(topStart = 0.dp, topEnd = 100.dp, bottomStart = 0.dp, bottomEnd = 100.dp)

    // Declaration order matters for z-order and hit-test priority in Compose Box:
    // the last-declared child is drawn on top and wins hit-testing in overlap zones.
    // The chevron tail (CenterEnd) and the leave circle (CenterStart) overlap by half a
    // circle; the leave button must win in that overlap zone, so it is declared SECOND.
    Box(modifier = Modifier.size(width = totalWidth, height = size)) {
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier
                .testTag("call_btn_end_choices")
                .align(Alignment.CenterEnd)
                .width(tailWidth)
                .height(size)
                .actionButtonShadow(tailShape)
                .background(color = DifftTheme.colors.backgroundSecondary, shape = tailShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onShowEndMenu() }
        ) {
            Image(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(chevronSize),
                painter = painterResource(id = R.drawable.call_btn_tabler_chevron_on),
                contentDescription = "end call choices menu",
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(DifftTheme.colors.textPrimary),
            )
        }

        CallActionButton(
            painter = painterResource(id = R.drawable.call_btn_mingcute_exit_line),
            contentDescription = "leave",
            style = ActionButtonStyle.END,
            size = size,
            iconSize = size / 2,
            testTag = "call_btn_leave",
            modifier = Modifier.align(Alignment.CenterStart),
            onClick = {
                L.i { "[call] LCallActivity onClick Leave" }
                onLeave()
            },
        )
    }
}
