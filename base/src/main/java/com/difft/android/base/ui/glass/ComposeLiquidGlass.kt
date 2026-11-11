package com.difft.android.base.ui.glass

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow






@Composable
fun Modifier.liquidGlassSurface(
    shape: Shape,
    style: GlassLensStyle,
    progress: () -> Float = { 1f },
    surface: DrawScope.() -> Unit = {},
    content: (DrawScope.() -> Unit)? = null,
    highlight: () -> Float = { 1f },
    shadow: () -> Float = { 1f },
    innerShadow: () -> Float = { 0f },
): Modifier {
    val view = LocalView.current
    val colors = DifftTheme.colors
    val fallback = if (colors.isDark) colors.backgroundTertiary else colors.background
    if (!GlassMode.isLiquid(view.context) || !GlassMode.libraryEffects) {
        return this.glassSurface(shape, fill = fallback)
    }
    val density = LocalDensity.current.density
    val local = LocalGlassBackdrop.current
    val binding = remember(view, local) { ComposeGlassBinding(view, local) }
    DisposableEffect(binding) {
        binding.start()
        onDispose { binding.stop() }
    }
    val page = remember(binding, density, style) {
        PageBackdrop(binding, GlassLensMath.samplePadding(density, style).toFloat())
    }
    page.base = fallback
    val backdrop: Backdrop = if (content != null) {
        rememberCombinedBackdrop(page, rememberCanvasBackdrop(content))
    } else {
        page
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            val press = GlassLensMath.progress(progress())
            if (style.vibrancy) vibrancy()
            if (style.blurRadiusDp > 0f) blur(style.blurRadiusDp.dp.toPx())
            lens(style.heightDp.dp.toPx() * press, style.amountDp.dp.toPx() * press,
                chromaticAberration = style.chromatic)
        },
        highlight = { Highlight.Default.copy(alpha = GlassLensMath.progress(highlight())) },
        shadow = { Shadow(alpha = GlassLensMath.progress(shadow())) },
        innerShadow = {
            val inner = GlassLensMath.progress(innerShadow())
            InnerShadow(radius = INNER_SHADOW_DP.dp * inner, alpha = inner)
        },
        onDrawSurface = surface,
    )
}

private const val INNER_SHADOW_DP = 8f





private class PageBackdrop(private val binding: ComposeGlassBinding, private val padding: Float) : Backdrop {
    override val isCoordinatesDependent: Boolean = true
    var base: Color = Color.Black
    private val geometry = ComposeGlassGeometry()
    private val bounds = RectF()

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        binding.refresh()
        
        binding.frame.intValue
        drawRect(base.copy(alpha = 1f), topLeft = Offset(-padding, -padding),
            size = Size(size.width + 2 * padding, size.height + 2 * padding))
        val placed = coordinates?.takeIf { it.isAttached } ?: return
        bounds.set(0f, 0f, placed.size.width.toFloat(), placed.size.height.toFloat())
        geometry.update(placed, bounds, padding)
        binding.updateRegion(geometry.windowBounds, sharp = true)
        val source = binding.source ?: return
        if (!geometry.invertible) return
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            
            if (!native.isHardwareAccelerated) return@drawIntoCanvas
            val save = native.save()
            native.concat(geometry.inverse)
            source.drawSharp(native, geometry.originX, geometry.originY, geometry.sampleBounds)
            native.restoreToCount(save)
        }
    }
}
