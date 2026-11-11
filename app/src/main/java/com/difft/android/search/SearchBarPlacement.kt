package com.difft.android.search







internal object SearchBarPlacement {
    const val KEYBOARD_GAP_DP = 10f
    const val RESTING_GAP_DP = 28f
    const val MIN_GAP_DP = 8f
    const val LIST_CLEARANCE_DP = 12f

    



    fun bottomMarginPx(keyboardUp: Boolean, navBottomPx: Int, density: Float): Int {
        val dp = if (keyboardUp) KEYBOARD_GAP_DP else maxOf(MIN_GAP_DP, RESTING_GAP_DP - navBottomPx / density)
        return (dp * density).toInt()
    }

    
    fun listBottomPaddingPx(listBottom: Int, barTop: Int, density: Float): Int =
        (listBottom - barTop + LIST_CLEARANCE_DP * density).toInt().coerceAtLeast(0)
}
