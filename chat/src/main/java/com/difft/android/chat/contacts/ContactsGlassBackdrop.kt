package com.difft.android.chat.contacts

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassBackdrop


internal class ContactsGlassBackdrop(
    private val viewport: ViewGroup,
    private val owner: LifecycleOwner,
) : GlassBackdrop, View.OnAttachStateChangeListener {
    private class Demand {
        val bounds = RectF()
        var present = false
        var sharp = false
    }
    private val demands = linkedMapOf<() -> Unit, Demand>()
    private var source: GlassBackdrop? = null
    private var borrowed: GlassBackdrop? = null
    private var attached = viewport.isAttachedToWindow
    private var closed = false
    private val lifecycle = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) close() else refresh()
    }
    init {
        viewport.setTag(R.id.glass_backdrop, this)
        viewport.addOnAttachStateChangeListener(this)
        owner.lifecycle.addObserver(lifecycle)
    }

    override fun onViewAttachedToWindow(v: View) {
        attached = true
        refresh()
    }

    override fun onViewDetachedFromWindow(v: View) {
        attached = false
        releaseBorrowed()
    }

    
    fun setSource(source: GlassBackdrop?) {
        if (closed || this.source === source) return
        this.source = source.takeUnless { it === this }
        refresh()
    }

    private fun refresh() {
        if (closed) return
        val next = if (demands.isNotEmpty() && attached &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            source
        } else null
        if (next === borrowed) return
        releaseBorrowed()
        borrowed = next
        if (next != null) for ((listener, demand) in demands) {
            next.addListener(listener)
            next.updateRegion(listener, demand.bounds.takeIf { demand.present }, demand.sharp)
        }
        notifyConsumers()
    }

    private fun releaseBorrowed() {
        borrowed?.let { source -> demands.keys.forEach(source::removeListener) }
        borrowed = null
    }

    private fun notifyConsumers() = demands.keys.toList().forEach { it() }

    override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean =
        borrowed?.draw(canvas, consumerWindowX, consumerWindowY) == true

    override fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean =
        borrowed?.drawSharp(canvas, consumerWindowX, consumerWindowY, bounds) == true

    override fun addListener(listener: () -> Unit) {
        if (closed || demands.containsKey(listener)) return
        demands[listener] = Demand()
        val previous = borrowed
        refresh()
        if (previous != null && borrowed === previous) previous.addListener(listener)
    }

    override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
        val demand = demands[listener] ?: return
        val present = boundsInWindow != null && !boundsInWindow.isEmpty
        if (present == demand.present && sharp == demand.sharp && (!present || demand.bounds == boundsInWindow)) return
        demand.present = present
        demand.sharp = sharp
        if (boundsInWindow != null) demand.bounds.set(boundsInWindow)
        borrowed?.updateRegion(listener, demand.bounds.takeIf { present }, sharp)
    }

    override fun removeListener(listener: () -> Unit) {
        if (demands.remove(listener) != null) borrowed?.removeListener(listener)
        if (demands.isEmpty()) releaseBorrowed()
    }

    override fun invalidate() {
        if (closed) return
        refresh()
        notifyConsumers()
    }

    override fun close() {
        if (closed) return
        closed = true
        onViewDetachedFromWindow(viewport)
        owner.lifecycle.removeObserver(lifecycle)
        viewport.removeOnAttachStateChangeListener(this)
        if (viewport.getTag(R.id.glass_backdrop) === this) viewport.setTag(R.id.glass_backdrop, null)
        notifyConsumers()
        demands.clear()
        source = null
    }
}
