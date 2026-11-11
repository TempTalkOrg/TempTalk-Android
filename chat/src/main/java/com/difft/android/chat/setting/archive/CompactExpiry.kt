package com.difft.android.chat.setting.archive

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.difft.android.chat.R
import com.difft.android.base.R as BaseR


fun Long.toCompactExpiryText(context: Context): String {
    if (this <= 0L) return ""
    val (unitSeconds, suffix) = when {
        this >= 86_400L -> 86_400L to BaseR.string.DateUtils_days_short
        this >= 3_600L -> 3_600L to BaseR.string.DateUtils_hours_short
        this >= 60L -> 60L to R.string.chat_expiry_minutes_short
        else -> 1L to BaseR.string.DateUtils_seconds_short
    }
    return "${this / unitSeconds}${context.getString(suffix)}"
}


internal fun bindCompactExpiry(row: View, label: TextView, seconds: Long?) {
    val text = (seconds ?: 0L).toCompactExpiryText(row.context)
    row.isVisible = text.isNotEmpty()
    label.text = text
    row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    row.contentDescription = seconds?.takeIf { it > 0L }?.toArchiveTimeDisplayText()
}
