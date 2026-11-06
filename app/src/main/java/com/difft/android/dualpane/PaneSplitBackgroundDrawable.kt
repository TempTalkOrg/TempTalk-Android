package com.difft.android.dualpane

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Root background for the two-pane home: the list colour up to the detail pane's edge and the
 * detail colour from there on, across the root's full height. The root is padded for the
 * status bar (BaseActivity), so the panes themselves cannot paint that strip; this drawable
 * keeps each column's colour continuous through it. Columns are
 * read from the detail pane's laid-out edge (RTL-aware); [onPanesLaidOut] redraws when the
 * split moves.
 */
internal class PaneSplitBackgroundDrawable(
    private val detailPane: View,
    internal val listColor: Int,
    internal val detailColor: Int,
) : Drawable() {

    private val paint = Paint()
    private var drawnDetailStart = -1

    fun onPanesLaidOut() {
        if (currentDetailStart() != drawnDetailStart) invalidateSelf()
    }

    private fun currentDetailStart(): Int =
        if (detailPane.layoutDirection == View.LAYOUT_DIRECTION_RTL) detailPane.right else detailPane.left

    override fun draw(canvas: Canvas) {
        val b = bounds
        val detailStart = currentDetailStart()
        drawnDetailStart = detailStart
        val rtl = detailPane.layoutDirection == View.LAYOUT_DIRECTION_RTL

        paint.color = listColor
        canvas.drawRect(b, paint)

        paint.color = detailColor
        if (rtl) {
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), detailStart.toFloat(), b.bottom.toFloat(), paint)
        } else {
            canvas.drawRect(detailStart.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
