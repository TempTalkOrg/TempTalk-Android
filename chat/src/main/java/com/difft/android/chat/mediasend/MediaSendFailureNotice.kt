package com.difft.android.chat.mediasend

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.android.permission.MediaReadDenialKind
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R










object MediaSendFailureNotice {

    
    private const val MAX_LISTED = 3

    



    fun showAllFailed(context: Context, failures: List<MediaFailure>, onRetry: () -> Unit) {
        val retryable = failures.all { it.reason.retryable }
        ComposeDialogManager.showMessageDialog(
            context = context,
            title = if (failures.size == 1) {
                context.getString(R.string.media_send_failed_title_single)
            } else {
                context.getString(R.string.media_send_failed_title_all, failures.size)
            },
            message = bodyOf(context, failures),
            confirmText = context.getString(
                if (retryable) R.string.media_send_failed_retry else R.string.chat_dialog_ok
            ),
            cancelText = context.getString(R.string.media_send_failed_back),
            
            
            showCancel = retryable,
            cancelable = false,
            onConfirm = { if (retryable) onRetry() },
        )
    }

    



    fun showPartial(context: Context, failures: List<MediaFailure>, sentCount: Int, onProceed: () -> Unit) {
        ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(
                R.string.media_send_failed_title_partial, sentCount + failures.size, failures.size
            ),
            message = bodyOf(context, failures),
            confirmText = context.getString(R.string.media_send_failed_send_rest, sentCount),
            cancelText = context.getString(R.string.media_send_failed_back),
            cancelable = false,
            onConfirm = { onProceed() },
        )
    }

    
    fun showThrown(context: Context, failure: MediaFailure, onRetry: () -> Unit) =
        showAllFailed(context, listOf(failure), onRetry)

    




    fun showStagingFailure(context: Context, failure: MediaFailure) {
        val reason = reasonText(context, failure)
        val name = failure.displayName
        ToastUtil.showLong(
            if (name.isNullOrEmpty()) {
                context.getString(R.string.media_send_failed_unnamed, reason)
            } else {
                context.getString(R.string.media_send_failed_named, name, reason)
            }
        )
    }

    






    @VisibleForTesting
    internal fun bodyOf(context: Context, failures: List<MediaFailure>): String = buildString {
        failures.take(MAX_LISTED).forEach { failure ->
            val reason = reasonText(context, failure)
            val name = failure.displayName
            appendLine(
                if (name.isNullOrEmpty()) {
                    context.getString(R.string.media_send_failed_item_unnamed, failure.position, reason)
                } else {
                    context.getString(R.string.media_send_failed_item_named, failure.position, name, reason)
                }
            )
        }
        val remaining = failures.size - MAX_LISTED
        if (remaining > 0) appendLine(context.getString(R.string.media_send_failed_more, remaining))
        failures.firstOrNull()?.let { appendLine().append(nextStepText(context, it)) }
    }

    @VisibleForTesting
    internal fun reasonText(context: Context, failure: MediaFailure): String = context.getString(
        when (failure.reason) {
            MediaFailureReason.SOURCE_UNREADABLE -> R.string.media_send_reason_unreadable
            MediaFailureReason.MEDIA_UNSUPPORTED -> R.string.media_send_reason_unsupported
            MediaFailureReason.OUT_OF_SPACE -> R.string.media_send_reason_no_space
            MediaFailureReason.TRANSFORM_FAILED -> R.string.media_send_reason_transform
            MediaFailureReason.UNKNOWN -> R.string.media_send_reason_unknown
        }
    )

    @VisibleForTesting
    internal fun nextStepText(context: Context, failure: MediaFailure): String = when (failure.reason) {
        MediaFailureReason.SOURCE_UNREADABLE -> context.getString(
            when (failure.denialKind) {
                MediaReadDenialKind.PERMISSION_MISSING -> R.string.media_send_next_grant
                MediaReadDenialKind.PARTIAL_SELECTION -> R.string.media_send_next_reselect_photos
                MediaReadDenialKind.NOT_MEDIA_SCOPED -> R.string.media_send_next_reselect_item
                
                
                
                else -> R.string.media_send_next_restart_or_file
            }
        )
        MediaFailureReason.MEDIA_UNSUPPORTED -> context.getString(R.string.media_send_next_unsupported)
        MediaFailureReason.OUT_OF_SPACE -> context.getString(R.string.media_send_next_free_space)
        MediaFailureReason.TRANSFORM_FAILED -> context.getString(R.string.media_send_next_retry_or_plain)
        MediaFailureReason.UNKNOWN -> context.getString(
            R.string.media_send_next_unknown, failure.reason.code
        )
    }
}
