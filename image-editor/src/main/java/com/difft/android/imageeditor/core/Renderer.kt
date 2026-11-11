package com.difft.android.imageeditor.core

import android.os.Parcelable
import com.difft.android.imageeditor.core.model.EditorElement






interface Renderer : Parcelable {

    




    fun render(rendererContext: RendererContext)

    




    fun hitTest(x: Float, y: Float): Boolean
}
