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

/**
 * Dimmed mask + centered scan rect + corner brackets — reproduces BGA's built-in qrcv_* visual.
 *
 * Decode analyzes the FULL frame (the old qrcv_isOnlyDecodeScanBoxArea=false), so this rect is a UI
 * affordance, NOT a decode boundary. Corner brackets + the moving scan line use the app primary
 * blue (baseR.color.primary, #056FFA — its values-night value is identical, so mode-identical);
 * scan_mask_color likewise has no night override.
 *
 * The [JvmOverloads] secondary constructors are MANDATORY: the view is inflated from
 * activity_scan.xml; without the (Context, AttributeSet) form, LayoutInflater throws InflateException
 * at runtime.
 */
class ScanFrameOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val rectSizePx = 200.dp.toFloat() // qrcv_rectWidth = 200dp
    private val cornerLenPx = 20.dp.toFloat() // qrcv_cornerLength = 20dp
    private val cornerThickPx = 3.dp.toFloat() // qrcv_cornerSize = 3dp
    private val borderPx = 1.dp.toFloat()

    // Paints created once, reused every onDraw (no per-frame allocation).
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scan_mask_color) // #33FFFFFF
    }

    // Dedicated immutable CLEAR paint — NOT a mutate-and-restore of maskPaint's xfermode. If drawRect
    // ever threw mid-frame, a mutated-then-not-restored maskPaint would stay in CLEAR mode and every
    // later frame would erase the saveLayer → overlay vanishes. Two stable Paints with fixed xfermodes
    // avoid that latent corruption entirely.
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
        color = ContextCompat.getColor(context, baseR.color.primary) // #056FFA primary blue
    }

    private val rect = RectF()

    // Moving scan line — replaces BGA's qrcv_customScanLineDrawable animation. Blue vector
    // (scan_line.xml, primary-blue gradient fading at both ends), swept top↔bottom inside the rect.
    private val scanLine: Drawable? = ContextCompat.getDrawable(context, R.drawable.scan_line)
    private val scanLineHalfPx = 4.dp.toFloat() // half the drawn line band height
    private var scanLineFraction = 0f // 0 = rect top, 1 = rect bottom
    private val scanLineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L // one sweep; REVERSE makes it travel back up (old qrcv_isScanLineReverse=true)
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
        val cx = width / 2f
        val cy = height / 2f
        val half = rectSizePx / 2f
        rect.set(cx - half, cy - half, cx + half, cy + half)

        // Dimmed mask everywhere EXCEPT the rect: draw the full mask on an offscreen layer, then CLEAR
        // the rect out. saveLayer/restore brackets the layer so CLEAR doesn't punch the window. Uses
        // two stable Paints (maskPaint always SRC_OVER, clearPaint always CLEAR) — no per-frame
        // xfermode mutate-and-restore, so a mid-frame throw can't leave maskPaint stuck in CLEAR.
        val layer = canvas.saveLayer(null, null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRect(rect, clearPaint) // always CLEAR; no mutation, no restore
        canvas.restoreToCount(layer)

        canvas.drawRect(rect, borderPaint) // 1dp white border
        drawCorners(canvas, rect) // 4 L-shaped brackets
        drawScanLine(canvas, rect) // moving blue scan line
    }

    private fun drawScanLine(canvas: Canvas, r: RectF) {
        val line = scanLine ?: return
        val y = r.top + scanLineFraction * r.height()
        // Clip to the rect so the band never bleeds past the frame at the travel extremes.
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

    /**
     * Starts the scan-line sweep. Driven by the camera lifecycle (called only on the
     * permission-granted → camera-start path), NOT by [onAttachedToWindow]: the view is attached
     * before permission resolves, so auto-starting here would burn CPU on permission-denied / finish
     * paths where scanning never begins.
     */
    fun startScanLine() {
        if (!scanLineAnimator.isStarted) scanLineAnimator.start()
    }

    /** Stops the scan-line sweep. Called from the Activity's onStop / shutdown path. */
    fun stopScanLine() {
        scanLineAnimator.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        scanLineAnimator.cancel() // safety net: ensure the infinite animator never outlives the view
        super.onDetachedFromWindow()
    }

    private fun drawCorners(canvas: Canvas, r: RectF) {
        // top-left
        canvas.drawRect(r.left, r.top, r.left + cornerLenPx, r.top + cornerThickPx, cornerPaint)
        canvas.drawRect(r.left, r.top, r.left + cornerThickPx, r.top + cornerLenPx, cornerPaint)
        // top-right
        canvas.drawRect(r.right - cornerLenPx, r.top, r.right, r.top + cornerThickPx, cornerPaint)
        canvas.drawRect(r.right - cornerThickPx, r.top, r.right, r.top + cornerLenPx, cornerPaint)
        // bottom-left
        canvas.drawRect(r.left, r.bottom - cornerThickPx, r.left + cornerLenPx, r.bottom, cornerPaint)
        canvas.drawRect(r.left, r.bottom - cornerLenPx, r.left + cornerThickPx, r.bottom, cornerPaint)
        // bottom-right
        canvas.drawRect(r.right - cornerLenPx, r.bottom - cornerThickPx, r.right, r.bottom, cornerPaint)
        canvas.drawRect(r.right - cornerThickPx, r.bottom - cornerLenPx, r.right, r.bottom, cornerPaint)
    }
}
