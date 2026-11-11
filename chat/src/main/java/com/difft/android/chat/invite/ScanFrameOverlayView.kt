package com.difft.android.chat.invite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.base.R as baseR











class ScanFrameOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    
    
    
    
    private val rectMinPx = 200.dp.toFloat()
    private val rectMaxPx = 320.dp.toFloat()
    private val rectSizePx: Float
        get() {
            val shortSide = minOf(width, height).toFloat()
            return (shortSide * RECT_FRACTION_OF_SHORT_SIDE).coerceIn(rectMinPx, rectMaxPx).coerceAtMost(shortSide * RECT_MAX_FRACTION_OF_VIEW)
        }
    private val cornerLenPx = 20.dp.toFloat() 
    private val cornerThickPx = 3.dp.toFloat() 
    private val borderPx = 1.dp.toFloat()

    
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scan_mask_color) 
    }

    
    
    
    
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderPx
        color = ContextCompat.getColor(context, baseR.color.white)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, baseR.color.info) 
    }

    private val rect = RectF()

    

    var migrationStyle: Boolean = false
        set(value) {
            field = value
            if (value) stopScanLine()
            invalidate()
        }

    private val migrationRectPx = 248.dp.toFloat()
    private val migrationSideInsetPx = 16.dp.toFloat()
    private val migrationCornerLenPx = 42.dp.toFloat()
    private val migrationCornerRadiusPx = 10.dp.toFloat()
    private val migrationCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = cornerThickPx
        color = ContextCompat.getColor(context, baseR.color.info)
    }
    private val strokeRect = RectF()

    
    private val scanLine: Drawable? = ContextCompat.getDrawable(context, R.drawable.scan_line)
    private val scanLineHalfPx = 4.dp.toFloat() 
    private var scanLineFraction = 0f 
    private val scanLineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L 
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            scanLineFraction = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (migrationStyle) {
            drawMigrationViewfinder(canvas)
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        val half = rectSizePx / 2f
        rect.set(cx - half, cy - half, cx + half, cy + half)

        
        
        
        
        val layer = canvas.saveLayer(null, null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRect(rect, clearPaint) 
        canvas.restoreToCount(layer)

        canvas.drawRect(rect, borderPaint) 
        drawCorners(canvas, rect) 
        drawScanLine(canvas, rect) 
    }

    private fun drawScanLine(canvas: Canvas, r: RectF) {
        val line = scanLine ?: return
        val y = r.top + scanLineFraction * r.height()
        
        val save = canvas.save()
        canvas.clipRect(r)
        line.setBounds(
            r.left.toInt(),
            (y - scanLineHalfPx).toInt(),
            r.right.toInt(),
            (y + scanLineHalfPx).toInt(),
        )
        line.draw(canvas)
        canvas.restoreToCount(save)
    }

    





    fun startScanLine() {
        if (migrationStyle) return
        if (!scanLineAnimator.isStarted) scanLineAnimator.start()
    }

    
    fun stopScanLine() {
        scanLineAnimator.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        scanLineAnimator.cancel() 
        super.onDetachedFromWindow()
    }

    



    private fun drawMigrationViewfinder(canvas: Canvas) {
        val size = migrationRectPx.coerceAtMost(width - 2 * migrationSideInsetPx)
        if (size <= 0f) return
        val half = size / 2f
        rect.set(width / 2f - half, height / 2f - half, width / 2f + half, height / 2f + half)
        val inset = cornerThickPx / 2f
        strokeRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        val len = migrationCornerLenPx.coerceAtMost(half)
        for ((x, y) in listOf(rect.left to rect.top, rect.right - len to rect.top, rect.left to rect.bottom - len, rect.right - len to rect.bottom - len)) {
            val save = canvas.save()
            canvas.clipRect(x, y, x + len, y + len)
            canvas.drawRoundRect(strokeRect, migrationCornerRadiusPx, migrationCornerRadiusPx, migrationCornerPaint)
            canvas.restoreToCount(save)
        }
    }

    private fun drawCorners(canvas: Canvas, r: RectF) {
        
        canvas.drawRect(r.left, r.top, r.left + cornerLenPx, r.top + cornerThickPx, cornerPaint)
        canvas.drawRect(r.left, r.top, r.left + cornerThickPx, r.top + cornerLenPx, cornerPaint)
        
        canvas.drawRect(r.right - cornerLenPx, r.top, r.right, r.top + cornerThickPx, cornerPaint)
        canvas.drawRect(r.right - cornerThickPx, r.top, r.right, r.top + cornerLenPx, cornerPaint)
        
        canvas.drawRect(r.left, r.bottom - cornerThickPx, r.left + cornerLenPx, r.bottom, cornerPaint)
        canvas.drawRect(r.left, r.bottom - cornerLenPx, r.left + cornerThickPx, r.bottom, cornerPaint)
        
        canvas.drawRect(r.right - cornerLenPx, r.bottom - cornerThickPx, r.right, r.bottom, cornerPaint)
        canvas.drawRect(r.right - cornerThickPx, r.bottom - cornerLenPx, r.right, r.bottom, cornerPaint)
    }

    private companion object {
        
        const val RECT_FRACTION_OF_SHORT_SIDE = 0.72f

        
        const val RECT_MAX_FRACTION_OF_VIEW = 0.9f
    }
}
