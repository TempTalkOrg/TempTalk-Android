package com.difft.android.call.handler

import android.app.Activity
import androidx.lifecycle.LifecycleCoroutineScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.exception.NetworkConnectionPoorException
import com.difft.android.call.exception.StartCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException

/**
 * 统一管理通话相关的错误处理
 * 负责处理各种异常情况并显示相应的错误提示
 */
class CallErrorHandler(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val callIntent: CallIntent,
    private val onEndCall: () -> Unit,
    private val onDismissError: () -> Unit
) {
    /**
     * 处理错误
     * @param error 要处理的异常
     */
    fun handleError(error: Throwable) {
        when (error) {
            is CancellationException -> {
                handleCancellationException(error)
            }

            is SocketTimeoutException -> {
                handleSocketTimeoutException(error)
            }

            is NetworkConnectionPoorException -> {
                handleNetworkConnectionPoorException(error)
            }

            is StartCallException -> {
                handleStartCallException(error)
            }

            else -> {
                handleGenericException(error)
            }
        }
    }

    /**
     * 处理取消异常
     * 通常发生在协程被取消时，不需要显示错误提示
     */
    private fun handleCancellationException(error: CancellationException) {
        L.e { "[Call] CallErrorHandler, CancellationException: $error" }
        onDismissError()
    }

    /**
     * 处理连接超时异常
     */
    private fun handleSocketTimeoutException(error: SocketTimeoutException) {
        L.e { "[Call] CallErrorHandler, SocketTimeoutException: $error" }
        showErrorTip(
            message = activity.getString(R.string.call_connect_timeout_tip),
            onDismiss = onDismissError
        )
    }

    /**
     * 处理网络连接质量差异常
     */
    private fun handleNetworkConnectionPoorException(error: NetworkConnectionPoorException) {
        L.e { "[Call] CallErrorHandler, NetworkConnectionPoorException: ${error.message}" }
        val message = error.message ?: activity.getString(R.string.call_connect_exception_error)
        showErrorTip(
            message = message,
            onDismiss = onDismissError
        )
    }

    /**
     * 处理启动/加入通话异常
     */
    private fun handleStartCallException(error: StartCallException) {
        L.e { "[Call] CallErrorHandler, StartCallException: ${error.message}" }
        val message = error.message ?: if (callIntent.action == CallIntent.Action.START_CALL) {
            activity.getString(R.string.call_start_failed_tip)
        } else {
            activity.getString(R.string.call_join_failed_tip)
        }
        showErrorTip(
            message = message,
            onDismiss = onEndCall
        )
    }

    /**
     * 处理通用异常
     * 对于未知异常，延迟显示错误提示，避免在连接过程中频繁弹出
     */
    private fun handleGenericException(error: Throwable) {
        L.e { "[Call] CallErrorHandler, GenericException: $error" }
        lifecycleScope.launch {
            delay(2000L) // 延迟2秒显示，避免在连接过程中频繁弹出
            showErrorTip(
                message = activity.getString(R.string.call_connect_exception_error),
                onDismiss = onEndCall
            )
        }
    }

    /**
     * 显示错误提示
     * @param message 错误消息
     * @param onDismiss 关闭时的回调
     */
    private fun showErrorTip(message: String, onDismiss: () -> Unit) {
        ToastUtil.show(message)
        onDismiss()
    }
}