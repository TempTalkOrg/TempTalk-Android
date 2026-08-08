package com.difft.android.chat.ui

import android.view.View
import androidx.core.view.isVisible
import com.difft.android.chat.common.SendType

/**
 * Binds the out-of-bubble "send failed" status row of an outgoing (mine) message.
 *
 * Top-level rather than a ViewHolder member so it can be exercised against the REAL inflated
 * `chat_item_chat_message_list_text_mine` layout in the JVM/Robolectric harness, matching the
 * [bindQuoteThumbnail] precedent.
 *
 * The row is the ONLY failed-state affordance: it renders for [SendType.SentFailed] and is fully
 * reset — hidden, listener cleared, not clickable — for every other value, so a recycled row can
 * never keep the previous message's retry affordance.
 *
 * @param statusRow the `ll_send_fail_status` row (icon + label); the whole row is the hit target.
 * @param sendStatus a [SendType] rawValue; null / unknown hides the row.
 * @param onRetryClick invoked on a row tap. Callers forward to the bubble's own click handler so
 *   retry keeps flowing through the single pre-existing resend path.
 */
internal fun bindSendFailStatusRow(
    statusRow: View,
    sendStatus: Int?,
    onRetryClick: () -> Unit
) {
    val failed = sendStatus == SendType.SentFailed.rawValue
    statusRow.isVisible = failed
    if (failed) {
        statusRow.setOnClickListener { onRetryClick() }
    } else {
        statusRow.setOnClickListener(null)
        // setOnClickListener(null) does NOT clear clickable — clear it explicitly so a recycled
        // row is inert.
        statusRow.isClickable = false
    }
}
