package com.difft.android.imageeditor.core

import android.graphics.Matrix
import android.graphics.PointF
import com.difft.android.imageeditor.core.model.EditorElement












internal interface EditSession {

    fun movePoint(p: Int, point: PointF)

    val selected: EditorElement

    fun newPoint(newInverse: Matrix, point: PointF, p: Int): EditSession?

    fun removePoint(newInverse: Matrix, p: Int): EditSession?

    fun commit()
}
