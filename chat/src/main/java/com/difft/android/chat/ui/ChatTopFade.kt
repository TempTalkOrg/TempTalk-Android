package com.difft.android.chat.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.difft.android.base.ui.topchrome.TopChromeGlassDrawable
import com.difft.android.base.ui.topchrome.TopChromeStyle
import com.difft.android.chat.R
import kotlin.math.roundToInt


object ChatTopFade {
    const val HEAD_ALPHA = 0.85f

    fun color(context: Context): Int =
        ColorUtils.setAlphaComponent(ContextCompat.getColor(context, com.difft.android.base.R.color.bg1), (HEAD_ALPHA * 255).roundToInt())
}








class ChatTopFadeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val tail = resources.getDimensionPixelSize(R.dimen.chat_top_fade_tail)
    private var surface: TopChromeGlassDrawable? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (surface == null) {
            surface = TopChromeGlassDrawable(this, TopChromeStyle.Feather) { (height - tail).toFloat() }
                .also { background = it }
        }
    }

    override fun onDetachedFromWindow() {
        surface?.close()
        surface = null
        background = null
        super.onDetachedFromWindow()
    }
}
