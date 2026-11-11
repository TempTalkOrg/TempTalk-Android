package com.difft.android.base.ui.glass

import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme






@Composable
fun SystemBarGlass(
    edge: SystemBarEdge,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    insets: WindowInsets = if (edge == SystemBarEdge.TOP) WindowInsets.statusBars else WindowInsets.navigationBars,
    imeInsets: WindowInsets = WindowInsets.ime,
) {
    val density = LocalDensity.current
    val insetPx = if (edge == SystemBarEdge.TOP) insets.getTop(density) else insets.getBottom(density)
    if (!enabled || insetPx <= 0 || (edge == SystemBarEdge.BOTTOM && imeInsets.getBottom(density) > 0)) return

    val view = LocalView.current
    val local = LocalGlassBackdrop.current
    val binding = remember(view, local) { ComposeGlassBinding(view, local) }
    val placement = remember(binding) { SystemBarGlassPlacement(binding) }
    val painter = remember { SystemBarGlassPainter() }
    val colors = DifftTheme.colors
    val tint = (if (colors.isDark) colors.backgroundTertiary else colors.background).toArgb()
    val height = with(density) { insetPx.toDp() } + GlassTuning.SYSTEM_BAR_FADE_DP.dp
    DisposableEffect(binding) {
        binding.start()
        onDispose { binding.stop() }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned {
                placement.coordinates = it
                placement.updateRegion()
            }
            .drawWithCache {
                placement.bounds.set(0f, 0f, size.width, size.height)
                onDrawBehind {
                    binding.refresh()
                    binding.frame.intValue
                    placement.updateRegion()
                    clipRect {
                        drawIntoCanvas { canvas ->
                            painter.draw(canvas.nativeCanvas, placement.bounds, edge, insetPx.toFloat(), tint, placement.drawBackdrop)
                        }
                    }
                }
            },
    )
}

private class SystemBarGlassPlacement(private val binding: ComposeGlassBinding) {
    var coordinates: LayoutCoordinates? = null
    val bounds = RectF()
    private val geometry = ComposeGlassGeometry()
    private var positioned = false
    val drawBackdrop: (Canvas) -> Boolean = { canvas ->
        positioned && geometry.draw(canvas, binding.source)
    }

    fun updateRegion() {
        val coordinates = coordinates
        positioned = coordinates?.isAttached == true && !bounds.isEmpty
        if (!positioned) {
            binding.updateRegion(null)
            return
        }
        geometry.update(checkNotNull(coordinates), bounds)
        binding.updateRegion(geometry.windowBounds)
    }
}
