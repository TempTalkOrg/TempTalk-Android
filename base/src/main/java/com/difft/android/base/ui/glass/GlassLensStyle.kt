
package com.difft.android.base.ui.glass


data class GlassLensStyle(
    val heightDp: Float,
    val amountDp: Float,
    val blurRadiusDp: Float = 0f,
    val chromatic: Boolean = false,
    val vibrancy: Boolean = false,
) {
    companion object {
        val Panel = GlassLensStyle(24f, 24f, 8f, vibrancy = true)
        
        val Selection = GlassLensStyle(10f, 14f, chromatic = true)
    }
}
