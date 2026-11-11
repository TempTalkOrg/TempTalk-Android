package com.difft.android.base.ui.glass

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.topchrome.TopChromeGlassDrawable


@Composable
fun Modifier.glassBand(): Modifier {
    val view = LocalView.current
    val local = LocalGlassBackdrop.current
    val binding = remember(view, local) { ComposeGlassBinding(view, local) }
    val placement = remember(binding) { BandPlacement(binding) }
    val colors = DifftTheme.colors
    val color = colors.background
    val alpha = if (colors.isDark) {
        TopChromeGlassDrawable.BAND_ALPHA_DARK
    } else {
        TopChromeGlassDrawable.BAND_ALPHA_LIGHT
    }
    DisposableEffect(binding) {
        binding.start()
        onDispose { binding.stop() }
    }
    return this
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
                    var drawn = false
                    drawIntoCanvas { canvas ->
                        if (placement.positioned) {
                            drawn = placement.geometry.draw(canvas.nativeCanvas, binding.source)
                        }
                    }
                    drawRect(color.copy(alpha = if (drawn) alpha else 1f))
                }
            }
        }
}

private class BandPlacement(private val binding: ComposeGlassBinding) {
    var coordinates: LayoutCoordinates? = null
    val bounds = RectF()
    val geometry = ComposeGlassGeometry()
    var positioned = false
        private set

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
