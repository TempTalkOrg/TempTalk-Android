package com.difft.android.call.service

import androidx.annotation.StringRes
import com.difft.android.call.data.CallStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the ongoing-call notification should show. Absent means nothing should be posted.
 */
internal data class CallNotificationState(
    val status: CallStatus,
    val connectedAt: Long,
)

/**
 * Decides, from the inputs the service observes, whether a notification should be posted and what
 * it should say. Kept free of Android and injection so the rules can be exercised directly.
 *
 * Nothing is posted before the service is in the foreground, while no call state is known, or once
 * teardown has begun — the last of which would otherwise re-post a live call notification over a
 * call the user has already ended.
 *
 * The room id gates rendering too. The notification's hang-up action reaches the call session,
 * which drops a hang-up while no room id is published, so a notification posted before one is
 * assigned — an outgoing 1v1 call has a status well before the create-call response supplies the
 * id — would carry an inert control. The id is also cleared when
 * the call ends, which suppresses a status arriving after teardown has already reset the ending
 * flag.
 */
internal fun callNotificationStates(
    status: Flow<CallStatus>,
    connectedAt: Flow<Long>,
    foregroundStarted: Flow<Boolean>,
    roomId: Flow<String?>,
    isCallEnding: () -> Boolean,
): Flow<CallNotificationState?> =
    combine(status, connectedAt, foregroundStarted, roomId) { s, at, started, room ->
        when {
            !started -> null
            s == CallStatus.UNKNOWN -> null
            room == null -> null
            isCallEnding() -> null
            else -> CallNotificationState(s, at)
        }
    }.distinctUntilChanged()

/** The notification body text for a call status. */
@StringRes
internal fun contentTextRes(status: CallStatus): Int = when (status) {
    // The fallback covers states that are genuinely still in progress (joining, reconnecting,
    // switching server), so terminal states are listed explicitly rather than inheriting it.
    CallStatus.CONNECTED, CallStatus.RECONNECTED -> com.difft.android.call.R.string.call_notification_in_progress
    CallStatus.CALLING -> com.difft.android.call.R.string.call_status_calling
    CallStatus.DISCONNECTED, CallStatus.CONNECTED_FAILED, CallStatus.RECONNECT_FAILED -> com.difft.android.call.R.string.call_disconnected_title
    else -> com.difft.android.call.R.string.call_connecting_title
}
