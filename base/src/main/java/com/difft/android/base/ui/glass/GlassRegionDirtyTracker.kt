package com.difft.android.base.ui.glass

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi


interface GlassDamageSource {
    val glassDamage: GlassRegionDirtyTracker
}








class GlassRegionDirtyTracker(private val source: ViewGroup) {
    private val matrix = Matrix()
    private val sourceMatrix = Matrix()
    private val values = FloatArray(9)
    private val bounds = RectF()
    private val clip = Rect()
    private val screen = IntArray(2)
    private val window = IntArray(2)
    private val rows = ArrayList<Geometry>()
    private val sourceGeometry = Geometry(source)
    private var pending = Damage()
    private var frame = Damage()
    private var sourceKnown = false
    private var screenOffsetX = 0
    private var screenOffsetY = 0
    private var users = 0
    private var childAnimation = false
    private var unboundedRow = false
    var overscrollActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateAll()
        }

    




    val requiresFullCapture: Boolean
        get() = source.hasTransientState() || source.animation?.hasEnded() == false || childAnimation ||
            unboundedRow || overscrollActive

    
    fun acquire() {
        if (users++ == 0) reset()
    }

    fun release() {
        if (users > 0 && --users == 0) reset()
    }

    
    fun invalidateAll() { pending.all = true }

    
    @RequiresApi(31)
    fun descendantInvalidated(target: View) {
        if (users == 0 || pending.all) return
        if (target === source) {
            invalidateAll()
            return
        }
        var row = target
        while (row.parent !== source) {
            row = row.parent as? View ?: return
        }
        
        if (hasUnboundedDrawing(row)) { invalidateAll(); return }
        
        for (index in rows.indices) {
            if (rows[index].view === row) { pending.add(rows[index].bounds); break }
        }
        mapBounds(row)
        pending.add(bounds)
        if (target !== row) {
            mapBounds(target)
            pending.add(bounds)
        }
    }

    
    @RequiresApi(31)
    fun beginFrame() {
        if (users == 0) return
        check(frame.count == 0 && !frame.all) { "Previous glass damage frame was not ended" }
        source.getLocationOnScreen(screen)
        source.getLocationInWindow(window)
        screenOffsetX = screen[0] - window[0]
        screenOffsetY = screen[1] - window[1]
        sourceMatrix.reset()
        source.transformMatrixToGlobal(sourceMatrix)
        sourceMatrix.postTranslate(-screenOffsetX.toFloat(), -screenOffsetY.toFloat())
        if (sample(sourceGeometry) || !sourceKnown) invalidateAll()
        childAnimation = false
        unboundedRow = false
        sourceKnown = true
        var index = rows.lastIndex
        while (index >= 0) {
            if (rows[index].view.parent !== source) {
                if (rows[index].unbounded) invalidateAll()
                pending.add(rows[index].bounds)
                rows.removeAt(index)
            }
            index--
        }
        for (childIndex in 0 until source.childCount) {
            val child = source.getChildAt(childIndex)
            var geometry: Geometry? = null
            for (savedIndex in rows.indices) {
                val saved = rows[savedIndex]
                if (saved.view === child) { geometry = saved; break }
            }
            if (geometry == null) {
                geometry = Geometry(child)
                sample(geometry)
                rows.add(geometry)
                pending.add(geometry.bounds)
            } else {
                bounds.set(geometry.bounds)
                val oldLeft = bounds.left
                val oldTop = bounds.top
                val oldRight = bounds.right
                val oldBottom = bounds.bottom
                if (sample(geometry)) {
                    pending.add(oldLeft, oldTop, oldRight, oldBottom)
                    pending.add(geometry.bounds)
                }
            }
            
            if (child.animation?.hasEnded() == false) childAnimation = true
            if (geometry.unbounded) unboundedRow = true
        }
        if (requiresFullCapture) invalidateAll()
        val empty = frame
        frame = pending
        pending = empty
    }

    
    fun isDirty(boundsInWindow: RectF): Boolean {
        if (frame.all) return true
        for (index in 0 until frame.count) if (RectF.intersects(frame.rects[index], boundsInWindow)) return true
        return false
    }

    
    fun captureBounds(out: RectF) {
        out.set(sourceGeometry.bounds)
        for (index in rows.indices) out.union(rows[index].bounds)
    }

    fun endFrame() { frame.clear() }

    
    fun reset() {
        rows.clear()
        sourceKnown = false
        childAnimation = false
        unboundedRow = false
        pending.clear()
        frame.clear()
        invalidateAll()
    }

    @RequiresApi(31)
    private fun mapBounds(view: View) {
        when {
            view === source -> matrix.set(sourceMatrix)
            view.parent === source -> {
                matrix.set(sourceMatrix)
                matrix.preTranslate((view.left - source.scrollX).toFloat(), (view.top - source.scrollY).toFloat())
                if (!view.matrix.isIdentity) matrix.preConcat(view.matrix)
            }
            else -> {
                matrix.reset()
                view.transformMatrixToGlobal(matrix)
                matrix.postTranslate(-screenOffsetX.toFloat(), -screenOffsetY.toFloat())
            }
        }
        bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        matrix.mapRect(bounds)
    }

    @RequiresApi(31)
    private fun sample(geometry: Geometry): Boolean {
        val view = geometry.view
        val group = view as? ViewGroup
        mapBounds(view)
        matrix.getValues(values)
        val hasClip = view.getClipBounds(clip)
        var changed = geometry.width != view.width || geometry.height != view.height ||
            geometry.alpha != view.alpha || geometry.visibility != view.visibility ||
            geometry.scrollX != view.scrollX || geometry.scrollY != view.scrollY ||
            geometry.elevation != view.elevation || geometry.translationZ != view.translationZ ||
            geometry.paddingLeft != view.paddingLeft || geometry.paddingTop != view.paddingTop ||
            geometry.paddingRight != view.paddingRight || geometry.paddingBottom != view.paddingBottom ||
            geometry.clipChildren != group?.clipChildren || geometry.clipToPadding != group?.clipToPadding ||
            geometry.hasClip != hasClip || (hasClip && geometry.clip != clip)
        for (index in values.indices) {
            if (geometry.matrix[index] != values[index]) changed = true
            geometry.matrix[index] = values[index]
        }
        val unbounded = hasUnboundedDrawing(view)
        if (changed && (unbounded || geometry.unbounded)) invalidateAll()
        geometry.unbounded = unbounded
        geometry.bounds.set(bounds)
        geometry.width = view.width
        geometry.height = view.height
        geometry.alpha = view.alpha
        geometry.visibility = view.visibility
        geometry.scrollX = view.scrollX
        geometry.scrollY = view.scrollY
        geometry.elevation = view.elevation
        geometry.translationZ = view.translationZ
        geometry.paddingLeft = view.paddingLeft
        geometry.paddingTop = view.paddingTop
        geometry.paddingRight = view.paddingRight
        geometry.paddingBottom = view.paddingBottom
        geometry.clipChildren = group?.clipChildren
        geometry.clipToPadding = group?.clipToPadding
        geometry.hasClip = hasClip
        if (hasClip) geometry.clip.set(clip)
        return changed
    }

    private fun hasUnboundedDrawing(view: View): Boolean =
        view.elevation + view.translationZ > 0f || (view is ViewGroup && !view.clipChildren)

    private class Geometry(val view: View) {
        val bounds = RectF()
        val matrix = FloatArray(9)
        val clip = Rect()
        var width = -1
        var height = -1
        var alpha = Float.NaN
        var visibility = -1
        var scrollX = 0
        var scrollY = 0
        var elevation = Float.NaN
        var translationZ = Float.NaN
        var paddingLeft = 0
        var paddingTop = 0
        var paddingRight = 0
        var paddingBottom = 0
        var clipChildren: Boolean? = null
        var clipToPadding: Boolean? = null
        var hasClip = false
        var unbounded = false
    }

    private class Damage {
        val rects = ArrayList<RectF>()
        var count = 0
        var all = false
        fun add(rect: RectF) = add(rect.left, rect.top, rect.right, rect.bottom)
        fun add(left: Float, top: Float, right: Float, bottom: Float) {
            if (all || right <= left || bottom <= top) return
            for (index in 0 until count) {
                val rect = rects[index]
                if (rect.left == left && rect.top == top && rect.right == right && rect.bottom == bottom) return
            }
            if (count == rects.size) rects.add(RectF())
            rects[count++].set(left, top, right, bottom)
        }
        fun clear() { count = 0; all = false }
    }
}
