package com.difft.android.chat.ui.messageaction

import kotlin.math.max
import kotlin.math.min








internal data class PopupRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2

    
    fun overlapsVertically(other: PopupRect): Boolean = top < other.bottom && other.top < bottom

    fun verticalOverlap(other: PopupRect): Int = max(0, min(bottom, other.bottom) - max(top, other.top))

    fun union(other: PopupRect): PopupRect =
        PopupRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    
    fun clipVerticallyTo(bounds: PopupRect): PopupRect? =
        PopupRect(left, max(top, bounds.top), right, min(bottom, bounds.bottom)).takeIf { it.top < it.bottom }
}






internal data class SelectionAnchorInput(
    val bubble: PopupRect,
    val startRow: PopupRect? = null,
    val endRow: PopupRect? = null,
)

internal data class PopupMetrics(val edgePaddingPx: Int, val arrowGapPx: Int, val arrowMinInsetPx: Int)

internal enum class AnchorMode {
    BUBBLE,
    SAME_ROW,
    START_ROW_ONLY,
    END_ROW_ONLY,
    WHOLE_SELECTION_BELOW,
    WHOLE_SELECTION_ABOVE,
    FIRST_ROW,
}

internal enum class PlacementMode { BELOW, ABOVE, PINNED_TOP, PINNED_BOTTOM }

internal data class ResolvedAnchor(val rect: PopupRect, val mode: AnchorMode)


internal data class YPlacement(val y: Int, val isBelow: Boolean, val mode: PlacementMode)

internal data class PopupPlacement(
    val x: Int,
    val y: Int,
    val isBelow: Boolean,
    val arrowOffsetXPx: Int,
    val anchor: ResolvedAnchor,
    val placementMode: PlacementMode,
)


internal data class VerticalBand(val minY: Int, val maxY: Int, val popupHeight: Int, val gap: Int) {
    
    fun yBelow(anchor: PopupRect): Int = (anchor.bottom + gap).coerceAtLeast(minY)

    fun yAbove(anchor: PopupRect): Int = (anchor.top - popupHeight - gap).coerceAtMost(maxY - popupHeight)

    fun fitsBelow(anchor: PopupRect): Boolean = yBelow(anchor) + popupHeight <= maxY

    fun fitsAbove(anchor: PopupRect): Boolean = yAbove(anchor) >= minY

    fun pinnedTopRect(): PopupRect = PopupRect(0, minY, 0, minY + popupHeight)

    fun pinnedBottomRect(): PopupRect = PopupRect(0, maxY - popupHeight, 0, maxY)
}















internal object MessageActionPopupPositioner {

    fun place(
        input: SelectionAnchorInput,
        content: PopupRect,
        popupWidth: Int,
        popupHeight: Int,
        isOutgoing: Boolean,
        metrics: PopupMetrics,
    ): PopupPlacement {
        val band = VerticalBand(
            minY = content.top + metrics.edgePaddingPx,
            maxY = content.bottom - metrics.edgePaddingPx,
            popupHeight = popupHeight,
            gap = metrics.arrowGapPx,
        )
        val anchor = resolveAnchor(clipRows(input, content), band)
        val yPlacement = placeY(anchor.rect, input.bubble, band)
        val x = placeX(input.bubble, content, popupWidth, metrics.edgePaddingPx, isOutgoing)
        val arrow = arrowOffsetX(anchor.rect.centerX, x, popupWidth, metrics.arrowMinInsetPx)
        return PopupPlacement(x, yPlacement.y, yPlacement.isBelow, arrow, anchor, yPlacement.mode)
    }

    




    internal fun clipRows(input: SelectionAnchorInput, content: PopupRect): SelectionAnchorInput =
        input.copy(
            startRow = input.startRow?.clipVerticallyTo(content),
            endRow = input.endRow?.clipVerticallyTo(content),
        )

    internal fun resolveAnchor(input: SelectionAnchorInput, band: VerticalBand): ResolvedAnchor {
        val start = input.startRow
        val end = input.endRow
        return when {
            start != null && end != null -> when {
                start.overlapsVertically(end) -> ResolvedAnchor(start.union(end), AnchorMode.SAME_ROW)
                else -> resolveMultiRow(start, end, band)
            }
            start != null -> ResolvedAnchor(start, AnchorMode.START_ROW_ONLY)
            end != null -> ResolvedAnchor(end, AnchorMode.END_ROW_ONLY)
            else -> ResolvedAnchor(input.bubble, AnchorMode.BUBBLE)
        }
    }

    private fun resolveMultiRow(a: PopupRect, b: PopupRect, band: VerticalBand): ResolvedAnchor {
        
        val (top, bottom) = if (a.top <= b.top) a to b else b to a
        val belowWhole = PopupRect(bottom.left, top.top, bottom.right, bottom.bottom)
        if (band.fitsBelow(belowWhole)) return ResolvedAnchor(belowWhole, AnchorMode.WHOLE_SELECTION_BELOW)
        val aboveWhole = PopupRect(top.left, top.top, top.right, bottom.bottom)
        if (band.fitsAbove(aboveWhole)) return ResolvedAnchor(aboveWhole, AnchorMode.WHOLE_SELECTION_ABOVE)
        return ResolvedAnchor(top, AnchorMode.FIRST_ROW)
    }

    internal fun placeY(anchor: PopupRect, bubble: PopupRect, band: VerticalBand): YPlacement = when {
        band.fitsBelow(anchor) -> YPlacement(band.yBelow(anchor), isBelow = true, PlacementMode.BELOW)
        band.fitsAbove(anchor) -> YPlacement(band.yAbove(anchor), isBelow = false, PlacementMode.ABOVE)
        pinToTop(anchor, bubble, band) -> YPlacement(band.minY, isBelow = false, PlacementMode.PINNED_TOP)
        
        else -> YPlacement(
            (band.maxY - band.popupHeight).coerceAtLeast(band.minY),
            isBelow = true,
            PlacementMode.PINNED_BOTTOM,
        )
    }

    



    private fun pinToTop(anchor: PopupRect, bubble: PopupRect, band: VerticalBand): Boolean {
        val top = band.pinnedTopRect()
        val bottom = band.pinnedBottomRect()
        val bubbleDelta = top.verticalOverlap(bubble) - bottom.verticalOverlap(bubble)
        if (bubbleDelta != 0) return bubbleDelta < 0
        val anchorDelta = top.verticalOverlap(anchor) - bottom.verticalOverlap(anchor)
        if (anchorDelta != 0) return anchorDelta < 0
        return bubble.top - band.minY > band.maxY - bubble.bottom
    }

    






    internal fun placeX(
        bubble: PopupRect,
        content: PopupRect,
        popupWidth: Int,
        edgePaddingPx: Int,
        isOutgoing: Boolean,
    ): Int {
        val minX = content.left + edgePaddingPx
        val maxX = content.right - edgePaddingPx - popupWidth
        if (maxX < minX) return minX
        val aligned = if (isOutgoing) bubble.right - popupWidth else bubble.left
        return aligned.coerceIn(minX, maxX)
    }

    internal fun arrowOffsetX(anchorCenterX: Int, popupX: Int, popupWidth: Int, minInsetPx: Int): Int =
        (anchorCenterX - popupX).coerceIn(minInsetPx, (popupWidth - minInsetPx).coerceAtLeast(minInsetPx))
}
