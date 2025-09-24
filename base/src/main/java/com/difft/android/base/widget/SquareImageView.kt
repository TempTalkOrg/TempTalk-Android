package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView


/**
 * SquareImageView为正方形的ImageView
 */
class SquareImageView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) :
    AppCompatImageView(context!!, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(
                widthMeasureSpec
            ) else 0
        val height =
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(
                heightMeasureSpec
            ) else 0
        val spec = Math.max(width, height)
        setMeasuredDimension(spec, spec)
    }
}
