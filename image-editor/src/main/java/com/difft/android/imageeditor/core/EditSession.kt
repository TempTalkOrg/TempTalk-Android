package com.difft.android.imageeditor.core

import android.graphics.Matrix
import android.graphics.PointF
import com.difft.android.imageeditor.core.model.EditorElement

/**
 * Represents an underway edit of the image.
 *
 * Accepts new touch positions, new touch points, released touch points and when complete can commit the edit.
 *
 * Examples of edit session implementations are, Drag, Draw, Resize:
 *
 * [ElementDragEditSession] for dragging with a single finger.
 * [ElementScaleEditSession] for resize/dragging with two fingers.
 * [DrawingSession] for drawing with a single finger.
 */
internal interface EditSession {

    fun movePoint(p: Int, point: PointF)

    val selected: EditorElement

    fun newPoint(newInverse: Matrix, point: PointF, p: Int): EditSession?

    fun removePoint(newInverse: Matrix, p: Int): EditSession?

    fun commit()
}
