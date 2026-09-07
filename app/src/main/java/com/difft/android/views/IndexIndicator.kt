package com.difft.android.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import android.util.TypedValue
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import androidx.core.widget.TextViewCompat
import com.difft.android.R
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.dp

class IndexIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

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

        // Initialize with current text size (no subscription needed)
        updateSize(TextSizeUtil.isLarger)
    }

    /**
     * Update size based on text size parameter.
     * Called by parent Activity/Fragment when text size changes.
     */
    fun updateSize(isLarger: Boolean) {
        val text = findViewById<AppCompatTextView>(R.id.textview_label)
        val icon = findViewById<AppCompatImageView>(R.id.imageview_icon)

        if (isLarger) {
            applyLabelSize(text, maxSp = 21)
            icon.layoutParams.width = 35.dp
            icon.layoutParams.height = 35.dp
        } else {
            applyLabelSize(text, maxSp = 14)
            icon.layoutParams.width = 25.dp
            icon.layoutParams.height = 25.dp
        }
    }

    /**
     * Auto-size DOWN from [maxSp], never up: the phone bottom strip has room and renders at
     * [maxSp] as before, while the 72dp dual-pane rail shrinks a long label ("Contacts" at
     * 14sp needs ~62dp, more than the rail's label box) instead of ellipsizing it.
     */
    private fun applyLabelSize(text: AppCompatTextView, maxSp: Int) {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            text, LABEL_MIN_SP, maxSp, 1, TypedValue.COMPLEX_UNIT_SP
        )
    }

    private companion object {
        const val LABEL_MIN_SP = 10
    }

    private val tvBadge: AppCompatTextView by lazy { findViewById(R.id.tv_badge) }

    fun setBadgeText(text: String?, backgroundColorRes: Int = com.difft.android.chat.R.drawable.chat_missing_number_bg) {
        tvBadge.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        tvBadge.text = text.orEmpty()
        tvBadge.setBackgroundResource(backgroundColorRes)
    }
}