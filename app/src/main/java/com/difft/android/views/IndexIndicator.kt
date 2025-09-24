package com.difft.android.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import com.difft.android.R
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.dp

class IndexIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    init {
        val layoutInflater = LayoutInflater.from(context)
        layoutInflater.inflate(R.layout.layout_index_indicator, this)

        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.IndexIndicator,
            0, 0
        ).use {
            val text = it.getText(R.styleable.IndexIndicator_android_text)
            val src = it.getDrawable(R.styleable.IndexIndicator_android_src)
            val textColor = it.getColorStateList(R.styleable.IndexIndicator_android_textColor)

            findViewById<AppCompatTextView>(R.id.textview_label)?.apply {
                this.text = text
                this.setTextColor(textColor)
            }
            findViewById<AppCompatImageView>(R.id.imageview_icon)
                ?.setImageDrawable(src)
        }

        updateSize()
    }

    fun updateSize() {
        val text = findViewById<AppCompatTextView>(R.id.textview_label)
        val icon = findViewById<AppCompatImageView>(R.id.imageview_icon)
        if (TextSizeUtil.isLager()) {
            text.textSize = 21f
            icon.layoutParams.width = 35.dp
            icon.layoutParams.height = 35.dp
        } else {
            text.textSize = 14f
            icon.layoutParams.width = 25.dp
            icon.layoutParams.height = 25.dp
        }
    }

    private val tvBadge: AppCompatTextView by lazy { findViewById(R.id.tv_badge) }

    fun setBadgeText(text: String?, backgroundColorRes: Int = com.difft.android.chat.R.drawable.chat_missing_number_bg) {
        tvBadge.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        tvBadge.text = text.orEmpty()
        tvBadge.setBackgroundResource(backgroundColorRes)
    }
}