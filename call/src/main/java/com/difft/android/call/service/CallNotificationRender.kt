package com.difft.android.call.service

import androidx.annotation.StringRes
import com.difft.android.call.data.CallStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged




internal data class CallNotificationState(
    val status: CallStatus,
    val connectedAt: Long,
)
















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


@StringRes
internal fun contentTextRes(status: CallStatus): Int = when (status) {
    
    
    CallStatus.CONNECTED, CallStatus.RECONNECTED -> com.difft.android.call.R.string.call_notification_in_progress
    CallStatus.CALLING -> com.difft.android.call.R.string.call_status_calling
    CallStatus.DISCONNECTED, CallStatus.CONNECTED_FAILED, CallStatus.RECONNECT_FAILED -> com.difft.android.call.R.string.call_disconnected_title
    else -> com.difft.android.call.R.string.call_connecting_title
}
