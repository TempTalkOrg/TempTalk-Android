package com.difft.android.call.ui

import androidx.compose.foundation.Image
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
import com.difft.android.call.ui.actionbar.actionButtonSurface


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






@Composable
fun GroupCallLeaveButton(
    onLeave: () -> Unit,
    onShowEndMenu: () -> Unit,
    size: Dp = CallActionBarPlanner.BUTTON_DP.dp,
) {
    val compact = size < CallActionBarPlanner.BUTTON_DP.dp
    val tailWidth = if (compact) CallActionBarPlanner.COMPACT_GROUP_END_TAIL_DP.dp else CallActionBarPlanner.GROUP_END_TAIL_DP.dp
    
    val totalWidth = CallActionBarPlanner.groupEndWidth(size.value.toInt()).dp
    val chevronSize = if (compact) 12.dp else 14.dp
    
    
    val tailShape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)

    
    
    
    
    Box(modifier = Modifier.size(width = totalWidth, height = size)) {
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier
                .testTag("call_btn_end_choices")
                .align(Alignment.CenterEnd)
                .width(tailWidth)
                .height(size)
                .actionButtonSurface(DifftTheme.colors.backgroundSecondary, tailShape)
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
