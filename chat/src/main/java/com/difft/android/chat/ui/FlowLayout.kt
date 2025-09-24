package com.difft.android.chat.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max

class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private var maxWidth = Int.MAX_VALUE
    private var lastLineWidth = 0
    private var lineCount = 0

    fun setMaxWidth(maxWidth: Int) {
        this.maxWidth = maxWidth
        requestLayout()
    }
    
    fun getLastLineWidth(): Int {
        return lastLineWidth
    }

    fun getLineCount(): Int {
        return lineCount
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        var lineWidth = 0
        var lineHeight = 0
        var maxWidthUsed = 0
        var totalHeight = 0
        
        // Reset tracking variables
        lastLineWidth = 0
        lineCount = if (childCount > 0) 1 else 0  // Start with 1 line if we have children


        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val lp = child.layoutParams as MarginLayoutParams

            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin


            if (lineWidth + childWidth <= widthSize - paddingLeft - paddingRight) {
//                if (lineWidth + childWidth <= minOf(maxWidth, widthSize - paddingLeft - paddingRight)) {
                // Add the child to the current line
                lineWidth += childWidth
                lineHeight = max(lineHeight, childHeight)
                maxWidthUsed = max(maxWidthUsed, lineWidth)
            } else {
                // Start a new line
                totalHeight += lineHeight
                lineWidth = childWidth
                lineHeight = childHeight
                maxWidthUsed = max(maxWidthUsed, lineWidth)
                lineCount++  // Increment line count when starting a new line
            }

            if (i == childCount - 1) {
                // Last child, add the remaining line height
                totalHeight += lineHeight
                maxWidthUsed = max(maxWidthUsed, lineWidth)
                // Track the last line width for time view positioning
                lastLineWidth = lineWidth
            }
        }

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(maxWidthUsed + paddingLeft + paddingRight, widthSize)
            else -> maxWidthUsed + paddingLeft + paddingRight
        }

        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(totalHeight + paddingTop + paddingBottom, MeasureSpec.getSize(heightMeasureSpec))
            else -> totalHeight + paddingTop + paddingBottom
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
        
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val parentLeft = paddingLeft
        val parentTop = paddingTop

        var childLeft = parentLeft
        var childTop = parentTop
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (childLeft + childWidth <= right - left - paddingRight) {
//                if (childLeft + childWidth <= maxOf(maxWidth, right - left - paddingRight)) {
                // Add the child to the current line
                child.layout(
                    childLeft + lp.leftMargin,
                    childTop + lp.topMargin,
                    childLeft + childWidth - lp.rightMargin,
                    childTop + childHeight - lp.bottomMargin
                )

                childLeft += childWidth
                lineHeight = max(lineHeight, childHeight)
            } else {
                // Start a new line
                childLeft = parentLeft
                childTop += lineHeight
                lineHeight = 0

                child.layout(
                    childLeft + lp.leftMargin,
                    childTop + lp.topMargin,
                    childLeft + childWidth - lp.rightMargin,
                    childTop + childHeight - lp.bottomMargin
                )

                childLeft += childWidth
                lineHeight = max(lineHeight, childHeight)
            }
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams {
        return MarginLayoutParams(p)
    }
}
