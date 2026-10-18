package com.difft.android.call.handler

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.R
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.exception.ServerConnectionException
import com.difft.android.call.exception.StartCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The call session's handling of a room error: an app toast naming the failure, then the end of the
 * call ([onEndCall]). Start, server and disconnect errors end at once; any other error ends after
 * [GENERIC_ERROR_DELAY_MS], and a later error replaces it. Nothing is done while the call is not
 * live ([isLive]: ended or ending), checked again when the delayed end fires.
 *
 * Main-confined: [handleError] and [cancelPending] run on main, the delayed end on [scope].
 */
class CallErrorHandler(
    private val scope: CoroutineScope,
    private val isStartAction: Boolean,
    private val isLive: () -> Boolean,
    private val onEndCall: () -> Unit,
    private val getString: (Int) -> String = { ResUtils.getString(it) },
    private val showToast: (String) -> Unit = { ToastUtil.show(it) },
) {
    private var pendingErrorJob: Job? = null

    fun handleError(error: Throwable) {
        cancelPending()
        if (!isLive()) {
            L.i { "[Call] CallErrorHandler error after the call ended dropped type=${error.javaClass.simpleName}" }
            return
        }

        when (error) {
            is CancellationException -> {
                L.e { "[Call] CallErrorHandler, CancellationException: $error" }
            }

            is StartCallException -> {
                L.e { "[Call] CallErrorHandler, StartCallException: ${error.message}" }
                val fallback = if (isStartAction) R.string.call_start_failed_tip else R.string.call_join_failed_tip
                endWithTip(error.message ?: getString(fallback))
            }

            is ServerConnectionException -> {
                L.e { "[Call] CallErrorHandler, ServerConnectionException: ${error.message}" }
                endWithTip(error.message ?: getString(R.string.call_server_connect_exception_error))
            }

            is DisconnectException -> {
                L.e { "[Call] CallErrorHandler, DisconnectException: ${error.message}" }
                endWithTip(getString(R.string.call_room_connect_exception_error))
            }

            else -> {
                L.e { "[Call] CallErrorHandler, GenericException: $error" }
                pendingErrorJob = scope.launch {
                    delay(GENERIC_ERROR_DELAY_MS)
                    if (!isLive()) return@launch
                    endWithTip(error.message ?: getString(R.string.call_server_connect_exception_error))
                }
            }
        }
    }

    /** Drops a pending delayed end; the session's end calls it. */
    fun cancelPending() {
        pendingErrorJob?.cancel()
        pendingErrorJob = null
    }

    private fun endWithTip(message: String) {
        showToast(message)
        onEndCall()
    }

    private companion object {
        const val GENERIC_ERROR_DELAY_MS = 2_000L
    }
}
