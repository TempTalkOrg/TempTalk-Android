package com.difft.android.chat.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.utils.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, com.difft.android.base.R.color.on_primary)
    }

    private val amplitudes = mutableListOf<Float>()
    private var animationJob: Job? = null

    private val baseHeights = floatArrayOf(5.dp.toFloat(), 8.dp.toFloat(), 10.dp.toFloat())

    private var visibleBars = 1

    private var maxAmplitude = 0f

    private val cornerRadius = 3.dp.toFloat()

    fun setBarColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun startAnimation() {
        animationJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                invalidate()
                delay(50)
            }
        }
    }

    fun stopAnimation() {
        animationJob?.cancel()
        amplitudes.clear()
        invalidate()
    }

    fun updateAmplitude(amplitude: Float) {
        maxAmplitude = maxOf(maxAmplitude, amplitude)

        val normalizedAmplitude = amplitude / maxAmplitude

        if (amplitudes.size >= 20) {
            amplitudes.removeAt(0)
        }
        amplitudes.add(normalizedAmplitude)

        visibleBars = when {
            normalizedAmplitude < 0.3f -> 1
            normalizedAmplitude < 0.6f -> 2
            else -> 3
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (amplitudes.isEmpty()) return

        val centerX = width / 2f
        val barWidth = 3.dp

        for (i in 0 until visibleBars) {
            val barHeight = baseHeights[i]
            val xPosition = centerX + (i - 1) * (barWidth + 2.dp)
            val yPosition = (this.height - barHeight) / 2f

            canvas.drawRoundRect(
                xPosition, yPosition,
                xPosition + barWidth, yPosition + barHeight,
                cornerRadius, cornerRadius,
                paint
            )
        }
    }
}





