package com.difft.android.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.glass.glassSurfaceFill
import com.difft.android.base.ui.theme.DifftTheme




@Composable
fun Modifier.floatingPill(glass: Boolean = false): Modifier = floatingSurface(cornerRadius = null, glass = glass)












@Composable
fun Modifier.floatingSurface(cornerRadius: Dp?, glass: Boolean = false): Modifier =
    floatingSurface(if (cornerRadius == null) PillShape else RoundedCornerShape(cornerRadius), glass = glass)





@Composable
fun Modifier.floatingSurface(
    shape: Shape,
    fill: Color? = null,
    outlined: Boolean = true,
    glass: Boolean = false,
): Modifier {
    val colors = DifftTheme.colors
    val dark = colors.isDark
    val surface = fill ?: if (dark) colors.backgroundTertiary else colors.background
    val surfaceFill = if (glass) Modifier.glassSurfaceFill(shape, surface) else Modifier.background(surface, shape)
    if (dark) {
        return this
            .then(surfaceFill)
            .innerRims(shape)
            .clip(shape)
    }
    val edge = Brush.verticalGradient(
        0f to HighlightLight,
        0.5f to colors.line,
        1f to colors.line,
    )
    return this
        .outsideShadows(shape)
        .then(surfaceFill)
        .then(if (outlined) Modifier.border(0.5.dp, edge, shape) else Modifier)
        .clip(shape)
}





private fun Modifier.innerRims(shape: Shape): Modifier = drawWithCache {
    val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    fun rim(dy: Float) = Path().apply {
        op(outline, Path().apply { addPath(outline, Offset(0f, dy)) }, PathOperation.Difference)
    }
    val top = rim(TOP_RIM.toPx())
    val bottom = rim(-BOTTOM_RIM.toPx())
    onDrawBehind {
        drawPath(top, TopRimDark)
        drawPath(bottom, BottomRimDark)
    }
}

private val PillShape = RoundedCornerShape(50)








@Composable
fun Modifier.floatingSurfaceShadow(shape: Shape): Modifier = if (DifftTheme.colors.isDark) this else outsideShadows(shape)

private fun Modifier.outsideShadows(shape: Shape): Modifier = drawWithCache {
    val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val paints = SHADOWS.map { layer ->
        val color = Color.Black.copy(alpha = layer.alpha).toArgb()
        Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = color
                setShadowLayer(layer.blur.toPx(), 0f, layer.dy.toPx(), color)
            }
        }
    }
    onDrawBehind {
        clipPath(outline, clipOp = ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                for (index in paints.indices) {
                    canvas.drawPath(outline, paints[index])
                }
            }
        }
    }
}

private class ShadowLayer(val blur: Dp, val dy: Dp, val alpha: Float)

private val FLOATING_SHADOW_BLUR = 2.67.dp
private val FLOATING_SHADOW_DY = 0.85.dp
private const val FLOATING_SHADOW_ALPHA = 0.125f



private val SHADOWS = listOf(
    ShadowLayer(blur = FLOATING_SHADOW_BLUR, dy = FLOATING_SHADOW_DY, alpha = FLOATING_SHADOW_ALPHA),
)
internal val HighlightLight = Color.White


private val TOP_RIM = 1.dp
private val BOTTOM_RIM = 0.67.dp
private val TopRimDark = Color.White.copy(alpha = 0.16f)
private val BottomRimDark = Color.White.copy(alpha = 0.08f)
