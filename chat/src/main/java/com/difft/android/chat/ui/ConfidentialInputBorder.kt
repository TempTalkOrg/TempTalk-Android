package com.difft.android.chat.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.difft.android.base.ui.theme.DifftTheme
import kotlin.math.min
import kotlin.math.roundToInt


internal class ConfidentialInputBorderDrawable(
    density: Float,
    color: Int,
    private val cornerRadiusPx: Float? = null,
) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val rect = RectF()
    private var radius = 0f

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        val outerRadius = min(cornerRadiusPx ?: Float.MAX_VALUE, min(rect.width(), rect.height()) / 2f)
        val inset = stroke.strokeWidth / 2f
        rect.inset(inset, inset)
        radius = (outerRadius - inset).coerceAtLeast(0f)
    }

    override fun draw(canvas: Canvas) {
        if (!rect.isEmpty) canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun View.setConfidentialInputBorder(enabled: Boolean, cornerRadiusPx: Float? = null) {
    foreground = if (enabled) {
        ConfidentialInputBorderDrawable(
            resources.displayMetrics.density,
            
            ContextCompat.getColor(context, com.difft.android.base.R.color.t_info),
            cornerRadiusPx,
        )
    } else {
        null
    }
}


@Composable
internal fun Modifier.confidentialInputBorder(enabled: Boolean, cornerRadius: Dp): Modifier {
    if (!enabled) return this
    val color = DifftTheme.colors.textInfo.toArgb()
    return drawWithCache {
        val border = ConfidentialInputBorderDrawable(density, color, cornerRadius.toPx()).apply {
            setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        }
        onDrawWithContent {
            drawContent()
            drawIntoCanvas { border.draw(it.nativeCanvas) }
        }
    }
}
