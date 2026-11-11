package com.difft.android.base.ui.glass

import android.graphics.RectF
import android.view.View
import android.view.ViewTreeObserver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import com.difft.android.base.ui.compose.floatingSurface





@Composable
fun Modifier.glassSurface(shape: Shape, fill: Color? = null): Modifier =
    floatingSurface(shape = shape, fill = fill, glass = true)


@Composable
internal fun Modifier.glassSurfaceFill(shape: Shape, surface: Color): Modifier {
    val view = LocalView.current
    val local = LocalGlassBackdrop.current
    val binding = remember(view, local) { ComposeGlassBinding(view, local) }
    val placement = remember { GlassPlacement() }
    DisposableEffect(binding) {
        binding.start()
        onDispose { binding.stop() }
    }
    return this
        .onGloballyPositioned {
            placement.coordinates = it
            placement.updateRegion(binding)
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this@drawWithCache)
            val path = Path().apply { addOutline(outline) }
            path.getBounds().let { placement.bounds.set(it.left, it.top, it.right, it.bottom) }
            onDrawBehind {
                
                binding.refresh()
                
                binding.frame.intValue
                val coordinates = placement.coordinates?.takeIf { it.isAttached }
                clipPath(path) {
                    var drawn = false
                    placement.updateRegion(binding)
                    if (coordinates != null) {
                        drawIntoCanvas { canvas ->
                            drawn = placement.geometry.draw(canvas.nativeCanvas, binding.source)
                        }
                    }
                    drawRect(surface.copy(alpha = if (drawn) surface.alpha * GlassTuning.TINT_ALPHA else surface.alpha))
                }
            }
        }
}

private class GlassPlacement {
    var coordinates: LayoutCoordinates? = null
    val bounds = RectF()
    val geometry = ComposeGlassGeometry()

    fun updateRegion(binding: ComposeGlassBinding) {
        val coordinates = coordinates
        if (coordinates?.isAttached != true || bounds.isEmpty) {
            binding.updateRegion(null)
            return
        }
        geometry.update(coordinates, bounds)
        binding.updateRegion(geometry.windowBounds)
    }
}


internal class ComposeGlassBinding(
    private val view: View,
    private val local: GlassBackdrop?,
) : View.OnAttachStateChangeListener {
    val frame = mutableIntStateOf(0)
    var source: GlassBackdrop? = null
        private set
    private var observer: ViewTreeObserver? = null
    private val region = RectF()
    private var hasRegion = false
    private var sharpRegion = false
    private val sourceChanged: () -> Unit = { frame.intValue++ }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        refresh()
        true
    }

    fun start() {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    fun stop() {
        view.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(view)
    }

    override fun onViewAttachedToWindow(v: View) {
        if (observer == null) {
            observer = view.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        }
        refresh()
    }

    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        bind(null)
    }

    fun refresh() = bind(if (view.isAttachedToWindow && view.isShown) local ?: view.findGlassBackdrop() else null)

    fun updateRegion(boundsInWindow: RectF?, sharp: Boolean = false) {
        val present = boundsInWindow != null && !boundsInWindow.isEmpty
        if (present == hasRegion && sharp == sharpRegion && (!present || region == boundsInWindow)) return
        hasRegion = present
        sharpRegion = sharp
        if (boundsInWindow != null) region.set(boundsInWindow)
        source?.updateRegion(sourceChanged, if (present) region else null, sharp)
    }

    private fun bind(next: GlassBackdrop?) {
        if (next === source) return
        source?.removeListener(sourceChanged)
        source = next
        next?.addListener(sourceChanged)
        if (hasRegion) next?.updateRegion(sourceChanged, region, sharpRegion)
        sourceChanged()
    }
}
