package com.difft.android.chat.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.dp

class AudioWaveProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progressColor: Int = ContextCompat.getColor(context, com.difft.android.base.R.color.icon)
    private var cursorColor: Int = ContextCompat.getColor(context, com.difft.android.base.R.color.icon)

    private val barPaint = Paint().apply {
        color = ContextCompat.getColor(context, com.difft.android.base.R.color.t_disable)
        style = Paint.Style.FILL
    }

    private val progressPaint = Paint().apply {
        color = progressColor
        style = Paint.Style.FILL
    }

    private val cursorPaint = Paint().apply {
        color = cursorColor
        style = Paint.Style.FILL
    }

    private val barWidth = 2.dp
    private val spacing = 3.dp
    private val maxHeight = 40.dp
    private val cursorRadius = 2.dp

    private var amplitudes: List<Float> = emptyList()
    private var progress: Float = 0f 

    private var isDragging = false

    private var barCount: Int = 0


    init {

    }

    
    fun setTrackColor(color: Int) {
        barPaint.color = color
        invalidate()
    }

    fun setProgressColor(color: Int) {
        progressColor = color
        progressPaint.color = progressColor
        invalidate()
    }

    fun setCursorColor(color: Int) {
        cursorColor = color
        cursorPaint.color = cursorColor
        invalidate()
    }

    fun setAmplitudes(amplitudes: List<Float>) {
        post {
            if (barCount == 0) {
                barCount = calculateBarCount()
            }
            if (amplitudes.isNotEmpty()) {
                this.amplitudes = reduceAmplitudes(amplitudes, barCount)
            }
            invalidate()
        }
    }

    fun setProgress(progress: Float) {
        this.progress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    private fun calculateBarCount(): Int {
        val count = (width / (barWidth + spacing))
        return if (count > 0) count else 1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (barCount == 0) return

        val progressBarIndex = (progress * barCount).toInt()

        
        val startOffset = cursorRadius.toFloat()

        for (i in 0 until barCount) {
            val amplitude = amplitudes.getOrNull(i) ?: 0f
            val barHeight = amplitude * maxHeight
            val left = startOffset + i * (barWidth + spacing)
            val top = height / 2 - barHeight / 2
            val right = left + barWidth
            val bottom = height / 2 + barHeight / 2

            if (i < progressBarIndex) {
                canvas.drawRect(left, top, right, bottom, progressPaint)
            } else if (i == progressBarIndex) {
                val cursorX = ((left + right) / 2f).coerceIn(startOffset, (width - cursorRadius).toFloat())
                canvas.drawCircle(cursorX, height / 2f, cursorRadius.toFloat(), cursorPaint)
            } else {
                canvas.drawRect(left, top, right, bottom, barPaint)
            }
        }
    }



    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun getDefaultAmplitudes(barCount: Int): List<Float> {
        val random = java.util.Random()
        return List(barCount) { random.nextFloat() } 
    }

    private fun reduceAmplitudes(amplitudes: List<Float>, targetSize: Int): List<Float> {
        try {
            if (targetSize >= amplitudes.size) {
                return amplitudes
            }

            val chunkSize = amplitudes.size.toFloat() / targetSize
            val reducedAmplitudes = (0 until targetSize).map { index ->
                val start = (index * chunkSize).toInt()
                val end = if (index == targetSize - 1) amplitudes.size else ((index + 1) * chunkSize).toInt()
                val averageAmplitude = amplitudes.subList(start, end).average().toFloat()
                averageAmplitude
            }

            
            val maxAmplitude = reducedAmplitudes.maxOrNull() ?: 1f
            return reducedAmplitudes.map { it / maxAmplitude }
        } catch (e: Exception) {
            L.e { "[AudioWaveProgressBar] reduceAmplitudes error:${e.stackTraceToString()}" }
            return getDefaultAmplitudes(targetSize)
        }
    }
}
