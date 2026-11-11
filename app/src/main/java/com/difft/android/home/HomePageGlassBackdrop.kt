package com.difft.android.home

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassBackdrop


internal class HomePageGlassBackdrop(
    private val viewport: View,
    private val owner: LifecycleOwner,
    private val source: GlassBackdrop,
) : GlassBackdrop, View.OnAttachStateChangeListener {
    private class Demand {
        val bounds = RectF()
        var present = false
        var sharp = false
    }

    private val listeners = linkedMapOf<() -> Unit, Demand>()
    private var active = false
    private var closed = false
    private var pendingInvalidation = false
    private val lifecycleEvents = LifecycleEventObserver { _, _ -> refresh() }

    init {
        viewport.setTag(R.id.glass_backdrop, this)
        viewport.addOnAttachStateChangeListener(this)
        owner.lifecycle.addObserver(lifecycleEvents)
        refresh()
    }

    override fun onViewAttachedToWindow(v: View) = refresh()
    override fun onViewDetachedFromWindow(v: View) = setActive(false)

    private fun refresh() {
        if (closed) return
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            close()
            return
        }
        setActive(viewport.isAttachedToWindow && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    private fun setActive(next: Boolean) {
        if (next == active) return
        if (active) listeners.keys.forEach(source::removeListener)
        active = next
        if (active) {
            listeners.forEach { (listener, demand) ->
                source.addListener(listener)
                source.updateRegion(listener, demand.bounds.takeIf { demand.present }, demand.sharp)
            }
            if (pendingInvalidation) {
                pendingInvalidation = false
                source.invalidate()
            }
        }
        notifyConsumers()
    }

    private fun notifyConsumers() = listeners.keys.toList().forEach { it() }

    override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean =
        active && source.draw(canvas, consumerWindowX, consumerWindowY)

    override fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean =
        active && source.drawSharp(canvas, consumerWindowX, consumerWindowY, bounds)

    override fun addListener(listener: () -> Unit) {
        if (closed || listeners.containsKey(listener)) return
        listeners[listener] = Demand()
        if (active) source.addListener(listener)
    }

    override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
        val demand = listeners[listener] ?: return
        val present = boundsInWindow != null && !boundsInWindow.isEmpty
        if (demand.present == present && demand.sharp == sharp && (!present || demand.bounds == boundsInWindow)) return
        demand.present = present
        demand.sharp = sharp
        if (boundsInWindow != null) demand.bounds.set(boundsInWindow) else demand.bounds.setEmpty()
        if (active) source.updateRegion(listener, demand.bounds.takeIf { present }, sharp)
    }

    override fun removeListener(listener: () -> Unit) {
        if (listeners.remove(listener) != null && active) source.removeListener(listener)
    }

    override fun invalidate() {
        if (closed) return
        if (active) source.invalidate() else pendingInvalidation = true
    }

    override fun close() {
        if (closed) return
        closed = true
        if (active) listeners.keys.forEach(source::removeListener)
        active = false
        owner.lifecycle.removeObserver(lifecycleEvents)
        viewport.removeOnAttachStateChangeListener(this)
        if (viewport.getTag(R.id.glass_backdrop) === this) viewport.setTag(R.id.glass_backdrop, null)
        notifyConsumers()
        listeners.clear()
    }
}
