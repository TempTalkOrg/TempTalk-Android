package com.difft.android.chat.ui.popup
















data class SheetLiftInput(
    val baseHeightPx: Int,
    val navigationBarHeightPx: Int,
    val imeHeightPx: Int,
    val imeVisible: Boolean,
    val panelHeightPx: Int,
    val panelVisible: Boolean,
    val heldLiftPx: Int,
    val maxHeightPx: Int,
)


data class SheetGeometry(
    val heightPx: Int,
    val paddingBottomPx: Int,
    val peekHeightPx: Int,
    val heldLiftPx: Int,
)

object PopupSheetGeometry {

    



































    fun compute(input: SheetLiftInput): SheetGeometry {
        
        
        val panelLift =
            if (input.panelVisible && input.panelHeightPx > 0) {
                input.panelHeightPx + input.navigationBarHeightPx
            } else {
                0
            }
        val required = maxOf(
            if (input.imeVisible) input.imeHeightPx else 0,
            panelLift,
        ).coerceAtLeast(0)
        
        
        
        val lift = (if (required > 0) required else input.heldLiftPx).coerceAtLeast(0)
        val height = clampHeight(input.baseHeightPx + lift, input.maxHeightPx)
        val padding =
            if (input.imeVisible && !input.panelVisible) input.imeHeightPx
            else input.navigationBarHeightPx
        return SheetGeometry(
            heightPx = height,
            paddingBottomPx = padding,
            peekHeightPx = height,
            
            heldLiftPx = (height - input.baseHeightPx).coerceAtLeast(0),
        )
    }

    private fun clampHeight(height: Int, maxHeightPx: Int): Int =
        if (maxHeightPx > 0) height.coerceAtMost(maxHeightPx) else height
}
