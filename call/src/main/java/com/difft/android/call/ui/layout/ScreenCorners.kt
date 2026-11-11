package com.difft.android.call.ui.layout

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.difft.android.call.layout.TileRole
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize










internal const val CARD_RADIUS_DP = 12.0


private const val CORNER_INSET_MARGIN_DP = 2.0


private const val RADIUS_EPS = 1e-6


private const val WINDOW_TOLERANCE_PX = 1





internal data class CornerRadii(val topLeft: Double, val topRight: Double, val bottomRight: Double, val bottomLeft: Double) {
    constructor(all: Double) : this(all, all, all, all)

    
    fun bottomStart(rtl: Boolean): Double = if (rtl) bottomRight else bottomLeft

    
    fun bottomEnd(rtl: Boolean): Double = if (rtl) bottomLeft else bottomRight

    
    fun topEnd(rtl: Boolean): Double = if (rtl) topLeft else topRight
}






internal data class CallScreenCorners(val window: LayoutSize, val radii: CornerRadii)








internal fun concentricCorners(card: LayoutRect, window: LayoutSize, screen: CornerRadii, own: Double): CornerRadii {
    val left = card.x
    val top = card.y
    val right = window.w - card.x - card.w
    val bottom = window.h - card.y - card.h
    val cap = min(card.w, card.h) / 2
    fun corner(radius: Double, dx: Double, dy: Double) = max(own, min(radius - max(dx, dy), cap))
    return CornerRadii(
        topLeft = corner(screen.topLeft, left, top),
        topRight = corner(screen.topRight, right, top),
        bottomRight = corner(screen.bottomRight, right, bottom),
        bottomLeft = corner(screen.bottomLeft, left, bottom),
    )
}








internal fun insetInCorner(a: Double, b: Double, radius: Double, own: Double, margin: Double = CORNER_INSET_MARGIN_DP): Double {
    if (radius <= own + RADIUS_EPS || b >= radius) return a
    val dy = radius - max(0.0, b)
    val need = radius - sqrt(max(0.0, radius * radius - dy * dy)) + margin
    return max(a, need)
}








internal fun roundedCornerRadii(px: List<Int?>, density: Double): CornerRadii? {
    if (px.size != 4 || density <= 0 || px.all { it == null || it <= 0 }) return null
    val dp = px.map { max(0, it ?: 0) / density }
    return CornerRadii(dp[0], dp[1], dp[2], dp[3])
}







internal fun isWholeScreen(current: IntRect, maximum: IntRect, multiWindow: Boolean, view: IntSize): Boolean =
    !multiWindow && current == maximum &&
        abs(current.width - view.width) <= WINDOW_TOLERANCE_PX && abs(current.height - view.height) <= WINDOW_TOLERANCE_PX


internal fun callScreenCorners(radii: CornerRadii?, wholeScreen: Boolean, window: LayoutSize): CallScreenCorners? =
    if (wholeScreen && radii != null) CallScreenCorners(window, radii) else null






internal fun takesScreenCorners(role: TileRole, pipFocus: Boolean, hidden: Boolean): Boolean =
    !pipFocus && !hidden && (role == TileRole.GRID || role == TileRole.PIP)


internal fun cardCorners(card: LayoutRect, screen: CallScreenCorners?, own: Double = CARD_RADIUS_DP): CornerRadii? {
    screen ?: return null
    val c = concentricCorners(card, screen.window, screen.radii, own)
    val rounder = listOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft).any { it > own + RADIUS_EPS }
    return c.takeIf { rounder }
}
