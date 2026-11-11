package com.difft.android.call.ui

import com.difft.android.base.ui.compose.floatingSurface
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.base.call.CallType
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.data.WeakNetworkBanner


enum class CallStatusIcon(@DrawableRes val res: Int, val spinning: Boolean) {
    
    CONNECTING(R.drawable.call_status_notification_loading, spinning = true),

    
    DISCONNECTED(R.drawable.gg_spinner_alt, spinning = false),

    
    WEAK_NETWORK(R.drawable.call_ic_wifi_x, spinning = false),
}


data class CallStatusNotification(val text: String, val icon: CallStatusIcon) {
    
    val spinning: Boolean get() = icon.spinning
}






internal fun callStatusNotification(
    callStatus: CallStatus,
    callType: String,
    callIntent: CallIntent,
    callTimerRunning: Boolean,
    mediaSendIssue: MediaSendIssueState,
    weakNetwork: WeakNetworkBanner,
): CallStatusNotification? {
    val connected = callStatus == CallStatus.CONNECTED || callStatus == CallStatus.RECONNECTED
    return when {
        
        !connected && callType == CallType.ONE_ON_ONE.type && callStatus == CallStatus.CALLING -> null

        !connected && shouldShowLoadingStatus(callStatus, callIntent, callType) ->
            if (callStatus == CallStatus.RECONNECT_FAILED) {
                CallStatusNotification(
                    ResUtils.getString(R.string.call_disconnected_title),
                    CallStatusIcon.DISCONNECTED,
                )
            } else {
                CallStatusNotification(ResUtils.getString(R.string.call_connecting_title), CallStatusIcon.CONNECTING)
            }

        
        connected && !callTimerRunning ->
            CallStatusNotification(ResUtils.getString(R.string.call_connecting_title), CallStatusIcon.CONNECTING)

        
        
        connected && mediaSendIssue == MediaSendIssueState.CONNECTION_RECOVERING ->
            CallStatusNotification(ResUtils.getString(R.string.call_connecting_title), CallStatusIcon.CONNECTING)

        
        
        connected && mediaSendIssue == MediaSendIssueState.SEND_RECOVERING ->
            CallStatusNotification(ResUtils.getString(R.string.call_media_send_issue), CallStatusIcon.CONNECTING)

        
        connected && weakNetwork == WeakNetworkBanner.LOCAL ->
            CallStatusNotification(
                ResUtils.getString(R.string.call_myself_network_poor_tip),
                CallStatusIcon.WEAK_NETWORK,
            )

        connected && weakNetwork == WeakNetworkBanner.REMOTE ->
            CallStatusNotification(
                ResUtils.getString(R.string.call_other_network_poor_tip),
                CallStatusIcon.WEAK_NETWORK,
            )

        else -> null
    }
}

private fun shouldShowLoadingStatus(callStatus: CallStatus, callIntent: CallIntent, callType: String): Boolean {
    
    return callStatus == CallStatus.RECONNECTING ||
            callStatus == CallStatus.SWITCHING_SERVER ||
            (callIntent.action != CallIntent.Action.START_CALL && callStatus != CallStatus.DISCONNECTED) ||
            (callType != CallType.ONE_ON_ONE.type && callStatus != CallStatus.DISCONNECTED)
}





@Composable
fun CallStatusNotificationBar(
    status: CallStatusNotification?,
    modifier: Modifier = Modifier,
) {
    
    
    
    val lastStatus = remember { arrayOfNulls<CallStatusNotification>(1) }
    if (status != null) lastStatus[0] = status
    val shown = status ?: lastStatus[0]

    AnimatedVisibility(
        visible = status != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -80 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -80 }),
        modifier = modifier.testTag("call_status_notification_bar"),
    ) {
        shown?.let { StatusNotificationPill(it) }
    }
}

@Composable
private fun StatusNotificationPill(status: CallStatusNotification) {
    
    val rotationAngle by if (status.spinning) {
        rememberInfiniteTransition(label = "callStatusNotificationSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
            ),
            label = "callStatusNotificationSpinValue",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .floatingSurface(DifftTheme.radius.largeShape, fill = DifftTheme.colors.backgroundTertiary)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Image(
            painter = painterResource(id = status.icon.res),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            
            
            
            colorFilter = ColorFilter.tint(DifftTheme.colors.textPrimary),
            modifier = Modifier
                .size(16.dp)
                .rotate(rotationAngle),
        )
        Text(
            modifier = Modifier.testTag("call_status_notification_text"),
            text = status.text,
            style = DifftTheme.typography.p4,
            color = DifftTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
