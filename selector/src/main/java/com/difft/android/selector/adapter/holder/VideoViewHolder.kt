package com.difft.android.selector.adapter.holder

import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import com.difft.android.selector.R
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.utils.DateUtils
import com.difft.android.selector.utils.StyleUtils

class VideoViewHolder(itemView: View, config: SelectorConfig) : BaseRecyclerMediaHolder(itemView, config) {
    private val tvDuration: TextView

    init {
        tvDuration = itemView.findViewById(R.id.tv_duration)
        val adapterStyle = selectorConfig!!.selectorStyle.selectMainStyle!!
        val drawableLeft = adapterStyle.adapterDurationDrawableLeft
        if (StyleUtils.checkStyleValidity(drawableLeft)) {
            tvDuration.setCompoundDrawablesRelativeWithIntrinsicBounds(drawableLeft, 0, 0, 0)
        }
        val textSize = adapterStyle.adapterDurationTextSize
        if (StyleUtils.checkSizeValidity(textSize)) {
            tvDuration.textSize = textSize.toFloat()
        }
        val textColor = adapterStyle.adapterDurationTextColor
        if (StyleUtils.checkStyleValidity(textColor)) {
            tvDuration.setTextColor(textColor)
        }

        val shadowBackground = adapterStyle.adapterDurationBackgroundResources
        if (StyleUtils.checkStyleValidity(shadowBackground)) {
            tvDuration.setBackgroundResource(shadowBackground)
        }

        val durationGravity = adapterStyle.adapterDurationGravity
        if (StyleUtils.checkArrayValidity(durationGravity)) {
            val lp = tvDuration.layoutParams
            if (lp is RelativeLayout.LayoutParams) {
                lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                for (i in durationGravity!!) {
                    lp.addRule(i)
                }
            }
        }
    }

    override fun bindData(media: LocalMedia, position: Int) {
        super.bindData(media, position)
        tvDuration.text = DateUtils.formatDurationTime(media.duration)
    }
}
