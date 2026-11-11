package com.difft.android.base.ui.sidechrome









object SideChromeEligibility {

    
    const val MAX_HEIGHT_DP = 700

    
    const val MIN_SHORT_SIDE_DP = 400

    
    const val MIN_CONTENT_WIDTH_DP = 320

    
    const val BAR_RESERVE_DP = 84

    data class Inputs(
        val hasHinge: Boolean,
        val onDefaultDisplay: Boolean,
        val inMultiWindow: Boolean,
        
        val screenWidthDp: Int,
        val screenHeightDp: Int,
        
        val contentWidthDp: Float,
        
        val dualPaneMinWidthDp: Float = 0f,
    )

    enum class Reason { ELIGIBLE, NO_HINGE, EXTERNAL_DISPLAY, MULTI_WINDOW, TALL, NARROW, CONTENT, DUAL_PANE }

    




    fun shortScreen(hasHinge: Boolean, onDefaultDisplay: Boolean, screenWidthDp: Int, screenHeightDp: Int): Boolean {
        val longSide = maxOf(screenWidthDp, screenHeightDp)
        val shortSide = minOf(screenWidthDp, screenHeightDp)
        val landscape = Inputs(
            hasHinge = hasHinge,
            onDefaultDisplay = onDefaultDisplay,
            inMultiWindow = false,
            screenWidthDp = longSide,
            screenHeightDp = shortSide,
            contentWidthDp = longSide.toFloat(),
        )
        return evaluate(landscape) == Reason.ELIGIBLE
    }

    fun evaluate(inputs: Inputs): Reason {
        val candidate = inputs.contentWidthDp - BAR_RESERVE_DP
        return when {
            !inputs.hasHinge -> Reason.NO_HINGE
            !inputs.onDefaultDisplay -> Reason.EXTERNAL_DISPLAY
            inputs.inMultiWindow -> Reason.MULTI_WINDOW
            inputs.screenHeightDp >= MAX_HEIGHT_DP -> Reason.TALL
            minOf(inputs.screenWidthDp, inputs.screenHeightDp) < MIN_SHORT_SIDE_DP -> Reason.NARROW
            candidate < MIN_CONTENT_WIDTH_DP -> Reason.CONTENT
            inputs.dualPaneMinWidthDp > 0f && candidate < inputs.dualPaneMinWidthDp -> Reason.DUAL_PANE
            else -> Reason.ELIGIBLE
        }
    }
}
