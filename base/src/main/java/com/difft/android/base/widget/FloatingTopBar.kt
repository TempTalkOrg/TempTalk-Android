package com.difft.android.base.widget

import com.difft.android.base.utils.avoidCornerCutoutInRow
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnLayout
import kotlin.math.roundToInt

















fun ImageView.floatAsTopBarButton(leading: Boolean, fillColor: Int? = null, glass: Boolean = false) {
    val density = resources.displayMetrics.density
    val pad = (FLOATING_ICON_PADDING_DP * density).roundToInt()
    val gap = if (leading) (TITLE_GAP_DP * density).roundToInt() else 0
    layoutParams?.let { params ->
        params.width = (FLOATING_BUTTON_DP * density).roundToInt() + gap
        setEdgeMargin(params, leading)
        layoutParams = params
    }
    val top = paddingTop
    val bottom = paddingBottom
    setPaddingRelative(pad, top, pad + gap, bottom)
    applyFloatingSurface(maxCircleDiameterDp = FLOATING_BUTTON_DP, fillColor = fillColor, pressable = true, elevationDp = TOP_BAR_ELEVATION_DP, glass = glass)
    avoidCornerCutoutInRow()
    if (gap > 0) {
        val surface = background
        val ripple = foreground
        
        val applyGap = {
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            background = InsetDrawable(surface, if (rtl) gap else 0, 0, if (rtl) 0 else gap, 0)
            foreground = ripple?.let { InsetDrawable(it, if (rtl) gap else 0, 0, if (rtl) 0 else gap, 0) }
            
            setPaddingRelative(pad, top, pad + gap, bottom)
        }
        if (isLayoutDirectionResolved) applyGap() else doOnLayout { applyGap() }
    }
}






fun TextView.floatAsTopBarTextButton(leading: Boolean, glass: Boolean = false) {
    val density = resources.displayMetrics.density
    val height = (FLOATING_BUTTON_DP * density).roundToInt()
    val pad = (FLOATING_TEXT_PADDING_DP * density).roundToInt()
    minimumHeight = height
    minHeight = height
    minimumWidth = height
    minWidth = height
    layoutParams?.let { params ->
        params.height = height
        setEdgeMargin(params, leading)
        layoutParams = params
    }
    gravity = Gravity.CENTER
    setPaddingRelative(pad, 0, pad, 0)
    applyFloatingSurface(cornerRadiusPx = FLOATING_BUTTON_DP / 2 * density, pressable = true, elevationDp = TOP_BAR_ELEVATION_DP, glass = glass)
    avoidCornerCutoutInRow()
}

private fun View.setEdgeMargin(params: ViewGroup.LayoutParams, leading: Boolean) {
    val margins = params as? ViewGroup.MarginLayoutParams ?: return
    val edge = (FLOATING_EDGE_DP * resources.displayMetrics.density).roundToInt()
    if (leading) margins.marginStart = edge else margins.marginEnd = edge
}

private const val FLOATING_BUTTON_DP = 44f
private const val FLOATING_ICON_PADDING_DP = 10f
private const val FLOATING_TEXT_PADDING_DP = 16f
private const val FLOATING_EDGE_DP = 16f

private const val TITLE_GAP_DP = 12f

private const val TOP_BAR_ELEVATION_DP = 2f
