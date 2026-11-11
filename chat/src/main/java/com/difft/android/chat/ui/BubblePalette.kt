package com.difft.android.chat.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.difft.android.base.R
import com.difft.android.chat.ui.theme.ChatBubbleGradient
import com.difft.android.chat.ui.theme.ChatTheme
import com.difft.android.chat.R as ChatR










internal class BubblePalette private constructor(
    
    @ColorInt val text: Int,
    
    @ColorInt val secondaryText: Int,
    
    @ColorInt val meta: Int,
    
    @ColorInt val link: Int,
    
    @ColorInt val blockFill: Int,
    
    @ColorInt val icon: Int,
    
    @ColorInt val barPlayed: Int,
    @ColorInt val barUnplayed: Int,
    
    @ColorInt val fill: Int,
    
    val linkMedium: Boolean,
) {
    companion object {
        private const val SENT_SECONDARY_ALPHA = 0.85f

        fun of(context: Context, sent: Boolean, theme: ChatTheme = ChatTheme.CLASSIC): BubblePalette {
            fun color(id: Int) = ContextCompat.getColor(context, id)
            val gradient = theme == ChatTheme.GRADIENT
            return if (sent) {
                val white = color(R.color.bubble_sent_text)
                BubblePalette(
                    text = white,
                    secondaryText = ColorUtils.setAlphaComponent(white, (SENT_SECONDARY_ALPHA * 255).toInt()),
                    meta = color(R.color.bubble_sent_meta),
                    link = color(R.color.bubble_sent_link),
                    blockFill = color(R.color.bubble_sent_quote),
                    icon = white,
                    barPlayed = white,
                    barUnplayed = color(R.color.bubble_sent_bar),
                    fill = if (gradient) color(ChatR.color.chat_bubble_gradient_bottom) else color(R.color.bubble_sent),
                    linkMedium = gradient,
                )
            } else {
                BubblePalette(
                    text = color(R.color.t_primary),
                    secondaryText = color(R.color.t_secondary),
                    meta = color(R.color.t_third),
                    link = color(R.color.t_info),
                    blockFill = color(R.color.bubble_recv_quote),
                    icon = color(R.color.icon),
                    barPlayed = color(R.color.t_primary),
                    barUnplayed = color(R.color.t_disable),
                    fill = if (gradient) color(ChatR.color.chat_bubble_gradient_recv) else color(R.color.bubble_recv),
                    linkMedium = gradient,
                )
            }
        }
    }
}


internal fun View.bubblePalette(): BubblePalette {
    var view: View? = this
    while (view != null) {
        if (view is ChatMessageContainerView) return BubblePalette.of(context, view.sent, view.chatTheme)
        view = view.parent as? View
    }
    return BubblePalette.of(context, sent = false)
}


internal fun bubbleFillDrawable(context: Context, sent: Boolean, theme: ChatTheme): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = BUBBLE_RADIUS_DP * context.resources.displayMetrics.density
        setColor(BubblePalette.of(context, sent, theme).fill)
    }


internal const val BUBBLE_RADIUS_DP = 16f
