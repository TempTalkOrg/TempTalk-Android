package com.difft.android.dualpane

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Dual-pane root background in three columns over the full height: the navigation rail's column in
 * [railColor], the detail pane's column in [detailColor], the list pane between them in [listColor].
 * Edge-to-edge, the root's background is what shows through the status and navigation bar strips,
 * so each strip continues the column below it instead of running one colour across columns that
 * differ (the Me tab's settings background, a contact card in the detail pane).
 *
 * Column edges follow the views' current bounds and the layout direction; the rail column disappears
 * when the rail is collapsed. Call [onPanesLaidOut] after layout passes so a moved edge redraws.
 */
internal class RailSplitBackgroundDrawable(
    private val rail: View,
    private val detailPane: View,
    private val railColor: Int,
    private val listColor: Int,
    private val detailColor: Int,
) : Drawable() {

    private val paint = Paint()
    private var drawnEdges = Edges(-1, -1)

    // railEnd: the rail's end edge in root coordinates (after any cutout padding), -1 when hidden.
    private data class Edges(val railEnd: Int, val detailStart: Int)

    /** Redraws only when the rail's end edge or the detail pane's position changed. */
    fun onPanesLaidOut() {
        if (currentEdges() != drawnEdges) invalidateSelf()
    }

    private fun currentEdges(): Edges {
        val rtl = detailPane.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val railEnd = when {
            rail.visibility != View.VISIBLE || rail.width == 0 -> -1
            rtl -> rail.left
            else -> rail.right
        }
        return Edges(railEnd, if (rtl) detailPane.right else detailPane.left)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val edges = currentEdges()
        drawnEdges = edges
        val rtl = detailPane.layoutDirection == View.LAYOUT_DIRECTION_RTL

        paint.color = listColor
        canvas.drawRect(b, paint)

        // Detail column: from the detail pane's start edge to the window's end edge.
        paint.color = detailColor
        if (rtl) {
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), edges.detailStart.toFloat(), b.bottom.toFloat(), paint)
        } else {
            canvas.drawRect(edges.detailStart.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        }

        // Rail column: from the window's start edge (including a cutout's padding) to the rail's end.
        if (edges.railEnd < 0) return
        paint.color = railColor
        if (rtl) {
            canvas.drawRect(edges.railEnd.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        } else {
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), edges.railEnd.toFloat(), b.bottom.toFloat(), paint)
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
