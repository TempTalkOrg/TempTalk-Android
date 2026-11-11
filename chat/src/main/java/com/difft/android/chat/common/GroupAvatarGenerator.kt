package com.difft.android.chat.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import kotlin.math.cos
import kotlin.math.sin

object GroupAvatarGenerator {

    





    fun generate(
        items: List<LetterItem>,
        backgroundColor: Int,
        sizePx: Int = 512
    ): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val centerX = sizePx / 2f
        val centerY = sizePx / 2f
        val padding = sizePx * 0.04f

        canvas.drawColor(Color.TRANSPARENT)
        paint.color = backgroundColor
        canvas.drawCircle(centerX, centerY, sizePx / 2f, paint)

        val filteredItems = items
            .filter { it.char.isLetterOrDigit() || it.char.code >= 0x4E00 }
            .take(6)

        val count = filteredItems.size

        val circleRadius = when (count) {
            1 -> sizePx * 0.38f
            2 -> sizePx * 0.24f
            3 -> sizePx * 0.22f
            4 -> sizePx * 0.20f
            5 -> sizePx * 0.18f
            else -> sizePx * 0.16f 
        }

        val layoutRadius = (sizePx / 2f) - padding - circleRadius

        val positions = when (count) {
            1 -> listOf(centerX to centerY)
            2 -> listOf(
                centerX - layoutRadius * 0.85f to centerY,
                centerX + layoutRadius * 0.85f to centerY
            )

            else -> {
                val angleStep = 360f / count
                (0 until count).map { i ->
                    val angle = Math.toRadians(i * angleStep - 90.0)
                    val x = centerX + layoutRadius * cos(angle)
                    val y = centerY + layoutRadius * sin(angle)
                    x.toFloat() to y.toFloat()
                }
            }
        }

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = circleRadius * 0.9f
        val textYOffset = (paint.descent() + paint.ascent()) / 2f

        filteredItems.forEachIndexed { index, item ->
            val (cx, cy) = positions[index]

            paint.color = item.color
            canvas.drawCircle(cx, cy, circleRadius, paint)

            paint.color = Color.WHITE
            canvas.drawText(item.char.uppercaseChar().toString(), cx, cy - textYOffset, paint)
        }

        return bitmap
    }
}

data class LetterItem(
    val char: Char,
    val color: Int
)
