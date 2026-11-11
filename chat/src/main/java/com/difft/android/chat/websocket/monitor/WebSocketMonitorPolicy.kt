package com.difft.android.chat.websocket.monitor

import com.difft.android.websocket.api.websocket.LocalDisconnectReason
import com.difft.android.websocket.api.websocket.WebSocketConnectionState


internal enum class ReconnectPolicy { RECONNECT_SWITCH_HOST, RECONNECT_KEEP_HOST, CONNECTED, IGNORE }







internal enum class WakeReason { SERVICE_ALARM, NETWORK, FOREGROUND, KEEP_ALIVE_ALARM, CONNECTED }

private const val RECONNECT_BACKOFF_CAP_FOREGROUND_MS = 5_000L
private const val RECONNECT_BACKOFF_CAP_BACKGROUND_MS = 30_000L








internal fun reconnectBackoffCapMs(isForegrounded: Boolean): Long =
    if (isForegrounded) RECONNECT_BACKOFF_CAP_FOREGROUND_MS else RECONNECT_BACKOFF_CAP_BACKGROUND_MS








internal fun keepAliveUnansweredForCadence(
    wakeReason: WakeReason?,
    previousSendElapsed: Long,
    lastResponseElapsed: Long,
): Boolean = wakeReason == null && previousSendElapsed != 0L && lastResponseElapsed < previousSendElapsed













internal fun reconnectPolicyFor(
    state: WebSocketConnectionState,
    closedByServer: Boolean = false,
    localDisconnectReason: LocalDisconnectReason? = null,
): ReconnectPolicy = when (state) {
    WebSocketConnectionState.AUTHENTICATION_FAILED,
    WebSocketConnectionState.INACTIVE_FAILED -> ReconnectPolicy.RECONNECT_KEEP_HOST

    WebSocketConnectionState.DISCONNECTED ->
        if (!closedByServer && localDisconnectReason?.switchesHost == true) {
            ReconnectPolicy.RECONNECT_SWITCH_HOST
        } else {
            ReconnectPolicy.RECONNECT_KEEP_HOST
        }

    WebSocketConnectionState.FAILED,
    WebSocketConnectionState.UNKNOWN_HOST_FAILED -> ReconnectPolicy.RECONNECT_SWITCH_HOST

    WebSocketConnectionState.CONNECTED -> ReconnectPolicy.CONNECTED

    WebSocketConnectionState.CONNECTING -> ReconnectPolicy.IGNORE
}
