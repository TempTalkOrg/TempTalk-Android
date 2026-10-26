package com.difft.android.base.widget

/**
 * Vertical spacing of the pattern unlock screen, per the pattern-lock adaptation design
 * (Figma 11293:33828); the set-pattern screen reuses its minimum header-to-grid gap.
 * Every gap starts at its minimum; a quarter of the extra height widens the
 * header-to-grid gap, up to 80dp, and the rest is split evenly above the header and below the
 * grid, so the group sits centered. The logo (if any) is shown only when the whole group fits with
 * the grid at full size.
 */
object PatternScreenSpacing {

    private const val MIN_TOP_DP = 32
    const val MIN_HEADER_TO_GRID_DP = 48
    private const val MAX_HEADER_TO_GRID_DP = 80
    private const val MIN_GRID_TO_FOOTER_DP = 48

    /** All values in px; [top] is the gap above the logo block, or above the header without a logo. */
    data class Result(val showLogo: Boolean, val top: Int, val headerToGrid: Int)

    /**
     * @param available height between the screen's top content edge and its bottom edge
     * @param header height of the header (title and anything stacked with it)
     * @param footer height of the footer including its gap to the bottom edge
     * @param grid the grid's full size; a short window shrinks the grid, not the minimum gaps
     * @param logoBlock height the logo takes when shown, 0 for a screen without a logo
     */
    fun compute(density: Float, available: Int, header: Int, footer: Int, grid: Int, logoBlock: Int = 0): Result {
        val minTop = dp(MIN_TOP_DP, density)
        val minHeaderToGrid = dp(MIN_HEADER_TO_GRID_DP, density)
        val maxHeaderToGrid = dp(MAX_HEADER_TO_GRID_DP, density)
        val fixed = minTop + header + minHeaderToGrid + dp(MIN_GRID_TO_FOOTER_DP, density) + footer
        val showLogo = logoBlock > 0 && available >= fixed + logoBlock + grid
        val extra = (available - fixed - grid - (if (showLogo) logoBlock else 0)).coerceAtLeast(0)
        val headerToGrid = minHeaderToGrid + minOf(maxHeaderToGrid - minHeaderToGrid, extra / 4)
        val top = minTop + (extra - (headerToGrid - minHeaderToGrid)) / 2
        return Result(showLogo, top, headerToGrid)
    }

    fun dp(value: Int, density: Float): Int = (value * density).toInt()
}
