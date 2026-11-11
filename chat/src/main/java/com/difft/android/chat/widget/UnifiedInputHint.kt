package com.difft.android.chat.widget

import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import androidx.core.content.ContextCompat
import com.difft.android.chat.R

private const val MIC_ICON_SIZE_DP = 16
private const val MIC_ICON_GAP_DP = 2


fun buildHoldToTalkHint(context: Context): CharSequence {
    val text = context.getString(R.string.chat_message_input_hint_hold_to_talk)
    val icon = ContextCompat.getDrawable(context, R.drawable.chat_ic_mic_24)?.mutate() ?: return text
    icon.setTint(ContextCompat.getColor(context, com.difft.android.base.R.color.t_disable))
    val density = context.resources.displayMetrics.density
    val size = (MIC_ICON_SIZE_DP * density).toInt()
    val gap = (MIC_ICON_GAP_DP * density).toInt()
    val drawable = InsetDrawable(icon, gap, 0, 0, 0).apply { setBounds(0, 0, size + gap, size) }
    return SpannableStringBuilder(text).apply {
        val start = length
        append(" ")
        setSpan(ImageSpan(drawable, DynamicDrawableSpan.ALIGN_CENTER), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
