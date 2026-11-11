package com.difft.android.base.widget

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnAttach
import androidx.compose.ui.graphics.toArgb
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.glass.GlassSurfaceBinding
import com.difft.android.base.ui.glass.GlassTuning
import com.difft.android.base.ui.theme.tokens.ColorTokens
import kotlin.math.min














class FloatingSurfaceDrawable(
    context: Context,
    private val cornerRadiusPx: Float?,
    private val maxCircleDiameterPx: Float = Float.MAX_VALUE,
    fillColor: Int? = null,
    private val glassBinding: GlassSurfaceBinding? = null,
    private val solidFill: Boolean = false,
    ownShadow: Boolean = false,
    private val fillGradient: IntArray? = null,
) : Drawable() {

    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val density = context.resources.displayMetrics.density
    private val lineColor = ContextCompat.getColor(context, R.color.line)
    private val highlightColor = WHITE
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor ?: ContextCompat.getColor(context, if (dark) R.color.bg3 else R.color.bg1)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = HAIRLINE_DP * density
    }
    private val shapeRect = RectF()
    private val edgeRect = RectF()
    private var radius = 0f
    private val topRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(WHITE, TOP_RIM_ALPHA) }
    private val bottomRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(WHITE, BOTTOM_RIM_ALPHA) }
    private val topRim = Path()
    private val bottomRim = Path()
    private val glassClip = Path()
    private var drawableAlpha = 255
    private val shadow = if (ownShadow && !dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SHADOW_COLOR
            setShadowLayer(FloatingShadow.BLUR_DP * density, 0f, FloatingShadow.DY_DP * density, SHADOW_COLOR)
        }
    } else null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (cornerRadiusPx == null) {
            val d = min(min(bounds.width(), bounds.height()).toFloat(), maxCircleDiameterPx)
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            shapeRect.set(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2)
            radius = d / 2
        } else {
            shapeRect.set(bounds)
            radius = min(cornerRadiusPx, min(bounds.width(), bounds.height()) / 2f)
        }
        fillGradient?.let { colors ->
            fill.shader = LinearGradient(shapeRect.left, shapeRect.top, shapeRect.right, shapeRect.bottom, colors, null, Shader.TileMode.CLAMP)
        }
        val inset = edge.strokeWidth / 2
        glassClip.reset()
        glassClip.addRoundRect(shapeRect, radius, radius, Path.Direction.CW)
        edge.shader = LinearGradient(
            0f, shapeRect.top, 0f, shapeRect.bottom,
            intArrayOf(highlightColor, lineColor, lineColor), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        edgeRect.set(shapeRect.left + inset, shapeRect.top + inset, shapeRect.right - inset, shapeRect.bottom - inset)
        if (dark) {
            rim(topRim, TOP_RIM_DP * density)
            rim(bottomRim, -BOTTOM_RIM_DP * density)
        }
    }

    
    private fun rim(into: Path, dy: Float) {
        into.reset()
        into.addRoundRect(shapeRect, radius, radius, Path.Direction.CW)
        val shifted = Path().apply {
            addRoundRect(RectF(shapeRect).apply { offset(0f, dy) }, radius, radius, Path.Direction.CW)
        }
        into.op(shifted, Path.Op.DIFFERENCE)
    }

    override fun draw(canvas: Canvas) {
        if (shapeRect.isEmpty) return
        shadow?.let { paint ->
            
            val save = canvas.save()
            canvas.clipOutPath(glassClip)
            canvas.drawPath(glassClip, paint)
            canvas.restoreToCount(save)
        }
        var hasGlass = false
        glassBinding?.let { binding ->
            val save = canvas.save()
            canvas.clipPath(glassClip)
            val alphaSave = if (drawableAlpha < 255) {
                canvas.saveLayerAlpha(shapeRect, drawableAlpha)
            } else {
                canvas.save()
            }
            hasGlass = binding.draw(canvas, shapeRect)
            canvas.restoreToCount(alphaSave)
            canvas.restoreToCount(save)
        }
        val fillAlpha = fill.alpha
        if (hasGlass) fill.alpha = (fillAlpha * GlassTuning.TINT_ALPHA).toInt()
        canvas.drawRoundRect(shapeRect, radius, radius, fill)
        fill.alpha = fillAlpha
        if (dark) {
            canvas.drawPath(topRim, topRimPaint)
            canvas.drawPath(bottomRim, bottomRimPaint)
        } else if (!solidFill) {
            
            canvas.drawRoundRect(edgeRect, radius, radius, edge)
        }
    }

    override fun getOutline(outline: Outline) {
        val r = Rect()
        shapeRect.roundOut(r)
        outline.setRoundRect(r, radius)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        fill.alpha = alpha
        edge.alpha = alpha
        shadow?.let { paint ->
            val color = ColorUtils.setAlphaComponent(SHADOW_COLOR, FloatingShadow.ALPHA * alpha / 255)
            paint.color = color
            paint.setShadowLayer(FloatingShadow.BLUR_DP * density, 0f, FloatingShadow.DY_DP * density, color)
        }
        topRimPaint.alpha = alpha * TOP_RIM_ALPHA / 255
        bottomRimPaint.alpha = alpha * BOTTOM_RIM_ALPHA / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        edge.colorFilter = colorFilter
        topRimPaint.colorFilter = colorFilter
        bottomRimPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val HAIRLINE_DP = 0.5f
        
        const val TOP_RIM_ALPHA = 41
        const val BOTTOM_RIM_ALPHA = 20
        const val TOP_RIM_DP = 1f
        const val BOTTOM_RIM_DP = 0.67f
        const val SHADOW_COLOR = FloatingShadow.ALPHA shl 24
    }
}


