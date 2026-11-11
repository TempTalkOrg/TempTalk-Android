package com.difft.android.base.widget








object PatternScreenSpacing {

    private const val MIN_TOP_DP = 32
    const val MIN_HEADER_TO_GRID_DP = 48
    private const val MAX_HEADER_TO_GRID_DP = 80
    private const val MIN_GRID_TO_FOOTER_DP = 48

    
    data class Result(val showLogo: Boolean, val top: Int, val headerToGrid: Int)

    






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
