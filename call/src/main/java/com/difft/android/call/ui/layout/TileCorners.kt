package com.difft.android.call.ui.layout

import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.difft.android.call.layout.Rect as LayoutRect







@Stable
internal class TileCornerState {
    private var rect: LayoutRect? by mutableStateOf(null)

    
    fun place(coordinates: LayoutCoordinates, density: Float) {
        val p = coordinates.positionInWindow()
        val s = coordinates.size
        rect = LayoutRect(p.x / density.toDouble(), p.y / density.toDouble(), s.width / density.toDouble(), s.height / density.toDouble())
    }

    
    fun radii(screen: CallScreenCorners?): CornerRadii? {
        screen ?: return null
        return rect?.let { cardCorners(it, screen) }
    }
}


internal fun cornerShape(radii: CornerRadii): Shape =
    AbsoluteRoundedCornerShape(radii.topLeft.dp, radii.topRight.dp, radii.bottomRight.dp, radii.bottomLeft.dp)






@Stable
internal data class TileCorners(val shape: Shape, private val state: TileCornerState, private val screen: CallScreenCorners?) {
    
    val concentric: Boolean get() = screen != null

    
    fun radii(): CornerRadii? = state.radii(screen)

    
    fun shapeNow(): Shape = radii()?.let(::cornerShape) ?: shape
}


internal fun Modifier.cardClip(corners: TileCorners): Modifier =
    if (corners.concentric) {
        graphicsLayer {
            shape = corners.shapeNow()
            clip = true
        }
    } else {
        clip(corners.shape)
    }





internal fun cornerShift(a: Double, b: Double, radius: Double?): Dp =
    radius?.let { (insetInCorner(a, b, it, CARD_RADIUS_DP) - a).dp } ?: 0.dp






internal fun Modifier.sideInset(end: Boolean, extra: () -> Dp): Modifier = layout { measurable, constraints ->
    val px = extra().roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.offset(horizontal = -px))
    val width = constraints.constrainWidth(placeable.width + px)
    layout(width, constraints.constrainHeight(placeable.height)) {
        placeable.placeRelative(if (end) width - placeable.width - px else px, 0)
    }
}






internal fun Modifier.pillInsets(start: () -> Dp, end: () -> Dp): Modifier = sideInset(end = false, start).sideInset(end = true, end)
