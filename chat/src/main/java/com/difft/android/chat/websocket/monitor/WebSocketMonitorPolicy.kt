package com.difft.android.chat.websocket.monitor

import com.difft.android.websocket.api.websocket.LocalDisconnectReason
import com.difft.android.websocket.api.websocket.WebSocketConnectionState

/** What the monitor should do about a state, split out so the branch matrix is unit-testable. */
internal enum class ReconnectPolicy { RECONNECT_SWITCH_HOST, RECONNECT_KEEP_HOST, CONNECTED, IGNORE }

/**
 * Why a monitor wait ended before its uptime timeout. [KEEP_ALIVE_ALARM] is the routine
 * background cadence (see [KeepAliveWakeAlarm]); [CONNECTED] comes from the connection state
 * and only re-enters the wait so the wake alarm covers the new socket; the rest are genuine
 * external events.
 */
internal enum class WakeReason { SERVICE_ALARM, NETWORK, FOREGROUND, KEEP_ALIVE_ALARM, CONNECTED }

private const val RECONNECT_BACKOFF_CAP_FOREGROUND_MS = 5_000L
private const val RECONNECT_BACKOFF_CAP_BACKGROUND_MS = 30_000L

/**
 * Cap for the exponential reconnect backoff. Foreground keeps 5 s: someone is watching the
 * connection state, and iOS / Desktop retry on a fixed 5 s timer. Background uses
 * Signal-Android's 30 s cap, so a network that is present but unusable (Doze restriction,
 * dead Wi-Fi) costs a handful of attempts per 5-minute alarm period instead of one every ~15 s.
 * Any wake event still cuts the wait short and the attempt count resets on CONNECTED.
 */
internal fun reconnectBackoffCapMs(isForegrounded: Boolean): Long =
    if (isForegrounded) RECONNECT_BACKOFF_CAP_FOREGROUND_MS else RECONNECT_BACKOFF_CAP_BACKGROUND_MS

/**
 * Signal-Android's missed keep-alive rule: on the uptime timer tick a full cadence has passed
 * since the previous send, so a keep-alive still unanswered means a dead socket. Wake events are
 * excluded because they can follow the previous send by milliseconds; the keep-alive alarm is
 * covered by the response deadline instead. [previousSendElapsed] is 0 until the current socket
 * has sent one.
 */
internal fun keepAliveUnansweredForCadence(
    wakeReason: WakeReason?,
    previousSendElapsed: Long,
    lastResponseElapsed: Long,
): Boolean = wakeReason == null && previousSendElapsed != 0L && lastResponseElapsed < previousSendElapsed

/**
 * Auth-level rejections keep the host: the server answered correctly, it rejected the *credential*.
 * Recording the host as failed would pollute host selection, and reporting it as a WS failure would
 * feed a network-quality counter that triggers a speed test. Reconnecting is still right — a false
 * alarm self-heals into CONNECTED, and a real kick keeps re-emitting until the guard confirms it.
 * A disconnect the server initiated with a close frame ([closedByServer], e.g. its 90 s idle
 * timeout) keeps the host for the same reason: the host is reachable and answering.
 *
 * A bare `DISCONNECTED` is a drop this app made itself, and says nothing about the host unless the
 * reason does ([localDisconnectReason], #1396): a connect deadline or lost keep-alives switch host,
 * a proxy-change cancel or a stop keep it, and so does a disconnect with no recorded reason.
 */
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
