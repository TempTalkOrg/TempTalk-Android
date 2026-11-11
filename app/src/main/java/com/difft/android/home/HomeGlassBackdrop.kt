package com.difft.android.home

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassBackdrop
import com.difft.android.base.ui.glass.GlassMode






internal class HomeGlassBackdrop(
    private val viewport: View,
    private val pager: ViewPager2,
) : GlassBackdrop, View.OnAttachStateChangeListener {
    private class Demand {
        val bounds = RectF()
        var present = false
        var sharp = false
    }
    private val listeners = linkedMapOf<() -> Unit, Demand>()
    private var borrowed: GlassBackdrop? = null
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        refreshSource()
        true
    }
    private val pageChanges = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = refreshSource()
    }

    init {
        viewport.setTag(R.id.glass_backdrop, this)
        viewport.addOnAttachStateChangeListener(this)
        pager.registerOnPageChangeCallback(pageChanges)
        if (viewport.isAttachedToWindow) onViewAttachedToWindow(viewport)
    }

    override fun onViewAttachedToWindow(v: View) {
        if (closed || observer != null) return
        observer = viewport.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        refreshSource()
    }

    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        releaseBorrowed()
    }

    private fun refreshSource() {
        if (closed) return
        val next = if (listeners.isNotEmpty() && viewport.isAttachedToWindow && GlassMode.isLive(viewport.context)) {
            val pages = pager.getChildAt(0) as? RecyclerView
            val selectedPage = pages?.findViewHolderForAdapterPosition(pager.currentItem)?.itemView
            selectedPage?.let(::findPageSource)
        } else null
        if (next === borrowed) return
        releaseBorrowed()
        borrowed = next
        if (next != null) {
            listeners.forEach { (listener, demand) ->
                next.addListener(listener)
                next.updateRegion(listener, demand.bounds.takeIf { demand.present }, demand.sharp)
            }
        }
        notifyConsumers()
    }

    
    private fun findPageSource(view: View): GlassBackdrop? {
        (view.getTag(R.id.glass_backdrop) as? GlassBackdrop)?.takeIf { it !== this }?.let { return it }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findPageSource(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun releaseBorrowed() {
        borrowed?.let { source -> listeners.keys.forEach(source::removeListener) }
        borrowed = null
    }

    private fun notifyConsumers() = listeners.keys.toList().forEach { it() }

    override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean =
        borrowed?.draw(canvas, consumerWindowX, consumerWindowY) == true

    override fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean =
        borrowed?.drawSharp(canvas, consumerWindowX, consumerWindowY, bounds) == true

    override fun addListener(listener: () -> Unit) {
        if (closed || listeners.containsKey(listener)) return
        listeners[listener] = Demand()
        val previous = borrowed
        refreshSource()
        if (previous != null && borrowed === previous) previous.addListener(listener)
    }

    override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
        val demand = listeners[listener] ?: return
        val present = boundsInWindow != null && !boundsInWindow.isEmpty
        if (demand.present == present && demand.sharp == sharp && (!present || demand.bounds == boundsInWindow)) return
        demand.present = present
        demand.sharp = sharp
        if (boundsInWindow != null) demand.bounds.set(boundsInWindow) else demand.bounds.setEmpty()
        borrowed?.updateRegion(listener, demand.bounds.takeIf { present }, sharp)
    }

    override fun removeListener(listener: () -> Unit) {
        if (listeners.remove(listener) != null) borrowed?.removeListener(listener)
        if (listeners.isEmpty()) releaseBorrowed()
    }

    override fun invalidate() {
        if (closed) return
        
        refreshSource()
        notifyConsumers()
    }

    override fun close() {
        if (closed) return
        closed = true
        onViewDetachedFromWindow(viewport)
        pager.unregisterOnPageChangeCallback(pageChanges)
        viewport.removeOnAttachStateChangeListener(this)
        if (viewport.getTag(R.id.glass_backdrop) === this) viewport.setTag(R.id.glass_backdrop, null)
        notifyConsumers()
        listeners.clear()
    }
}
