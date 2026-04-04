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

    private var progressColor: Int = ContextCompat.getColor(context, com.difft.android.base.R.color.icon) // 已播放部分颜色
    private var cursorColor: Int = ContextCompat.getColor(context, com.difft.android.base.R.color.icon) // 游标颜色

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

    private val barWidth = 2.dp // 每根竖线的宽度
    private val spacing = 3.dp // 竖线间距
    private val maxHeight = 40.dp // 最大竖线高度
    private val cursorRadius = 2.dp // 游标的半径

    private var amplitudes: List<Float> = emptyList()
    private var progress: Float = 0f // 当前进度 (0.0 - 1.0)

    private var isDragging = false

    private var barCount: Int = 0


    init {
//        isClickable = true
//        isFocusable = true
    }

    // 设置已播放部分颜色
    fun setProgressColor(color: Int) {
        progressColor = color
        progressPaint.color = progressColor
        invalidate()
    }

    // 设置游标颜色
    fun setCursorColor(color: Int) {
        cursorColor = color
        cursorPaint.color = cursorColor
        invalidate()
    }

    fun setAmplitudes(amplitudes: List<Float>) {
        post {
            if (barCount == 0) {
                barCount = calculateBarCount() // 确保 barCount 不为 0
            }
            if (amplitudes.isNotEmpty()) {
                this.amplitudes = reduceAmplitudes(amplitudes, barCount)
            }
//            L.d { "[AudioWaveProgressBar] amplitudes:${this.amplitudes} barCount:${barCount}" }
            invalidate()
        }
    }

    fun setProgress(progress: Float) {
        this.progress = progress.coerceIn(0f, 1f)
//        L.i { "=====111==progressBarIndex=======" + progress + "====" + this.progress }
        invalidate()
    }

    private fun calculateBarCount(): Int {
        val count = (width / (barWidth + spacing))
        return if (count > 0) count else 1 // 确保至少有 1 根条形
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (barCount == 0) return // 如果 barCount 仍为 0，直接返回

        // 根据进度计算当前进度对应的振幅条索引
        val progressBarIndex = (progress * barCount).toInt()

        // 偏移量，确保第一个振幅条和游标不会超出边界
        val startOffset = cursorRadius.toFloat()

        // 遍历每个振幅条
        for (i in 0 until barCount) {
            val amplitude = amplitudes.getOrNull(i) ?: 0f
            val barHeight = amplitude * maxHeight
            val left = startOffset + i * (barWidth + spacing)
            val top = height / 2 - barHeight / 2
            val right = left + barWidth
            val bottom = height / 2 + barHeight / 2

            if (i < progressBarIndex) {
                // 进度之前的振幅条绘制为已播放颜色
                canvas.drawRect(left, top, right, bottom, progressPaint)
            } else if (i == progressBarIndex) {
                // 当前进度对应的振幅条，用游标替换它
                val cursorX = ((left + right) / 2f).coerceIn(startOffset, (width - cursorRadius).toFloat())
                canvas.drawCircle(cursorX, height / 2f, cursorRadius.toFloat(), cursorPaint)
            } else {
                // 进度之后的振幅条绘制为默认颜色
                canvas.drawRect(left, top, right, bottom, barPaint)
            }
        }
    }


//    override fun onTouchEvent(event: MotionEvent): Boolean {
//        when (event.action) {
//            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
//                isDragging = true
//                progress = (event.x / width).coerceIn(0f, 1f)
//                invalidate()
//                return true
//            }
//
//            MotionEvent.ACTION_UP -> {
//                isDragging = false
//                performClick()
//                return true
//            }
//        }
//        return super.onTouchEvent(event)
//    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun getDefaultAmplitudes(barCount: Int): List<Float> {
        val random = java.util.Random()
        return List(barCount) { random.nextFloat() } // 默认生成barCount个随机振幅
    }

    private fun reduceAmplitudes(amplitudes: List<Float>, targetSize: Int): List<Float> {
        try {
            // 如果目标大小大于等于输入大小，直接返回输入数据
            if (targetSize >= amplitudes.size) {
                return amplitudes
            }

            // 按照 barCount 分段计算每段的平均值
            val chunkSize = amplitudes.size.toFloat() / targetSize
            val reducedAmplitudes = (0 until targetSize).map { index ->
                val start = (index * chunkSize).toInt()
                val end = if (index == targetSize - 1) amplitudes.size else ((index + 1) * chunkSize).toInt()
                val averageAmplitude = amplitudes.subList(start, end).average().toFloat()
                averageAmplitude
            }

            // 在渲染时进行归一化，确保显示效果的一致性
            val maxAmplitude = reducedAmplitudes.maxOrNull() ?: 1f
            return reducedAmplitudes.map { it / maxAmplitude }
        } catch (e: Exception) {
            L.e { "[AudioWaveProgressBar] reduceAmplitudes error:${e.stackTraceToString()}" }
            return getDefaultAmplitudes(targetSize)
        }
    }
}
