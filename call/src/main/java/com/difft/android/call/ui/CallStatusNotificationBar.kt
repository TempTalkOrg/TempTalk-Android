package com.difft.android.call.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallType
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.data.WeakNetworkBanner

/**
 * The pill's icon slot. Pairs the drawable with its rotation so a state can't mismatch them:
 * before this enum the drawable was derived from `spinning` alone, which breaks as soon as a
 * state is static but needs its own glyph (weak network is the first such state).
 */
enum class CallStatusIcon(@DrawableRes val res: Int, val spinning: Boolean) {
    /** Connecting / reconnecting / whole-link recovery / uplink recovery — the rotating arc. */
    CONNECTING(R.drawable.call_status_notification_loading, spinning = true),

    /** RECONNECT_FAILED — static broken-link glyph. */
    DISCONNECTED(R.drawable.gg_spinner_alt, spinning = false),

    /** Weak network, local or remote (Figma 17277:82) — static, never rotates. */
    WEAK_NETWORK(R.drawable.call_ic_wifi_x, spinning = false),
}

/** One entry of the floating status pill: what to say and which icon (with its rotation). */
data class CallStatusNotification(val text: String, val icon: CallStatusIcon) {
    /** Derived read so call sites keep asking about rotation directly. */
    val spinning: Boolean get() = icon.spinning
}

/**
 * Resolves which (single) status the floating pill shows. The pill carries CONNECTION HEALTH
 * only — it drops in while the link is unhealthy and disappears once connected (the Figma
 * note's "联通以后就消失", Mac parity). "等待接听…" is deliberately NOT here: the connection is
 * fine, the callee just hasn't answered — that call-progress state stays in the title (with
 * the E2EE crossfade, PR #1125), which also keeps this slot free for the critical-alert
 * banner that only appears during that same waiting phase. Doc rule preserved: any
 * connecting/reconnecting state outranks the media-send issue.
 */
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
        // Title owns 1v1 CALLING (either role) — same precedence the old title logic had, so
        // the pill must yield or a 1v1 callee's CALLING would double-render as "Connecting…".
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

        // 1v1 media-ready gate: room is CONNECTED but the timer waits for the peer RTC channel.
        connected && !callTimerRunning ->
            CallStatusNotification(ResUtils.getString(R.string.call_connecting_title), CallStatusIcon.CONNECTING)

        // Whole-link recovery (SDK ROOM_RECOVERING: resume / reconnect / network loss while the
        // room status flow may still say CONNECTED) — reuse the connection presentation.
        connected && mediaSendIssue == MediaSendIssueState.CONNECTION_RECOVERING ->
            CallStatusNotification(ResUtils.getString(R.string.call_connecting_title), CallStatusIcon.CONNECTING)

        // Uplink-only degradation (RECOVERING and FAILED share one presentation per doc — the
        // SDK keeps recovering after FAILED, so no distinct no-recovery failure state).
        // Deliberately ahead of the weak-network hints below: this one reports a real uplink
        // failure the peer already hears, while the weak-network copy only says quality "may" be
        // affected — and a weak local link is frequently the very cause of this state, so ranking
        // the cause first would hide the (short-lived) consequence behind a sustained hint.
        connected && mediaSendIssue == MediaSendIssueState.SEND_RECOVERING ->
            CallStatusNotification(ResUtils.getString(R.string.call_media_send_issue), CallStatusIcon.CONNECTING)

        // Weak network, appended strictly ahead of `else` so the decision path for
        // `weakNetwork == NONE` stays identical to what it was before this slot existed.
        // The `connected` prefix re-gates the hint at this layer: the upstream snapshot is
        // emptied off the room's own connection state, which need not flip in the same frame as
        // this resolver's `callStatus`.
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
    // SWITCHING_SERVER is a sustained mid-call unhealthy state (server switch, no teardown), so
    // it must surface in the pill like RECONNECTING — the legacy title gate never listed it, which
    // left a 1v1 caller with zero connecting feedback during the switch.
    return callStatus == CallStatus.RECONNECTING ||
            callStatus == CallStatus.SWITCHING_SERVER ||
            (callIntent.action != CallIntent.Action.START_CALL && callStatus != CallStatus.DISCONNECTED) ||
            (callType != CallType.ONE_ON_ONE.type && callStatus != CallStatus.DISCONNECTED)
}

/**
 * Notification-style floating pill (Figma node 17129:3372): drops in from the top below the
 * title bar and disappears once the status clears. Colors are literal design tokens resolved
 * through DifftTheme — this subtree is forced dark (CallContent.kt), so backgroundTertiary =
 * #2B3139 and textPrimary = #EAECEF, exactly the Figma bgmodal/tprimary values.
 */
@Composable
fun CallStatusNotificationBar(
    status: CallStatusNotification?,
    modifier: Modifier = Modifier,
) {
    // Keep the last shown status so the exit animation doesn't blank its content mid-slide.
    // Plain holder, not snapshot state: it is only read on the recomposition where `status`
    // turns null, which is already triggered by the param change itself.
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
    // Mount the infinite rotation only while a spinning status is visible — an unconditioned
    // infinite transition drives main-thread recomposition for the whole call (ANR precedent
    // on this screen's old loading spinner).
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
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(8.dp),
                spotColor = Color(0x14000000),
                ambientColor = Color(0x14000000),
            )
            .background(
                color = DifftTheme.colors.backgroundTertiary,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Image(
            painter = painterResource(id = status.icon.res),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // Unconditional tint so every pill icon matches the pill's own text colour (#EAECEF
            // in this forced-dark subtree), and so the weak-network glyph can keep the pure-white
            // fill the tile badge needs from the same single drawable.
            colorFilter = ColorFilter.tint(DifftTheme.colors.textPrimary),
            modifier = Modifier
                .size(16.dp)
                .rotate(rotationAngle),
        )
        Text(
            modifier = Modifier.testTag("call_status_notification_text"),
            text = status.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = DifftTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
