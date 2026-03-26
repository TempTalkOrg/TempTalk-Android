package com.difft.android.base.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.base.R
import com.difft.android.base.utils.dp

class ConfidentialTipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)
        alpha = 0.9f
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.bg_tooltip))
            cornerRadius = 100f.dp
        }

        val icon = ImageView(context).apply {
            layoutParams = LayoutParams(14.dp, 14.dp)
            setImageResource(R.drawable.ic_confidential_tip)
        }
        addView(icon)

        val text = TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 4.dp }
            setText(R.string.confidential_preview_tip)
            setTextColor(ContextCompat.getColor(context, R.color.t_white))
            textSize = 12f
        }
        addView(text)
    }
}
