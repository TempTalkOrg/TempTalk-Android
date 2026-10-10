package com.difft.android.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
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
            // Library Bottombar: 24 dp icon, 10 sp label.
            applyLabelSize(text, maxSp = 10)
            icon.layoutParams.width = 24.dp
            icon.layoutParams.height = 24.dp
        }
    }

    /**
     * Auto-size DOWN from [maxSp], never up: the phone bottom strip has room and renders at
     * [maxSp], while the 72dp dual-pane rail shrinks a long label ("Contacts") instead of
     * ellipsizing it. [LABEL_MIN_SP] must stay strictly below every [maxSp] passed here —
     * setAutoSizeTextTypeUniformWithConfiguration rejects max <= min.
     */
    private fun applyLabelSize(text: AppCompatTextView, maxSp: Int) {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            text, LABEL_MIN_SP, maxSp, 1, TypedValue.COMPLEX_UNIT_SP
        )
    }

    private companion object {
        const val LABEL_MIN_SP = 8
    }

    private val tvBadge: AppCompatTextView by lazy { findViewById(R.id.tv_badge) }

    fun setBadgeText(text: String?, backgroundColorRes: Int = com.difft.android.chat.R.drawable.chat_missing_number_bg) {
        tvBadge.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        tvBadge.text = text.orEmpty()
        tvBadge.setBackgroundResource(backgroundColorRes)
        applyBadgeSize(dot = false)
    }

    /**
     * A plain dot with no number (#1332): shown when the only unread state is a manual "mark as
     * unread", which is not counted. Same view as the count badge, so the geometry is switched
     * explicitly and restored for the next count.
     */
    fun setBadgeDot(backgroundColorRes: Int = com.difft.android.chat.R.drawable.chat_missing_number_bg) {
        tvBadge.visibility = VISIBLE
        tvBadge.text = ""
        tvBadge.setBackgroundResource(backgroundColorRes)
        applyBadgeSize(dot = true)
    }

    private var badgeIsDot = false

    private fun applyBadgeSize(dot: Boolean) {
        if (dot == badgeIsDot) return // the rooms flow emits often; only relayout on a real switch
        badgeIsDot = dot
        val density = resources.displayMetrics.density
        val minPx = ((if (dot) BADGE_DOT_DP else BADGE_COUNT_MIN_DP) * density).toInt()
        val paddingPx = ((if (dot) 0 else BADGE_COUNT_PADDING_DP) * density).toInt()
        tvBadge.minWidth = minPx
        tvBadge.minHeight = minPx
        tvBadge.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        tvBadge.layoutParams = tvBadge.layoutParams.apply {
            width = if (dot) minPx else ViewGroup.LayoutParams.WRAP_CONTENT
            height = if (dot) minPx else ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }
}

/** XML geometry of tv_badge (layout_index_indicator.xml), restored after a dot render. */
private const val BADGE_COUNT_MIN_DP = 16
private const val BADGE_COUNT_PADDING_DP = 2

/** Tab dot for a manual-unread-only state (design: 10dp). */
private const val BADGE_DOT_DP = 10