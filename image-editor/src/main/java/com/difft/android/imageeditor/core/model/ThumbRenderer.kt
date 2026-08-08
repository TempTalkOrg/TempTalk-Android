package com.difft.android.imageeditor.core.model

import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.Renderer
import java.util.UUID

/**
 * A special [Renderer] that controls another [EditorElement].
 *
 * It has a reference to the [EditorElement.id] and a [ControlPoint] which it is in control of.
 *
 * The presence of this interface on the selected element is used to launch a ThumbDragEditSession.
 */
interface ThumbRenderer : Renderer {

    val controlPoint: ControlPoint

    val elementToControl: UUID

    enum class ControlPoint(val x: Float, val y: Float) {

        // 8 point controls
        CENTER_LEFT(Bounds.LEFT, Bounds.CENTRE_Y),
        CENTER_RIGHT(Bounds.RIGHT, Bounds.CENTRE_Y),

        TOP_CENTER(Bounds.CENTRE_X, Bounds.TOP),
        BOTTOM_CENTER(Bounds.CENTRE_X, Bounds.BOTTOM),

        TOP_LEFT(Bounds.LEFT, Bounds.TOP),
        TOP_RIGHT(Bounds.RIGHT, Bounds.TOP),
        BOTTOM_LEFT(Bounds.LEFT, Bounds.BOTTOM),
        BOTTOM_RIGHT(Bounds.RIGHT, Bounds.BOTTOM),

        // 2 point controls
        SCALE_ROT_LEFT(Bounds.LEFT, Bounds.CENTRE_Y),
        SCALE_ROT_RIGHT(Bounds.RIGHT, Bounds.CENTRE_Y),
        ORIGIN(0f, 0f);

        fun opposite(): ControlPoint {
            return when (this) {
                CENTER_LEFT -> CENTER_RIGHT
                CENTER_RIGHT -> CENTER_LEFT
                TOP_CENTER -> BOTTOM_CENTER
                BOTTOM_CENTER -> TOP_CENTER
                TOP_LEFT -> BOTTOM_RIGHT
                TOP_RIGHT -> BOTTOM_LEFT
                BOTTOM_LEFT -> TOP_RIGHT
                BOTTOM_RIGHT -> TOP_LEFT
                SCALE_ROT_LEFT, SCALE_ROT_RIGHT -> ORIGIN
                else -> throw RuntimeException()
            }
        }

        fun isHorizontalCenter(): Boolean = this == CENTER_LEFT || this == CENTER_RIGHT

        fun isVerticalCenter(): Boolean = this == TOP_CENTER || this == BOTTOM_CENTER

        fun isCenter(): Boolean = isHorizontalCenter() || isVerticalCenter()

        fun isScaleAndRotateThumb(): Boolean = this == SCALE_ROT_LEFT || this == SCALE_ROT_RIGHT
    }
}
