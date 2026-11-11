package com.difft.android.base.ui.glass

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.graphics.Insets
import androidx.core.widget.NestedScrollView
import com.difft.android.base.R
import kotlin.math.ceil

private const val MAX_SHARP_REGIONS = 3


@RequiresApi(31)
internal class ViewGlassBackdrop(
    private val viewport: View,
    private val content: View,
    private val drawBackground: ((Canvas) -> Unit)?,
    private val captureInsets: () -> Insets = { Insets.NONE },
) : GlassBackdrop, View.OnAttachStateChangeListener {
    private class Demand(val listener: () -> Unit) {
        var bounds: GlassSharpRegion? = null
        var sharp = false
        var pending = false
    }
    private class BlurRegion(val bounds: GlassSharpRegion, effect: RenderEffect) {
        var effect: RenderEffect = effect
            private set
        val node = RenderNode("GlassBackdropRegion").apply { setRenderEffect(effect); setClipToBounds(true) }
        var capture: RenderNode? = null
        var recorded = false
        fun attachEffect(effect: RenderEffect) {
            this.effect = effect
            node.setRenderEffect(effect)
        }
        fun close() { node.discardDisplayList(); capture?.discardDisplayList() }
    }
    private class SharpRegion(val node: RenderNode, var dirty: Boolean = false)

    private val demands = linkedMapOf<() -> Unit, Demand>()
    private val regions = arrayListOf<BlurRegion>()
    private val sharpNodes = LinkedHashMap<GlassSharpRegion, SharpRegion>(MAX_SHARP_REGIONS, 0.75f, true)
    private val callbacks = arrayListOf<() -> Unit>()
    private var notifying = false
    private val viewportLocation = IntArray(2)
    private val contentLocation = IntArray(2)
    private val query = RectF()
    private val clip = Rect()
    private var observer: ViewTreeObserver? = null
    private var configuration: Configuration? = null
    private var blurEffect: RenderEffect? = null
    private var activeTracker: GlassRegionDirtyTracker? = null
    private var sharedCapture: RenderNode? = null
    private var sharedBounds: GlassSharpRegion? = null
    private var fullCaptureMode = false
    private var extent: GlassSharpRegion? = null
    private var dirty = true
    private var planDirty = true
    private var ready = false
    private var closed = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastContentX = Int.MIN_VALUE
    private var lastContentY = Int.MIN_VALUE
    private var lastScrollX = 0
    private var lastScrollY = 0
    private var lastCaptureInsets = Insets.NONE
    private val preDraw = ViewTreeObserver.OnPreDrawListener { captureIfNeeded(); true }

    init {
        viewport.addOnAttachStateChangeListener(this)
        if (viewport.isAttachedToWindow) attach()
    }

    override fun onViewAttachedToWindow(v: View) = attach()

    private fun attach() {
        if (closed || observer != null) return
        observer = viewport.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        invalidate()
    }

    override fun onViewDetachedFromWindow(v: View) {
        removeObservers()
        releaseNodes()
        dirty = true
    }

    private fun removeObservers() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
    }

    private fun eligible() = !closed && viewport.isHardwareAccelerated && viewport.isAttachedToWindow &&
        content.isAttachedToWindow && viewport.isShown && content.isShown

    private fun captureIfNeeded() {
        if (!eligible() || demands.values.none { it.bounds != null } || viewport.width <= 0 || viewport.height <= 0) {
            dirty = true
            val wasReady = ready
            releaseNodes()
            if (wasReady) {
                demands.values.forEach { queue(it.listener) }
                dispatchCallbacks()
            }
            return
        }
        
        
        (content as? NestedScrollView)?.computeScroll()
        viewport.getLocationInWindow(viewportLocation)
        content.getLocationInWindow(contentLocation)
        val currentConfiguration = viewport.resources.configuration
        val outsets = captureInsets()
        val configurationChanged = configuration != currentConfiguration
        val geometryChanged = viewport.width != lastWidth || viewport.height != lastHeight ||
            viewportLocation[0] != lastX || viewportLocation[1] != lastY ||
            contentLocation[0] != lastContentX || contentLocation[1] != lastContentY || outsets != lastCaptureInsets
        val scrollChanged = content.scrollX != lastScrollX || content.scrollY != lastScrollY
        val tracker = (content as? GlassDamageSource)?.glassDamage
        if (activeTracker !== tracker) {
            activeTracker?.release()
            tracker?.acquire()
            activeTracker = tracker
        }
        var allDirty = dirty || geometryChanged || scrollChanged || configurationChanged || content.isLayoutRequested ||
            (tracker == null && content.isDirty)
        
        
        ready = false
        dirty = true
        try {
            tracker?.beginFrame()
            val animatedCapture = tracker?.requiresFullCapture == true
            
            
            val scrollContainer = content is NestedScrollView
            val needsFullCapture = scrollContainer || animatedCapture
            if (needsFullCapture != fullCaptureMode) allDirty = true
            fullCaptureMode = needsFullCapture
            lastWidth = viewport.width
            lastHeight = viewport.height
            lastX = viewportLocation[0]
            lastY = viewportLocation[1]
            lastContentX = contentLocation[0]
            lastContentY = contentLocation[1]
            lastScrollX = content.scrollX
            lastScrollY = content.scrollY
            lastCaptureInsets = outsets
            if (geometryChanged || extent == null) extent = GlassSharpRegion(lastX - outsets.left, lastY - outsets.top,
                lastX + lastWidth + outsets.right, lastY + lastHeight + outsets.bottom)
            if (configurationChanged) {
                configuration = Configuration(currentConfiguration)
                blurEffect = null
            }
            
            
            
            
            sharedCapture = if (fullCaptureMode) {
                val source = checkNotNull(extent)
                val bounds = if (scrollContainer || tracker?.overscrollActive == true) source else {
                    val damage = checkNotNull(tracker)
                    damage.captureBounds(query)
                    checkNotNull(GlassSharpMath.region(
                        minOf(source.left.toFloat(), query.left), minOf(source.top.toFloat(), query.top),
                        maxOf(source.right.toFloat(), query.right), maxOf(source.bottom.toFloat(), query.bottom),
                        Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
                }
                if (allDirty || animatedCapture || sharedCapture == null || sharedBounds != bounds) {
                    val captured = recordContent(bounds, bounds.width, bounds.height, 1f, "GlassBackdropSharedCommands", clipToBounds = false)
                    sharedBounds = bounds
                    captured
                } else sharedCapture
            } else { sharedBounds = null; null }
            
            for ((bounds, cached) in sharpNodes) {
                if (allDirty || affected(tracker, bounds)) cached.dirty = true
            }
            if (planDirty || geometryChanged || configurationChanged) rebuildRegions()
            for (region in regions) {
                if (allDirty || animatedCapture || !region.recorded || affected(tracker, region.bounds)) {
                    recordBlur(region)
                    for (demand in demands.values) {
                        val bounds = demand.bounds
                        if (!demand.sharp && bounds != null && GlassRegionMath.intersects(bounds, region.bounds)) queue(demand.listener)
                    }
                }
            }
            for (demand in demands.values) {
                val bounds = demand.bounds ?: continue
                if (demand.pending || (demand.sharp && (allDirty || affected(tracker, bounds)))) queue(demand.listener)
                demand.pending = false
            }
            ready = true
            dirty = false
        } finally {
            tracker?.endFrame()
        }
        dispatchCallbacks()
    }

    private fun affected(tracker: GlassRegionDirtyTracker?, bounds: GlassSharpRegion): Boolean {
        if (tracker == null) return false
        query.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        return tracker.isDirty(query)
    }

    private fun rebuildRegions() {
        val bounds = checkNotNull(extent)
        val requests = demands.values.mapNotNull { if (it.sharp) null else it.bounds }
        val planned = GlassRegionMath.merge(requests, bounds, GlassRegionMath.blurMargin(viewport.resources.displayMetrics.density))
        if (planned.isNotEmpty() && blurEffect == null) {
            val saturation = RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(GlassTuning.SATURATION) }),
            )
            val radius = GlassMath.blurRadiusPx(viewport.resources.displayMetrics.density)
            blurEffect = RenderEffect.createBlurEffect(radius, radius, saturation, Shader.TileMode.CLAMP)
        }
        val next = ArrayList<BlurRegion>(planned.size)
        for (region in planned) {
            val cached = regions.firstOrNull { it.bounds == region }
            next += cached?.also { it.attachEffect(checkNotNull(blurEffect)) }
                ?: BlurRegion(region, checkNotNull(blurEffect))
        }
        
        
        regions.clear()
        regions.addAll(next)
        planDirty = false
    }

    private fun recordBlur(region: BlurRegion) {
        region.recorded = false
        region.attachEffect(region.effect)
        val width = ceil(region.bounds.width / GlassTuning.DOWNSAMPLE).toInt()
        val height = ceil(region.bounds.height / GlassTuning.DOWNSAMPLE).toInt()
        
        
        val shared = sharedCapture
        val next = if (shared == null) {
            recordContent(region.bounds, width, height, GlassTuning.DOWNSAMPLE, "GlassBackdropCapture")
        } else null
        var adopted = false
        try {
            region.node.setPosition(0, 0, width, height)
            val canvas = region.node.beginRecording(width, height)
            try {
                if (shared != null) drawShared(canvas, region.bounds, GlassTuning.DOWNSAMPLE, shared)
                else canvas.drawRenderNode(checkNotNull(next))
            } finally { region.node.endRecording() }
            region.capture?.discardDisplayList()
            region.capture = next
            region.recorded = true
            adopted = true
        } finally {
            if (!adopted) next?.discardDisplayList()
        }
    }

    private fun drawShared(canvas: Canvas, bounds: GlassSharpRegion, scale: Float, shared: RenderNode) {
        val source = checkNotNull(sharedBounds)
        canvas.scale(1f / scale, 1f / scale)
        canvas.translate((source.left - bounds.left).toFloat(), (source.top - bounds.top).toFloat())
        canvas.drawRenderNode(shared)
    }

    private fun recordContent(
        bounds: GlassSharpRegion, width: Int, height: Int, scale: Float, name: String,
        clipToBounds: Boolean = true,
    ): RenderNode {
        val target = RenderNode(name)
        target.setPosition(0, 0, width, height)
        target.setClipToBounds(clipToBounds)
        var recorded = false
        try {
            val canvas = target.beginRecording(width, height)
            try {
                canvas.scale(1f / scale, 1f / scale)
                canvas.translate((lastX - bounds.left).toFloat(), (lastY - bounds.top).toFloat())
                val backgroundSave = canvas.save()
                try { drawBackground?.invoke(canvas) } finally { canvas.restoreToCount(backgroundSave) }
                
                
                canvas.translate((lastContentX - lastX - lastScrollX).toFloat(),
                    (lastContentY - lastY - lastScrollY).toFloat())
                content.draw(canvas)
            } finally { target.endRecording() }
            recorded = true
            return target
        } finally {
            if (!recorded) target.discardDisplayList()
        }
    }

    override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
        if (!ready || !eligible() || !canvas.isHardwareAccelerated || !canvas.getClipBounds(clip)) return false
        
        
        
        for (region in regions) {
            if (!region.recorded || !region.node.hasDisplayList() || !GlassRegionMath.covers(region.bounds,
                    clip.left + consumerWindowX, clip.top + consumerWindowY,
                    clip.right + consumerWindowX, clip.bottom + consumerWindowY)
            ) continue
            val save = canvas.save()
            canvas.translate(region.bounds.left - consumerWindowX, region.bounds.top - consumerWindowY)
            canvas.clipRect(0f, 0f, region.bounds.width.toFloat(), region.bounds.height.toFloat())
            canvas.scale(GlassTuning.DOWNSAMPLE, GlassTuning.DOWNSAMPLE)
            canvas.drawRenderNode(region.node)
            canvas.restoreToCount(save)
            return true
        }
        return false
    }

    override fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean {
        val source = extent ?: return false
        if (!ready || !eligible() || !canvas.isHardwareAccelerated) return false
        val region = GlassSharpMath.region(bounds.left + consumerWindowX, bounds.top + consumerWindowY,
            bounds.right + consumerWindowX, bounds.bottom + consumerWindowY,
            source.left, source.top, source.right, source.bottom) ?: return false
        val cached = sharpNodes[region]
        val capture = if (cached != null && !cached.dirty) cached else {
            val shared = sharedCapture
            val node = if (shared == null) recordContent(region, region.width, region.height, 1f, "GlassBackdropSharp")
            else RenderNode("GlassBackdropSharpReference").apply {
                setPosition(0, 0, region.width, region.height)
                setClipToBounds(true)
                val recording = beginRecording(region.width, region.height)
                try { drawShared(recording, region, 1f, shared) } finally { endRecording() }
            }
            SharpRegion(node).also {
                sharpNodes[region] = it
                if (sharpNodes.size > MAX_SHARP_REGIONS) sharpNodes.entries.iterator().run { next(); remove() }
            }
        }
        val save = canvas.save()
        canvas.translate(region.left - consumerWindowX, region.top - consumerWindowY)
        canvas.drawRenderNode(capture.node)
        canvas.restoreToCount(save)
        return true
    }

    override fun addListener(listener: () -> Unit) {
        if (!closed && listener !in demands) demands[listener] = Demand(listener)
    }

    override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
        val demand = demands[listener] ?: return
        val bounds = boundsInWindow?.let { GlassSharpMath.region(it.left, it.top, it.right, it.bottom,
            Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE) }
        if (bounds == demand.bounds && sharp == demand.sharp) return
        if (!sharp || !demand.sharp) planDirty = true
        demand.bounds = bounds
        demand.sharp = sharp
        demand.pending = bounds != null
        if (bounds != null) viewport.postInvalidateOnAnimation()
    }

    override fun removeListener(listener: () -> Unit) {
        if (demands.remove(listener)?.sharp == false) planDirty = true
        if (demands.isEmpty()) { releaseNodes(); dirty = true }
    }

    override fun invalidate() {
        if (closed) return
        dirty = true
        if (demands.values.any { it.bounds != null }) viewport.postInvalidateOnAnimation()
    }

    private fun queue(listener: () -> Unit) { if (listener !in callbacks) callbacks += listener }

    private fun dispatchCallbacks() {
        if (notifying) return
        notifying = true
        try {
            var index = 0
            while (index < callbacks.size) callbacks[index++]()
        } finally { callbacks.clear(); notifying = false }
    }

    private fun releaseNodes() {
        regions.forEach { it.close() }
        regions.clear()
        sharpNodes.values.forEach { it.node.discardDisplayList() }
        sharpNodes.clear()
        sharedCapture?.discardDisplayList()
        sharedCapture = null
        sharedBounds = null
        fullCaptureMode = false
        activeTracker?.release()
        activeTracker = null
        blurEffect = null
        extent = null
        planDirty = true
        ready = false
    }

    override fun close() {
        if (closed) return
        closed = true
        removeObservers()
        releaseNodes()
        viewport.removeOnAttachStateChangeListener(this)
        if (viewport.getTag(R.id.glass_backdrop) === this) viewport.setTag(R.id.glass_backdrop, null)
        demands.values.forEach { queue(it.listener) }
        demands.clear()
        dispatchCallbacks()
    }
}
