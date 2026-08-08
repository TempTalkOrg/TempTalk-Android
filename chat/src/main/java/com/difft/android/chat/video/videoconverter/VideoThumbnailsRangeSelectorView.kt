package com.difft.android.chat.video.videoconverter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import com.difft.android.chat.R
import com.difft.android.chat.util.ViewUtil
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class VideoThumbnailsRangeSelectorView : VideoThumbnailsView {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGrey = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbTimeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbTimeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempDrawRect = Rect()
    private val timePillRect = RectF()
    private val activeRegionPath = Path()

    private var left = 0
    private var right = 0
    private var cursor = 0
    private var minValue: Long? = null
    private var maxValue: Long? = null
    private var externalMinValue: Long? = null
    private var externalMaxValue: Long? = null
    private var xDown = 0f
    private var downCursor: Long = 0
    private var downMin: Long = 0
    private var downMax: Long = 0
    private var dragThumb: Thumb? = null
    private var lastDragThumb: Thumb? = null
    private var playerDragListener: PositionDragListener? = null
    private var editorOnRangeChangeListener: RangeDragListener? = null
    private var thumbSizePixels = 0
    private var thumbTouchRadius = 0
    private var thumbColor = 0
    private var actualPosition: Long = 0
    private var dragPosition: Long = 0
    private var thumbHintTextSize = 0
    private var thumbHintTextColor = 0
    private var thumbHintBackgroundColor = 0
    private var dragStartTimeMs: Long = 0
    private var dragEndTimeMs: Long = 0
    private var maximumSelectableRangeMicros: Long = 0

    constructor(context: Context) : super(context) {
        initAttributes(null)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initAttributes(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initAttributes(attrs)
    }

    private fun initAttributes(attrs: AttributeSet?) {
        if (attrs != null) {
            val typedArray = context.theme.obtainStyledAttributes(attrs, R.styleable.VideoThumbnailsRangeSelectorView, 0, 0)
            try {
                thumbSizePixels = typedArray.getDimensionPixelSize(R.styleable.VideoThumbnailsRangeSelectorView_thumbWidth, 1)
                thumbColor = typedArray.getColor(R.styleable.VideoThumbnailsRangeSelectorView_thumbColor, -0x10000)
                thumbTouchRadius = typedArray.getDimensionPixelSize(R.styleable.VideoThumbnailsRangeSelectorView_thumbTouchRadius, 50)
                thumbHintTextSize = typedArray.getDimensionPixelSize(R.styleable.VideoThumbnailsRangeSelectorView_thumbHintTextSize, 0)
                thumbHintTextColor = typedArray.getColor(R.styleable.VideoThumbnailsRangeSelectorView_thumbHintTextColor, -0x10000)
                thumbHintBackgroundColor = typedArray.getColor(R.styleable.VideoThumbnailsRangeSelectorView_thumbHintBackgroundColor, -0xff0100)
            } finally {
                typedArray.recycle()
            }
        }

        paintGrey.color = 0x7f000000
        paintGrey.style = Paint.Style.FILL_AND_STROKE
        paintGrey.strokeWidth = 1f

        paint.strokeWidth = 2f

        thumbTimeTextPaint.textSize = thumbHintTextSize.toFloat()
        thumbTimeTextPaint.color = thumbHintTextColor

        thumbTimeBackgroundPaint.style = Paint.Style.FILL_AND_STROKE
        thumbTimeBackgroundPaint.color = thumbHintBackgroundColor
    }

    override fun afterDurationChange(duration: Long) {
        maxValue = duration

        if (duration > 0) {
            externalMaxValue?.let {
                setMinMax(getMinValue(), it, Thumb.MAX)
                externalMaxValue = null
            }

            externalMinValue?.let {
                setMinMax(it, getMaxValue(), Thumb.MIN)
                externalMinValue = null
            }
        }

        onRangeDrag(getMinValue(), getMaxValue(), duration, true)

        invalidate()
    }

    fun registerPlayerDragListener(playerDragListener: PositionDragListener?) {
        this.playerDragListener = playerDragListener
    }

    fun registerEditorOnRangeChangeListener(editorOnRangeChangeListener: RangeDragListener?) {
        this.editorOnRangeChangeListener = editorOnRangeChangeListener
    }

    fun unregisterDragListener() {
        this.playerDragListener = null
    }

    fun setActualPosition(position: Long) {
        if (this.actualPosition != position) {
            this.actualPosition = position
            invalidate()
        }
    }

    private fun setDragPosition(position: Long) {
        if (this.dragPosition != position) {
            this.dragPosition = max(getMinValue(), min(getMaxValue(), position))
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val drawableWidth = getDrawableWidth()
        val drawableHeight = getDrawableHeight()

        val duration = getDuration()

        val min = getMinValue()
        val max = getMaxValue()

        val drawPosAt = if (dragThumb == Thumb.POSITION) dragPosition else actualPosition

        left = if (duration != 0L) ((min * drawableWidth) / duration).toInt() else 0
        right = if (duration != 0L) ((max * drawableWidth) / duration).toInt() else drawableWidth
        cursor = if (duration != 0L) ((drawPosAt * drawableWidth) / duration).toInt() else drawableWidth

        canvas.save()
        canvas.clipPath(clippingPath)
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

        // draw greyed out areas
        if (Build.VERSION.SDK_INT >= 26) {
            activeRegionPath.reset()
            timePillRect.set((left + 1).toFloat(), 0f, (right - 1).toFloat(), drawableHeight.toFloat())
            activeRegionPath.addRoundRect(timePillRect, ACTIVE_REGION_CORNER_RADIUS, ACTIVE_REGION_CORNER_RADIUS, Path.Direction.CW)
            canvas.clipOutPath(activeRegionPath)
            tempDrawRect.set(0, 0, drawableWidth, drawableHeight)
            canvas.drawRect(tempDrawRect, paintGrey)
        } else {
            tempDrawRect.set(0, 0, left - 1, drawableHeight)
            canvas.drawRect(tempDrawRect, paintGrey)
            tempDrawRect.set(right + 1, 0, drawableWidth, drawableHeight)
            canvas.drawRect(tempDrawRect, paintGrey)
        }

        canvas.restore()

        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

        val verticalThumbInset = drawableHeight / 4
        val halfThumbWidth = thumbSizePixels / 2
        // draw thumb rectangles
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.color = thumbColor
        timePillRect.set((left - halfThumbWidth).toFloat(), verticalThumbInset.toFloat(), (left + halfThumbWidth).toFloat(), (drawableHeight - verticalThumbInset).toFloat())
        canvas.drawRoundRect(timePillRect, THUMB_RECT_CORNER_RADIUS, THUMB_RECT_CORNER_RADIUS, paint)
        timePillRect.set((right - halfThumbWidth).toFloat(), verticalThumbInset.toFloat(), (right + halfThumbWidth).toFloat(), (drawableHeight - verticalThumbInset).toFloat())
        canvas.drawRoundRect(timePillRect, THUMB_RECT_CORNER_RADIUS, THUMB_RECT_CORNER_RADIUS, paint)

        // draw time hint pill
        if (thumbHintTextSize > 0) {
            if (dragStartTimeMs > 0 && (dragThumb == Thumb.MIN || dragThumb == Thumb.MAX)) {
                drawTimeHint(canvas, drawableWidth, dragThumb!!, false)
            }
            if (dragEndTimeMs > 0 && (lastDragThumb == Thumb.MIN || lastDragThumb == Thumb.MAX)) {
                drawTimeHint(canvas, drawableWidth, lastDragThumb!!, true)
            }
        }

        // draw current position marker
        if (left <= cursor && cursor <= right && dragThumb != Thumb.MIN && dragThumb != Thumb.MAX) {
            timePillRect.set((cursor - halfThumbWidth).toFloat(), 0f, (cursor + halfThumbWidth).toFloat(), drawableHeight.toFloat())
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.color = thumbColor
            canvas.drawRoundRect(timePillRect, THUMB_RECT_CORNER_RADIUS, THUMB_RECT_CORNER_RADIUS, paint)
        }
    }

    private fun drawTimeHint(canvas: Canvas, drawableWidth: Int, dragThumb: Thumb, fadeOut: Boolean) {
        canvas.save()
        val microsecondValue = if (dragThumb == Thumb.MIN) getMinValue() else getMaxValue()
        val seconds = TimeUnit.MICROSECONDS.toSeconds(microsecondValue)
        val timeString = String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
        val topBottomPadding = thumbHintTextSize * 0.5f
        val leftRightPadding = thumbHintTextSize * 0.75f

        thumbTimeTextPaint.getTextBounds(timeString, 0, timeString.length, tempDrawRect)

        timePillRect.set(tempDrawRect.left - leftRightPadding, tempDrawRect.top - topBottomPadding, tempDrawRect.right + leftRightPadding, tempDrawRect.bottom + topBottomPadding)

        val halfPillWidth = timePillRect.width() / 2f
        val halfPillHeight = timePillRect.height() / 2f

        val animationTime = if (fadeOut) {
            ANIMATION_DURATION_MS - min(ANIMATION_DURATION_MS.toLong(), System.currentTimeMillis() - dragEndTimeMs)
        } else {
            min(ANIMATION_DURATION_MS.toLong(), System.currentTimeMillis() - dragStartTimeMs)
        }
        val animationPosition = animationTime / ANIMATION_DURATION_MS.toFloat()
        val scaleIn = 0.2f * animationPosition + 0.8f
        val alpha = (255 * animationPosition).toInt()

        if (dragThumb == Thumb.MAX) {
            canvas.translate(min(right.toFloat(), drawableWidth - halfPillWidth), 0f)
        } else {
            canvas.translate(max(left.toFloat(), halfPillWidth), 0f)
        }

        val timePillOffset = timePillRect.height() * -1.5f
        canvas.translate(0f, timePillOffset)
        canvas.scale(scaleIn, scaleIn)
        thumbTimeTextPaint.alpha = alpha
        thumbTimeBackgroundPaint.alpha = alpha
        canvas.translate(leftRightPadding - halfPillWidth, halfPillHeight)
        canvas.drawRoundRect(timePillRect, halfPillHeight, halfPillHeight, thumbTimeBackgroundPaint)
        canvas.drawText(timeString, 0f, 0f, thumbTimeTextPaint)
        canvas.restore()

        if (fadeOut && animationTime > 0 || !fadeOut && animationTime < ANIMATION_DURATION_MS) {
            invalidate()
        } else {
            if (fadeOut) {
                lastDragThumb = null
            }
        }
    }

    fun getMinValue(): Long = minValue ?: 0L

    fun getMaxValue(): Long = maxValue ?: getDuration()

    private fun setMinValue(minValue: Long): Boolean =
        if (this.minValue == null || this.minValue != minValue) {
            setMinMax(minValue, getMaxValue(), Thumb.MIN)
        } else {
            false
        }

    private fun setMaxValue(maxValue: Long): Boolean =
        if (this.maxValue == null || this.maxValue != maxValue) {
            setMinMax(getMinValue(), maxValue, Thumb.MAX)
        } else {
            false
        }

    private fun setMinMax(newMinIn: Long, newMaxIn: Long, thumb: Thumb): Boolean {
        var newMin = newMinIn
        var newMax = newMaxIn
        val currentMin = getMinValue()
        val currentMax = getMaxValue()
        val duration = getDuration()

        val minDiff = max(MINIMUM_SELECTABLE_RANGE, pixelToDuration(thumbSizePixels * 2.5f))
        val maxDiff = if (maximumSelectableRangeMicros <= MINIMUM_SELECTABLE_RANGE) 0L else max(maximumSelectableRangeMicros, pixelToDuration(thumbSizePixels * 2.5f))

        if (thumb == Thumb.MIN) {
            newMin = clamp(newMin, 0, currentMax - minDiff)
            if (maxDiff > 0) {
                newMax = clamp(newMax, newMin + minDiff, min(newMin + maxDiff, duration))
            }
        } else {
            newMax = clamp(newMax, currentMin + minDiff, duration)
            if (maxDiff > 0) {
                newMin = clamp(newMin, max(0, newMax - maxDiff), newMax - minDiff)
            }
        }

        if (newMin != currentMin || newMax != currentMax) {
            this.minValue = newMin
            this.maxValue = newMax
            invalidate()
            return true
        }
        return false
    }

    // Range-slider drag — not a clickable view.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            xDown = event.x
            downCursor = actualPosition
            downMin = getMinValue()
            downMax = getMaxValue()
            dragThumb = closestThumb(event.x)
            dragStartTimeMs = System.currentTimeMillis()
            invalidate()
            return dragThumb != null
        }

        if (actionMasked == MotionEvent.ACTION_MOVE) {
            val delta = pixelToDuration(event.x - xDown)
            var changed = false
            when (dragThumb) {
                Thumb.POSITION -> {
                    setDragPosition(pixelToDuration(event.x))
                    changed = true
                }
                Thumb.MIN -> changed = setMinValue(downMin + delta)
                Thumb.MAX -> changed = setMaxValue(downMax + delta)
                else -> {}
            }
            if (changed) {
                if (dragThumb == Thumb.POSITION) {
                    onPositionDrag(dragPosition)
                } else {
                    onRangeDrag(getMinValue(), getMaxValue(), getDuration(), false)
                }
            }
            return true
        }

        if (actionMasked == MotionEvent.ACTION_UP) {
            if (editorOnRangeChangeListener != null) {
                if (dragThumb == Thumb.POSITION) {
                    onEndPositionDrag(dragPosition)
                } else {
                    onRangeDrag(getMinValue(), getMaxValue(), getDuration(), true)
                }
                lastDragThumb = dragThumb
                dragEndTimeMs = System.currentTimeMillis()
                dragThumb = null
                invalidate()
            }
            return true
        }

        if (actionMasked == MotionEvent.ACTION_CANCEL) {
            dragThumb = null
        }

        return true
    }

    private fun closestThumb(x: Float): Thumb {
        val midPoint = (right + left) / 2f
        val possibleThumb = if (x < midPoint) Thumb.MIN else Thumb.MAX
        val possibleThumbX = if (x < midPoint) left else right

        if (abs(x - possibleThumbX) < thumbTouchRadius) {
            return possibleThumb
        }

        return Thumb.POSITION
    }

    private fun pixelToDuration(pixel: Float): Long =
        (pixel / getDrawableWidth() * getDuration()).toLong()

    private fun getDrawableWidth(): Int = width - paddingLeft - paddingRight

    private fun getDrawableHeight(): Int = height - paddingBottom - paddingTop

    fun setRange(minValue: Long, maxValue: Long) {
        if (getDuration() > 0) {
            setMinMax(minValue, maxValue, Thumb.MIN)
        } else {
            externalMinValue = minValue
            externalMaxValue = maxValue
        }
    }

    fun setTimeLimit(t: Int, timeUnit: TimeUnit) {
        maximumSelectableRangeMicros = timeUnit.toMicros(t.toLong())
    }

    private fun onPositionDrag(position: Long) {
        playerDragListener?.onPositionDrag(position)
    }

    private fun onEndPositionDrag(position: Long) {
        playerDragListener?.onEndPositionDrag(position)
    }

    private fun onRangeDrag(minValue: Long, maxValue: Long, duration: Long, end: Boolean) {
        editorOnRangeChangeListener?.onRangeDrag(minValue, maxValue, duration, end)
    }

    enum class Thumb {
        MIN,
        MAX,
        POSITION
    }

    interface PositionDragListener {
        fun onPositionDrag(position: Long)

        fun onEndPositionDrag(position: Long)
    }

    interface RangeDragListener {
        fun onRangeDrag(minValue: Long, maxValue: Long, duration: Long, start: Boolean)
    }

    companion object {
        private const val TAG = "VideoThumbnailsRangeSelectorView"

        private val MINIMUM_SELECTABLE_RANGE = TimeUnit.MILLISECONDS.toMicros(500)
        private const val ANIMATION_DURATION_MS = 100
        private val THUMB_RECT_CORNER_RADIUS: Float = ViewUtil.dpToPx(4).toFloat()
        private val ACTIVE_REGION_CORNER_RADIUS: Float = ViewUtil.dpToPx(8).toFloat()

        private fun clamp(value: Long, min: Long, max: Long): Long = min(max(min, value), max)
    }
}