fun View.clearFloatingSurface() {
    (getTag(R.id.glass_surface_binding) as? GlassSurfaceBinding)?.close()
    setTag(R.id.glass_surface_binding, null)
    background = null
    foreground = null
    elevation = 0f
    outlineProvider = ViewOutlineProvider.BACKGROUND
}







fun View.applyFloatingSurface(
    cornerRadiusPx: Float? = null,
    maxCircleDiameterDp: Float = DEFAULT_CIRCLE_DP,
    fillColor: Int? = null,
    pressable: Boolean = false,
    elevationDp: Float = ELEVATION_DP,
    glass: Boolean = false,
    solidFill: Boolean = false,
    ownShadow: Boolean = true,
    clipsContent: Boolean = false,
    fillGradient: IntArray? = null,
) {
    val density = resources.displayMetrics.density
    val paintsShadow = ownShadow && elevationDp > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    (getTag(R.id.glass_surface_binding) as? GlassSurfaceBinding)?.close()
    val binding = if (glass && GlassMode.isLive(context)) GlassSurfaceBinding(this) else null
    setTag(R.id.glass_surface_binding, binding)
    fun surface(glassSurface: Boolean = true) = FloatingSurfaceDrawable(
        context, cornerRadiusPx, maxCircleDiameterDp * density, fillColor,
        if (glassSurface) binding else null, solidFill, ownShadow = paintsShadow && glassSurface && !clipsContent,
        fillGradient = fillGradient,
    )
    
    
    
    background = surface()
    foreground = if (pressable) {
        RippleDrawable(ColorStateList.valueOf(rippleColor().toArgb()), null, surface(glassSurface = false))
    } else {
        null
    }
    outlineProvider = ViewOutlineProvider.BACKGROUND
    elevation = elevationDp * density
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        
        val shadow = ColorUtils.setAlphaComponent(0, if (dark || paintsShadow) 0 else LIGHT_SHADOW_ALPHA)
        outlineAmbientShadowColor = shadow
        outlineSpotShadowColor = shadow
    }
    
    if (paintsShadow) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (clipsContent) {
            
            if (!dark) FloatingOverlayShadow.install(this, cornerRadiusPx ?: 0f)
        } else {
            val host = parent as? ViewGroup
            if (host != null) host.clipChildren = false else doOnAttach { (it.parent as? ViewGroup)?.clipChildren = false }
        }
    }
}






fun View.applyFloatingCardShadow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    outlineAmbientShadowColor = 0
    outlineSpotShadowColor = 0
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    if (!dark) FloatingOverlayShadow.install(this, 0f)
}


fun View.applyGlassSurface(
    cornerRadiusPx: Float? = null,
    maxCircleDiameterDp: Float = DEFAULT_CIRCLE_DP,
    fillColor: Int? = null,
    pressable: Boolean = false,
    elevationDp: Float = ELEVATION_DP,
) = applyFloatingSurface(cornerRadiusPx, maxCircleDiameterDp, fillColor, pressable, elevationDp, glass = true)

private fun View.rippleColor() =
    if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
        ColorTokens.Dark.Ripple
    } else {
        ColorTokens.Light.Ripple
    }

private const val DEFAULT_CIRCLE_DP = 40f

private const val ELEVATION_DP = 6f
private const val LIGHT_SHADOW_ALPHA = 64 
