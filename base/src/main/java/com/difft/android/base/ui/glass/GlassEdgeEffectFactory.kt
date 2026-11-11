package com.difft.android.base.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.widget.EdgeEffect
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView


@RequiresApi(31)
internal class GlassEdgeEffectFactory(private val damage: GlassRegionDirtyTracker) : RecyclerView.EdgeEffectFactory() {
    private val edges = arrayOfNulls<TrackingEdgeEffect>(4)

    public override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect =
        TrackingEdgeEffect(view.context).also {
            edges[direction] = it
            refresh()
        }

    
    fun reset() {
        for (index in edges.indices) {
            edges[index]?.finish()
            edges[index] = null
        }
        refresh()
    }

    
    fun finishAll() {
        for (edge in edges) edge?.finish()
        refresh()
    }

    private fun refresh() {
        var active = false
        for (index in edges.indices) if (edges[index]?.isFinished == false) active = true
        damage.overscrollActive = active
    }

    private inner class TrackingEdgeEffect(context: Context) : EdgeEffect(context) {
        override fun onPull(deltaDistance: Float) {
            super.onPull(deltaDistance)
            refresh()
        }

        override fun onPull(deltaDistance: Float, displacement: Float) {
            super.onPull(deltaDistance, displacement)
            refresh()
        }

        override fun onPullDistance(deltaDistance: Float, displacement: Float): Float =
            super.onPullDistance(deltaDistance, displacement).also { refresh() }

        override fun onAbsorb(velocity: Int) {
            super.onAbsorb(velocity)
            refresh()
        }

        override fun onRelease() {
            super.onRelease()
            refresh()
        }

        override fun finish() {
            super.finish()
            refresh()
        }

        override fun draw(canvas: Canvas): Boolean = super.draw(canvas).also { refresh() }
    }
}
