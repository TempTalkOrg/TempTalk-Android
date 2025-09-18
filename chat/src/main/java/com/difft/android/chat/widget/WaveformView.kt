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
        color = ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary_night) // 设置柱子的颜色为白色
    }

    private val amplitudes = mutableListOf<Float>()
    private var animationJob: Job? = null

    // 设定每根柱子的固定高度
    private val baseHeights = floatArrayOf(5.dp.toFloat(), 8.dp.toFloat(), 10.dp.toFloat())

    // 当前显示的柱子数量
    private var visibleBars = 1

    // 振幅最大值（用来归一化）
    private var maxAmplitude = 0f

    // 圆角半径
    private val cornerRadius = 3.dp.toFloat()

    fun setBarColor(color: Int) {
        paint.color = color
        invalidate() // 更新颜色后重绘视图
    }

    fun startAnimation() {
        animationJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                invalidate()
                delay(50) // 每 50ms 刷新一次
            }
        }
    }

    fun stopAnimation() {
        animationJob?.cancel()
        amplitudes.clear()
        invalidate()
    }

    fun updateAmplitude(amplitude: Float) {
        // 更新最大振幅值
        maxAmplitude = maxOf(maxAmplitude, amplitude)

        // 归一化振幅到 0 到 1 的范围
        val normalizedAmplitude = amplitude / maxAmplitude

        // 保持最多 20 个振幅值
        if (amplitudes.size >= 20) {
            amplitudes.removeAt(0)
        }
        amplitudes.add(normalizedAmplitude)

        // 根据归一化后的振幅动态调整柱子数量
        visibleBars = when {
            normalizedAmplitude < 0.3f -> 1 // 振幅较低时显示 1 根柱子
            normalizedAmplitude < 0.6f -> 2 // 中等振幅时显示 2 根柱子
            else -> 3 // 高振幅时显示 3 根柱子
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (amplitudes.isEmpty()) return

        val centerX = width / 2f // X 轴中心点
        val barWidth = 3.dp // 每根柱子的宽度

        // 绘制显示的柱子
        for (i in 0 until visibleBars) {
            val barHeight = baseHeights[i] // 固定柱子高度
            val xPosition = centerX + (i - 1) * (barWidth + 2.dp) // 将柱子稍微分开
            val yPosition = (this.height - barHeight) / 2f // 垂直居中

            // 绘制每根带圆角的柱子
            canvas.drawRoundRect(
                xPosition, yPosition,
                xPosition + barWidth, yPosition + barHeight,
                cornerRadius, cornerRadius, // 设置圆角半径
                paint
            )
        }
    }
}





