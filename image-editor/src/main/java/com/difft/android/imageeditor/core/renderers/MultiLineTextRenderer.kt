package com.difft.android.imageeditor.core.renderers

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.animation.Interpolator
import androidx.annotation.ColorInt
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import util.DimensionUnit
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.ColorableRenderer
import com.difft.android.imageeditor.core.RendererContext
import com.difft.android.imageeditor.core.SelectableRenderer
import kotlin.math.max
import kotlin.math.min

/**
 * Renders multiple lines of [text] in ths specified [color].
 *
 * Scales down the text size of long lines to fit inside the [Bounds] width.
 */
@Parcelize
class MultiLineTextRenderer(
    private var textValue: String,
    @ColorInt private var colorValue: Int,
    private var modeValue: Mode
) : InvalidateableRenderer(), ColorableRenderer, SelectableRenderer {

    @IgnoredOnParcel
    private val paint = Paint()

    @IgnoredOnParcel
    private val selectionPaint = Paint()

    @IgnoredOnParcel
    private val modePaint = Paint()

    @IgnoredOnParcel
    private val textScale: Float

    @IgnoredOnParcel
    private var selStart = 0

    @IgnoredOnParcel
    private var selEnd = 0

    @IgnoredOnParcel
    private var hasFocus = false

    @IgnoredOnParcel
    private var lines: MutableList<Line> = ArrayList()

    @IgnoredOnParcel
    private var cursorAnimator: ValueAnimator? = null

    @IgnoredOnParcel
    private var cursorAnimatedValue = 0f

    @IgnoredOnParcel
    private val recommendedEditorMatrix = Matrix()

    @IgnoredOnParcel
    private val textBounds = RectF()

    init {
        modePaint.isAntiAlias = true
        modePaint.textSize = 100f

        setColorInternal(colorValue)

        val regularTextSize = paint.textSize

        paint.isAntiAlias = true
        paint.textSize = 100f

        textScale = paint.textSize / regularTextSize

        selectionPaint.isAntiAlias = true

        createLinesForText()
    }

    override fun render(rendererContext: RendererContext) {
        super.render(rendererContext)

        paint.typeface = rendererContext.typefaceProvider.getSelectedTypeface(rendererContext.context, this, rendererContext.invalidate)
        modePaint.typeface = rendererContext.typefaceProvider.getSelectedTypeface(rendererContext.context, this, rendererContext.invalidate)

        var height = 0f
        var width = 0f
        for (line in lines) {
            line.render(rendererContext)
            height += line.heightInBounds - line.ascentInBounds + line.descentInBounds
            width = max(line.textBounds.width(), width)
        }

        textBounds.set(-width - PADDING, (-PADDING).toFloat(), width + PADDING, height / 2f + PADDING)
    }

    fun getText(): String = textValue

    fun setText(text: String) {
        if (textValue != text) {
            textValue = text
            createLinesForText()
        }
    }

    fun nextMode() {
        setMode(Mode.fromCode(modeValue.code + 1))
    }

    fun getMode(): Mode = modeValue

    /**
     * Post concats an additional matrix to the supplied matrix that scales and positions the editor
     * so that all the text is visible.
     *
     * @param matrix editor matrix, already zoomed and positioned to fit the regular bounds.
     */
    fun applyRecommendedEditorMatrix(matrix: Matrix) {
        recommendedEditorMatrix.reset()

        var scale = 1f
        for (line in lines) {
            if (line.scale < scale) {
                scale = line.scale
            }
        }

        var yOff = 0f
        for (line in lines) {
            if (line.containsSelectionEnd()) {
                break
            } else {
                yOff -= line.heightInBounds
            }
        }

        recommendedEditorMatrix.postTranslate(0f, Bounds.TOP / 1.5f + yOff)

        recommendedEditorMatrix.postScale(scale, scale)

        matrix.postConcat(recommendedEditorMatrix)
    }

    private fun createLinesForText() {
        val split = textValue.split("\n")

        if (split.size == lines.size) {
            for (i in split.indices) {
                lines[i].updateText(split[i])
            }
        } else {
            lines = ArrayList(split.size)
            for (s in split) {
                lines.add(Line(s))
            }
        }
        setSelection(selStart, selEnd)
    }

    private inner class Line(text: String) {
        private val ascentMatrix = Matrix()
        private val descentMatrix = Matrix()
        private val projectionMatrix = Matrix()
        private val inverseProjectionMatrix = Matrix()
        private val selectionBounds = RectF()
        val textBounds = RectF()
        private val hitBounds = RectF()
        private val modeBounds = RectF()
        private val outlinerPath = Path()

        var text: String = text
            private set
        private var selStart = 0
        private var selEnd = 0
        var ascentInBounds = 0f
            private set
        var descentInBounds = 0f
            private set
        var scale = 1f
            private set
        var heightInBounds = 0f
            private set

        init {
            recalculate()
        }

        private fun recalculate() {
            val maxTextBounds = RectF()
            val temp = Rect()

            getTextBoundsWithoutTrim(text, 0, text.length, temp)
            textBounds.set(temp)
            hitBounds.set(textBounds)

            hitBounds.left -= HIT_PADDING
            hitBounds.right += HIT_PADDING
            hitBounds.top -= HIT_PADDING
            hitBounds.bottom += HIT_PADDING

            maxTextBounds.set(textBounds)
            val widthLimit = 150 * textScale

            scale = 1f / max(1f, maxTextBounds.right / widthLimit)

            maxTextBounds.right = widthLimit

            if (showSelectionOrCursor()) {
                val startTemp = Rect()
                val startInString = min(text.length, max(0, selStart))
                val endInString = min(text.length, max(0, selEnd))
                val startText = text.substring(0, startInString)

                getTextBoundsWithoutTrim(startText, 0, startInString, startTemp)

                if (selStart != selEnd) {
                    // selection
                    getTextBoundsWithoutTrim(text, startInString, endInString, temp)
                } else {
                    // cursor
                    paint.getTextBounds("|", 0, 1, temp)
                    val width = temp.width()

                    temp.left -= width
                    temp.right -= width
                }

                temp.left += startTemp.right
                temp.right += startTemp.right
                selectionBounds.set(temp)
            }

            projectionMatrix.setRectToRect(RectF(maxTextBounds), Bounds.FULL_BOUNDS, Matrix.ScaleToFit.CENTER)
            removeTranslate(projectionMatrix)

            val pts = floatArrayOf(0f, paint.ascent(), 0f, paint.descent())
            projectionMatrix.mapPoints(pts)
            ascentInBounds = pts[1]
            descentInBounds = pts[3]
            heightInBounds = descentInBounds - ascentInBounds

            projectionMatrix.preTranslate(-textBounds.centerX(), 0f)
            projectionMatrix.invert(inverseProjectionMatrix)

            ascentMatrix.setTranslate(0f, -ascentInBounds)
            descentMatrix.setTranslate(0f, descentInBounds + HIGHLIGHT_TOP_PADDING + HIGHLIGHT_BOTTOM_PADDING)

            invalidate()
        }

        private fun removeTranslate(matrix: Matrix) {
            val values = FloatArray(9)

            matrix.getValues(values)
            values[2] = 0f
            values[5] = 0f
            matrix.setValues(values)
        }

        private fun showSelectionOrCursor(): Boolean =
            (selStart >= 0 || selEnd >= 0) &&
                (selStart <= text.length || selEnd <= text.length)

        fun containsSelectionEnd(): Boolean =
            (selEnd >= 0) &&
                (selEnd <= text.length)

        private fun getTextBoundsWithoutTrim(text: String, start: Int, end: Int, result: Rect) {
            val extra = Rect()
            val xBounds = Rect()

            val cannotBeTrimmed = "x" + text.substring(max(0, start), min(text.length, end)) + "x"

            paint.getTextBounds(cannotBeTrimmed, 0, cannotBeTrimmed.length, extra)
            paint.getTextBounds("x", 0, 1, xBounds)
            result.set(extra)
            result.right -= 2 * xBounds.width()

            val temp = result.left
            result.left -= temp
            result.right -= temp
        }

        fun contains(x: Float, y: Float): Boolean {
            val dst = FloatArray(2)

            inverseProjectionMatrix.mapPoints(dst, floatArrayOf(x, y))

            return hitBounds.contains(dst[0], dst[1])
        }

        fun updateText(text: String) {
            if (this.text != text) {
                this.text = text
                recalculate()
            }
        }

        fun render(rendererContext: RendererContext) {
            // add our ascent for ourselves and the next lines
            rendererContext.canvasMatrix.concat(ascentMatrix)

            rendererContext.save()

            rendererContext.canvasMatrix.concat(projectionMatrix)

            if (modeValue == Mode.HIGHLIGHT) {
                if (text.isEmpty()) {
                    modeBounds.setEmpty()
                } else {
                    modeBounds.set(
                        textBounds.left - HIGHLIGHT_HORIZONTAL_PADDING,
                        selectionBounds.top - HIGHLIGHT_TOP_PADDING,
                        textBounds.right + HIGHLIGHT_HORIZONTAL_PADDING,
                        selectionBounds.bottom + HIGHLIGHT_BOTTOM_PADDING
                    )
                }
                val alpha = modePaint.alpha
                modePaint.alpha = rendererContext.getAlpha(alpha)
                rendererContext.canvas.drawRoundRect(modeBounds, HIGHLIGHT_CORNER_RADIUS, HIGHLIGHT_CORNER_RADIUS, modePaint)
                modePaint.alpha = alpha
            } else if (modeValue == Mode.UNDERLINE) {
                if (text.isEmpty()) {
                    modeBounds.setEmpty()
                } else {
                    modeBounds.set(textBounds.left, selectionBounds.top, textBounds.right, selectionBounds.bottom)
                    modeBounds.inset(-DimensionUnit.DP.toPixels(2f), -DimensionUnit.DP.toPixels(2f))

                    modeBounds.set(
                        modeBounds.left,
                        max(modeBounds.top, modeBounds.bottom - DimensionUnit.DP.toPixels(6f)),
                        modeBounds.right,
                        modeBounds.bottom - DimensionUnit.DP.toPixels(2f)
                    )
                }

                val alpha = modePaint.alpha
                modePaint.alpha = rendererContext.getAlpha(alpha)
                rendererContext.canvas.drawRect(modeBounds, modePaint)
                modePaint.alpha = alpha
            }

            if (hasFocus && showSelectionOrCursor()) {
                if (selStart == selEnd) {
                    selectionPaint.alpha = (cursorAnimatedValue * 128).toInt()
                } else {
                    selectionPaint.alpha = 128
                }
                rendererContext.canvas.drawRect(selectionBounds, selectionPaint)
            }

            val alpha = paint.alpha
            paint.alpha = rendererContext.getAlpha(alpha)

            rendererContext.canvas.drawText(text, 0f, 0f, paint)

            paint.alpha = alpha

            if (modeValue == Mode.OUTLINE) {
                val modeAlpha = modePaint.alpha
                modePaint.alpha = rendererContext.getAlpha(alpha)

                if (Build.VERSION.SDK_INT >= 31) {
                    outlinerPath.reset()
                    modePaint.getTextPath(text, 0, text.length, 0f, 0f, outlinerPath)
                    outlinerPath.op(outlinerPath, Path.Op.INTERSECT)
                    rendererContext.canvas.drawPath(outlinerPath, modePaint)
                } else {
                    rendererContext.canvas.drawText(text, 0f, 0f, modePaint)
                }
                modePaint.alpha = modeAlpha
            }

            rendererContext.restore()

            // add our descent for the next lines
            rendererContext.canvasMatrix.concat(descentMatrix)
        }

        fun setSelection(selStart: Int, selEnd: Int) {
            if (selStart != this.selStart || selEnd != this.selEnd) {
                this.selStart = selStart
                this.selEnd = selEnd
                recalculate()
            }
        }
    }

    override fun getColor(): Int = colorValue

    override fun setColor(@ColorInt color: Int) {
        if (colorValue != color) {
            setColorInternal(color)
        }
    }

    override fun onSelected(selected: Boolean) {
    }

    override fun getSelectionBounds(bounds: RectF) {
        bounds.set(textBounds)
    }

    override fun hitTest(x: Float, y: Float): Boolean = textBounds.contains(x, y)

    fun setSelection(selStart: Int, selEnd: Int) {
        this.selStart = selStart
        this.selEnd = selEnd
        var start = selStart
        var end = selEnd
        for (line in lines) {
            line.setSelection(start, end)

            val length = line.text.length + 1 // one for new line

            start -= length
            end -= length
        }
    }

    fun setFocused(hasFocus: Boolean) {
        if (this.hasFocus != hasFocus) {
            this.hasFocus = hasFocus
            if (cursorAnimator != null) {
                cursorAnimator!!.cancel()
                cursorAnimator = null
            }
            if (hasFocus) {
                val animator = ValueAnimator.ofFloat(0f, 1f)
                cursorAnimator = animator
                animator.interpolator = pulseInterpolator()
                animator.repeatCount = ValueAnimator.INFINITE
                animator.duration = 1000
                animator.addUpdateListener { animation ->
                    cursorAnimatedValue = animation.animatedValue as Float
                    invalidate()
                }
                animator.start()
            } else {
                invalidate()
            }
        }
    }

    private fun setMode(mode: Mode) {
        if (modeValue != mode) {
            modeValue = mode
            setColorInternal(colorValue)
        }
    }

    private fun setColorInternal(@ColorInt color: Int) {
        colorValue = color

        if (modeValue == Mode.REGULAR) {
            paint.color = color
            selectionPaint.color = color
        } else {
            paint.color = Color.WHITE
            selectionPaint.color = Color.WHITE
        }

        if (modeValue == Mode.OUTLINE) {
            modePaint.strokeWidth = DimensionUnit.DP.toPixels(15f) / 10f
            modePaint.style = Paint.Style.STROKE
        } else {
            modePaint.style = Paint.Style.FILL
        }

        modePaint.color = color
        invalidate()
    }

    enum class Mode(val code: Int) {
        REGULAR(0),
        HIGHLIGHT(1),
        UNDERLINE(2),
        OUTLINE(3);

        companion object {
            fun fromCode(code: Int): Mode = entries.firstOrNull { it.code == code } ?: REGULAR
        }
    }

    companion object {
        private val HIT_PADDING = DimensionUnit.DP.toPixels(30f)
        private val HIGHLIGHT_HORIZONTAL_PADDING = DimensionUnit.DP.toPixels(8f)
        private val HIGHLIGHT_TOP_PADDING = DimensionUnit.DP.toPixels(10f)
        private val HIGHLIGHT_BOTTOM_PADDING = DimensionUnit.DP.toPixels(6f)
        private val HIGHLIGHT_CORNER_RADIUS = DimensionUnit.DP.toPixels(4f)

        private const val PADDING = 10

        private fun pulseInterpolator(): Interpolator = Interpolator { input ->
            var value = input * 5
            if (value > 1) {
                value = 4 - value
            }
            max(0f, min(1f, value))
        }
    }
}
