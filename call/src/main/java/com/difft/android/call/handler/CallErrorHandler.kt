package com.difft.android.call.handler

import android.app.Activity
import androidx.lifecycle.LifecycleCoroutineScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.exception.DisconnectException
import com.difft.android.call.exception.NetworkConnectionPoorException
import com.difft.android.call.exception.ServerConnectionException
import com.difft.android.call.exception.StartCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 统一管理通话相关的错误处理
 * 负责处理各种异常情况并显示相应的错误提示
 */
class CallErrorHandler(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val callIntent: CallIntent,
    private val onEndCall: () -> Unit,
) {
    private var pendingErrorJob: Job? = null

    fun handleError(error: Throwable) {
        pendingErrorJob?.cancel()
        pendingErrorJob = null

        when (error) {
            is CancellationException -> {
                L.e { "[Call] CallErrorHandler, CancellationException: $error" }
            }

            is NetworkConnectionPoorException -> {
                L.e { "[Call] CallErrorHandler, NetworkConnectionPoorException: ${error.message}" }
                showToast(activity.getString(R.string.call_myself_network_poor_tip))
            }

            is StartCallException -> {
                L.e { "[Call] CallErrorHandler, StartCallException: ${error.message}" }
                val message = error.message ?: if (callIntent.action == CallIntent.Action.START_CALL) {
                    activity.getString(R.string.call_start_failed_tip)
                } else {
                    activity.getString(R.string.call_join_failed_tip)
                }
                showToast(message)
                onEndCall()
            }

            is ServerConnectionException -> {
                L.e { "[Call] CallErrorHandler, ServerConnectionException: ${error.message}" }
                showToast(error.message ?: activity.getString(R.string.call_server_connect_exception_error))
                onEndCall()
            }

            is DisconnectException -> {
                L.e { "[Call] CallErrorHandler, DisconnectException: ${error.message}" }
                showToast(activity.getString(R.string.call_room_connect_exception_error))
                onEndCall()
            }

            else -> {
                L.e { "[Call] CallErrorHandler, GenericException: $error" }
                pendingErrorJob = lifecycleScope.launch {
                    delay(2000L)
                    showToast(error.message ?: activity.getString(R.string.call_server_connect_exception_error))
                    onEndCall()
                }
            }
        }
    }

    private fun showToast(message: String) {
        ToastUtil.show(message)
    }
}
