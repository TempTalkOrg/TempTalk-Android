package com.difft.android.call.ui.tabletop

import com.difft.android.call.LCallUiConstants
import com.difft.android.call.layout.LayoutPlan
import com.difft.android.call.layout.Posture
import com.difft.android.call.ui.actionbar.ActionBarPlan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize


internal object CallTabletopSpec {
    const val GAP_DP = LCallUiConstants.CHROME_CONTENT_GAP_DP.toFloat()

    
    const val CONSOLE_HEIGHT_DP = 232f

    
    const val CONSOLE_WIDTH_DP = 368f

    



    val CONSOLE_SIZE = LayoutSize(CONSOLE_WIDTH_DP.toDouble(), CONSOLE_HEIGHT_DP.toDouble())
}

internal enum class CallFoldState { FLAT, HALF_OPENED }


internal data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int)





internal data class CallFold(
    val state: CallFoldState,
    val horizontal: Boolean,
    val occluding: Boolean,
    val bounds: PxRect,
    val byHinge: Boolean = false,
)


internal data class CallFoldReport(val folds: List<CallFold>)


internal object HingeFallback {
    
    const val HALF_OPEN_MIN_DEG = 30f
    const val HALF_OPEN_MAX_DEG = 150f

    



    const val HYSTERESIS_DEG = 10f
}










internal fun CallFoldReport.withHingeFallback(hingeDeg: Float?, heldByHinge: Boolean = false): CallFoldReport {
    if (hingeDeg == null) return this
    val slack = if (heldByHinge) HingeFallback.HYSTERESIS_DEG else 0f
    if (hingeDeg < HingeFallback.HALF_OPEN_MIN_DEG - slack || hingeDeg > HingeFallback.HALF_OPEN_MAX_DEG + slack) return this
    if (folds.none { !it.horizontal && it.state == CallFoldState.FLAT }) return this
    return CallFoldReport(
        folds.map { if (!it.horizontal && it.state == CallFoldState.FLAT) it.copy(state = CallFoldState.HALF_OPENED, byHinge = true) else it },
    )
}






data class TabletopGeometry(
    val bandTopPx: Int,
    val bandBottomPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val horizontal: Boolean = true,
    
    val controls: LayoutRect? = null,
) {
    val bandTopDp: Float get() = bandTopPx / density
    val bandBottomDp: Float get() = bandBottomPx / density
    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density

    val dockTopDp: Float get() = bandBottomDp + CallTabletopSpec.GAP_DP
    val dockHeightDp: Float get() = heightDp - dockTopDp

    
    val dockHeightPx: Int get() = heightPx - (dockTopDp * density).roundToInt()

    



    fun consoleDp(bottomInsetDp: Float = 0f): LayoutRect = controls ?: LayoutRect(
        ((widthDp - CallTabletopSpec.CONSOLE_WIDTH_DP) / 2f).coerceAtLeast(0f).toDouble(),
        (heightDp - bottomInsetDp - CallTabletopSpec.CONSOLE_HEIGHT_DP).toDouble(),
        min(widthDp, CallTabletopSpec.CONSOLE_WIDTH_DP).toDouble(),
        CallTabletopSpec.CONSOLE_HEIGHT_DP.toDouble(),
    )
}

internal sealed interface TabletopResolution {
    
    data object NoFold : TabletopResolution

    
    data class Flat(val widthPx: Int, val heightPx: Int) : TabletopResolution

    
    data class Accepted(val geometry: TabletopGeometry) : TabletopResolution
}


private class LocalFold(val fold: CallFold, val rect: PxRect)





internal fun resolveTabletop(
    report: CallFoldReport?,
    rootLeftPx: Int,
    rootTopPx: Int,
    rootWidthPx: Int,
    rootHeightPx: Int,
    density: Float,
): TabletopResolution {
    if (report == null || report.folds.isEmpty() || rootWidthPx <= 0 || rootHeightPx <= 0 || density <= 0f) {
        return TabletopResolution.NoFold
    }
    val crossing = report.folds.mapNotNull { localFold(it, rootLeftPx, rootTopPx, rootWidthPx, rootHeightPx) }
    if (crossing.isEmpty()) return TabletopResolution.NoFold
    val fold = crossing.firstOrNull { it.fold.state == CallFoldState.HALF_OPENED }
        ?: return TabletopResolution.Flat(rootWidthPx, rootHeightPx)
    val r = fold.rect
    return TabletopResolution.Accepted(
        TabletopGeometry(
            bandTopPx = if (fold.fold.horizontal) r.top else r.left,
            bandBottomPx = if (fold.fold.horizontal) r.bottom else r.right,
            widthPx = rootWidthPx,
            heightPx = rootHeightPx,
            density = density,
            horizontal = fold.fold.horizontal,
        ),
    )
}


private fun localFold(fold: CallFold, leftPx: Int, topPx: Int, widthPx: Int, heightPx: Int): LocalFold? {
    val b = fold.bounds
    val r = PxRect(b.left - leftPx, b.top - topPx, b.right - leftPx, b.bottom - topPx)
    return if (fold.horizontal) {
        val overlap = min(r.right, widthPx) - max(r.left, 0)
        if (r.top > 0 && r.bottom < heightPx && overlap > 0) LocalFold(fold, r) else null
    } else {
        val overlap = min(r.bottom, heightPx) - max(r.top, 0)
        if (r.left > 0 && r.right < widthPx && overlap > 0) LocalFold(fold, r) else null
    }
}


internal fun TabletopGeometry.foldDp(): LayoutRect = if (horizontal) {
    LayoutRect(0.0, bandTopDp.toDouble(), widthDp.toDouble(), (bandBottomDp - bandTopDp).toDouble())
} else {
    LayoutRect(bandTopDp.toDouble(), 0.0, (bandBottomDp - bandTopDp).toDouble(), heightDp.toDouble())
}






internal fun planTabletop(plan: LayoutPlan?, fold: LayoutRect?, window: LayoutSize, density: Float): TabletopGeometry? {
    if (plan?.posture != Posture.TABLETOP || fold == null || density <= 0f) return null
    val dock = plan.dock ?: return null
    return TabletopGeometry(
        bandTopPx = (fold.y * density).roundToInt(),
        bandBottomPx = ((dock.y - CallTabletopSpec.GAP_DP) * density).roundToInt(),
        widthPx = (window.w * density).roundToInt(),
        heightPx = (window.h * density).roundToInt(),
        density = density,
        controls = plan.controls,
    )
}





internal fun bottomReserveDp(posture: Posture, plan: ActionBarPlan, navBottomDp: Float): Int =
    when (posture) {
        Posture.TABLETOP -> navBottomDp.roundToInt()
        Posture.BOOK -> plan.bottomMarginDp + plan.buttonSizeDp
        Posture.FLAT -> plan.chromeBottomReserveDp
    }
