package com.difft.android.chat.mediasend

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.android.permission.MediaReadDenialKind
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R

/**
 * The only place a media send failure becomes something the user sees.
 *
 * Owns the failure -> wording mapping, the retry-entry decision and the N-of-M framing, so the two
 * hosting screens stay free of all three — both are already over the file-size limit, and a copy of
 * this `when` in either of them would drift from this one.
 *
 * It never logs: the classifier already emitted exactly one line per failure.
 */
object MediaSendFailureNotice {

    /** Beyond this many items the dialog stops listing and switches to a remainder count. */
    private const val MAX_LISTED = 3

    /**
     * Nothing could be sent. The caller must NOT finish the screen: the typed caption and the whole
     * selection live on it, and finishing would discard both to report a failure.
     */
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
            // A reason whose fix is out of band must not be offered a retry entry: that entry is a
            // promise the app cannot keep.
            showCancel = retryable,
            cancelable = false,
            onConfirm = { if (retryable) onRetry() },
        )
    }

    /**
     * Some items made it. Either branch keeps everything: proceeding sends the successes with the
     * caption, going back leaves the selection and the caption untouched.
     */
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

    /** A throwable that escaped the whole send. Same surface as an all-failed batch of one. */
    fun showThrown(context: Context, failure: MediaFailure, onRetry: () -> Unit) =
        showAllFailed(context, listOf(failure), onRetry)

    /**
     * Residual failure at the attachment-copy step, i.e. after the review screen is gone. A toast,
     * not a dialog: the batch loop fires one call per item and stacked modal dialogs would freeze
     * the chat screen. No position is shown — the loop index counts successes only.
     */
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

    /**
     * Up to [MAX_LISTED] item lines, a remainder count, then exactly one next step.
     *
     * The next step is taken from the first failure rather than one per item: a mixed-reason batch
     * is rare (one fault usually explains the whole batch), and a sentence per item makes the dialog
     * unreadable. Every item still carries its own short reason label on its own line.
     */
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
                // GRANTED_BUT_UNREADABLE *and* null (no attribution available): the wording that
                // names neither "permission not granted" nor "file moved or deleted", and offers
                // restarting the app, which helps whichever mechanism is at play.
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
