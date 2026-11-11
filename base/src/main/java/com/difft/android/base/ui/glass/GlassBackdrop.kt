package com.difft.android.base.ui.glass

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.view.View
import androidx.core.graphics.Insets
import com.difft.android.base.R


interface GlassBackdrop : AutoCloseable {
    



    fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean
    






    fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean = false
    
    fun addListener(listener: () -> Unit)
    




    fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean = false) = Unit
    fun removeListener(listener: () -> Unit)
    
    fun invalidate()
    override fun close()
}









fun View.installGlassBackdrop(
    content: View = this,
    captureInsets: () -> Insets = { Insets.NONE },
    drawBackground: ((Canvas) -> Unit)? = null,
): GlassBackdrop {
    (getTag(R.id.glass_backdrop) as? GlassBackdrop)?.close()
    val backdrop = if (GlassMode.isLive(context) && Build.VERSION.SDK_INT >= 31) {
        ViewGlassBackdrop(this, content, drawBackground, captureInsets)
    } else {
        EmptyGlassBackdrop(this)
    }
    setTag(R.id.glass_backdrop, backdrop)
    return backdrop
}


fun View.findGlassBackdrop(): GlassBackdrop? {
    var view: View? = this
    while (view != null) {
        (view.getTag(R.id.glass_backdrop) as? GlassBackdrop)?.let { return it }
        view = view.parent as? View
    }
    return null
}

private class EmptyGlassBackdrop(private val viewport: View) : GlassBackdrop {
    override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float) = false
    override fun addListener(listener: () -> Unit) = Unit
    override fun removeListener(listener: () -> Unit) = Unit
    override fun invalidate() = Unit
    override fun close() {
        if (viewport.getTag(R.id.glass_backdrop) === this) viewport.setTag(R.id.glass_backdrop, null)
    }
}
