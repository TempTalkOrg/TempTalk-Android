package com.difft.android.imageeditor.core.renderers

import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext
import java.lang.ref.WeakReference




abstract class InvalidateableRenderer : Renderer {

    private var invalidate = WeakReference<RendererContext.Invalidate>(null)

    override fun render(rendererContext: RendererContext) {
        setInvalidate(rendererContext.invalidate)
    }

    private fun setInvalidate(invalidate: RendererContext.Invalidate) {
        if (invalidate !== this.invalidate.get()) {
            this.invalidate = WeakReference(invalidate)
        }
    }

    protected fun invalidate() {
        val invalidate = this.invalidate.get()
        invalidate?.onInvalidate(this)
    }
}
