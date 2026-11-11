package com.difft.android.chat.ui

import android.view.View
import androidx.core.view.isVisible
import com.difft.android.chat.common.SendType

















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
        
        
        statusRow.isClickable = false
    }
}
