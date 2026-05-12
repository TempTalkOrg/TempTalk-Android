package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.R

@Composable
fun OneOnOneHangupButton(onHangup: () -> Unit) {
    Row(
        modifier = Modifier
            .width(50.dp)
            .height(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                L.i { "[call] LCallActivity onClick Hangup" }
                onHangup()
            },
        horizontalArrangement = Arrangement.spacedBy(1.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .width(48.dp)
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .width(48.dp)
                    .height(48.dp)
                    .background(
                        color = colorResource(id = com.difft.android.base.R.color.error),
                        shape = RoundedCornerShape(size = 100.dp)
                    )
                    .padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    modifier = Modifier
                        .padding(1.0125.dp)
                        .width(31.2.dp)
                        .height(24.dp),
                    painter = painterResource(id = R.drawable.call_btn_hangup),
                    contentDescription = "hangup",
                    contentScale = ContentScale.None
                )
            }
        }
    }
}

@Composable
fun GroupCallLeaveButton(
    onLeave: () -> Unit,
    onShowEndMenu: () -> Unit
) {
    // Declaration order matters for z-order and hit-test priority in Compose Box:
    // the last-declared child is drawn on top and wins hit-testing in overlap zones.
    // The chevron pill (CenterEnd, 54dp) and the leave circle (CenterStart, 48dp)
    // overlap by 24dp; the leave button must win in that overlap zone, so it is
    // declared SECOND (after the chevron).
    Box(modifier = Modifier.size(width = 78.dp, height = 48.dp)) {
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(54.dp)
                .height(48.dp)
                .background(
                    color = colorResource(id = com.difft.android.base.R.color.bg2_night),
                    shape = RoundedCornerShape(
                        topStart = 0.dp, topEnd = 100.dp,
                        bottomStart = 0.dp, bottomEnd = 100.dp
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onShowEndMenu() }
                .padding(top = 7.dp, bottom = 7.dp)
        ) {
            Image(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(14.dp),
                painter = painterResource(id = R.drawable.call_btn_tabler_chevron_on),
                contentDescription = "end call choices menu",
                contentScale = ContentScale.None
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .background(
                    color = colorResource(id = com.difft.android.base.R.color.t_error_night),
                    shape = RoundedCornerShape(size = 100.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    L.i { "[call] LCallActivity onClick Leave" }
                    onLeave()
                }
                .padding(top = 7.dp, bottom = 7.dp)
        ) {
            Image(
                modifier = Modifier
                    .padding(1.0125.dp)
                    .size(24.dp),
                painter = painterResource(id = R.drawable.call_btn_mingcute_exit_line),
                contentDescription = "leave",
                contentScale = ContentScale.None
            )
        }
    }
}
