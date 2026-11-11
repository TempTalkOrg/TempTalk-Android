package com.difft.android.chat.ui

import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import com.difft.android.base.utils.dp


private const val CONFIDENTIAL_SIBLING_BLUR = 16f


private const val QUOTE_BAR_ALPHA = 0x80


private const val AVATAR_COLUMN_DP = 46
private const val AVATAR_COLUMN_AFTER_CHECKBOX_DP = 40
private const val NO_AVATAR_START_DP = 26


internal fun styleQuoteBlock(zone: View, bar: View, author: TextView, text: TextView, palette: BubblePalette) {
    zone.backgroundTintList = ColorStateList.valueOf(palette.blockFill)
    zone.clipToOutline = true
    bar.setBackgroundColor(ColorUtils.setAlphaComponent(palette.text, QUOTE_BAR_ALPHA))
    author.setTextColor(palette.text)
    text.setTextColor(palette.text)
}





internal fun placeBubbleAfterAvatarColumn(container: View, senderColumn: Boolean) {
    val params = container.layoutParams as? ConstraintLayout.LayoutParams ?: return
    val marginStart = if (senderColumn) AVATAR_COLUMN_AFTER_CHECKBOX_DP.dp else 0
    val goneMarginStart = if (senderColumn) AVATAR_COLUMN_DP.dp else NO_AVATAR_START_DP.dp
    if (params.marginStart == marginStart && params.goneStartMargin == goneMarginStart) return
    params.marginStart = marginStart
    params.goneStartMargin = goneMarginStart
    container.layoutParams = params
}







internal fun maskConfidentialBubble(container: ChatMessageContainerView, around: List<View>, masked: Boolean) {
    container.masked = masked
    val cover = if (masked) BubblePalette.of(container.context, container.sent, container.chatTheme).fill else null
    around.forEach { view ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                if (masked) RenderEffect.createBlurEffect(CONFIDENTIAL_SIBLING_BLUR, CONFIDENTIAL_SIBLING_BLUR, Shader.TileMode.CLAMP) else null,
            )
        } else {
            view.foreground = cover?.let { ColorDrawable(it) }
        }
    }
}
