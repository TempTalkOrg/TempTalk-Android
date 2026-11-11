package com.difft.android.chat.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.R
import com.difft.android.chat.ui.theme.ChatTheme
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch












class ChatWallpaperPainter(context: Context, val theme: ChatTheme) {

    private val appContext = context.applicationContext
    private val configuration = Configuration(context.resources.configuration)
    private val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val lagoon = theme == ChatTheme.GRADIENT && !night

    private val baseColor = color(context, if (lagoon) R.color.chat_wallpaper_lagoon_base else R.color.chat_wallpaper_base)
    private val ink = if (lagoon) null else intArrayOf(
        color(context, R.color.chat_wallpaper_ink_purple),
        color(context, R.color.chat_wallpaper_ink_blue),
        color(context, R.color.chat_wallpaper_ink_teal),
    )
    private val lagoonCorners = if (!lagoon) null else intArrayOf(
        color(context, R.color.chat_wallpaper_lagoon_top_start),
        color(context, R.color.chat_wallpaper_lagoon_top_end),
        color(context, R.color.chat_wallpaper_lagoon_bottom_end),
        color(context, R.color.chat_wallpaper_lagoon_bottom_start),
    )
    private val patternAlpha = when {
        lagoon -> LAGOON_PATTERN_ALPHA
        night && theme == ChatTheme.GRADIENT -> GRADIENT_DARK_PATTERN_ALPHA
        night -> DARK_PATTERN_ALPHA
        else -> LIGHT_PATTERN_ALPHA
    }

    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = baseColor }
    private val patternPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (lagoon) color = ContextCompat.getColor(context, R.color.chat_wallpaper_lagoon_ink)
    }
    private var drawableAlpha = OPAQUE

    private var frameWidth = 0
    private var frameHeight = 0
    private var maskInUse: Bitmap? = null

    
    
    private var renderedWidth = 0
    private var renderedHeight = 0
    private var baseLayer: Layer? = null
    private var inkLayer: Layer? = null
    private var maskShader: BitmapShader? = null
    private val stretchMatrix = Matrix()

    
    private class Layer(val shader: Shader, val sourceWidth: Int, val sourceHeight: Int)

    
    var onPatternReady: (() -> Unit)? = null

    var alpha: Int
        get() = drawableAlpha
        set(value) {
            drawableAlpha = value
            basePaint.alpha = value
            applyPatternAlpha()
        }

    var colorFilter: ColorFilter?
        get() = basePaint.colorFilter
        set(value) {
            basePaint.colorFilter = value
            patternPaint.colorFilter = value
        }

    val isStateful: Boolean get() = false

    val opacity: Int get() = if (drawableAlpha == OPAQUE) android.graphics.PixelFormat.OPAQUE else android.graphics.PixelFormat.TRANSLUCENT

    init {
        applyPatternAlpha()
    }

    
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val width = (right - left).roundToInt()
        val height = (bottom - top).roundToInt()
        if (width <= 0 || height <= 0) return
        val mask = ChatPatternMask.get(appContext, configuration, this)
        if (width != frameWidth || height != frameHeight || mask !== maskInUse) {
            prepare(width, height, mask)
        }
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
        if (mask != null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), patternPaint)
        canvas.restore()
    }

    internal fun maskReady() {
        onPatternReady?.invoke()
    }

    private fun prepare(width: Int, height: Int, mask: Bitmap?) {
        frameWidth = width
        frameHeight = height
        if (needsRender(width, height)) render(width, height)
        baseLayer?.let { stretch(it, width, height) }
        inkLayer?.let { stretch(it, width, height) }
        basePaint.shader = baseLayer?.shader
        if (mask !== maskInUse) {
            maskInUse = mask
            maskShader = mask?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        }
        val maskShader = maskShader
        val inkShader = inkLayer?.shader
        patternPaint.shader = when {
            maskShader == null -> null
            inkShader == null -> maskShader
            else -> ComposeShader(maskShader, inkShader, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun needsRender(width: Int, height: Int): Boolean = needsRender(renderedWidth, renderedHeight, width, height)

    private fun render(width: Int, height: Int) {
        renderedWidth = width
        renderedHeight = height
        baseLayer = lagoonCorners?.let { corners ->
            bitmapLayer(renderSmall(width, height) { canvas -> drawLagoon(canvas, width, height, baseColor, corners) })
        }
        inkLayer = ink?.let { colors ->
            
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bitmapLayer(renderSmall(width, height) { canvas -> drawInk(canvas, width, height, colors) })
            } else {
                Layer(linear135(width.toFloat(), height.toFloat(), colors), width, height)
            }
        }
    }

    
    private fun stretch(layer: Layer, width: Int, height: Int) {
        stretchMatrix.setScale(width / layer.sourceWidth.toFloat(), height / layer.sourceHeight.toFloat())
        layer.shader.setLocalMatrix(stretchMatrix)
    }

    private fun applyPatternAlpha() {
        val alpha = (patternAlpha * drawableAlpha).roundToInt()
        if (lagoon) {
            patternPaint.color = ColorUtils.setAlphaComponent(patternPaint.color, alpha)
        } else {
            patternPaint.alpha = alpha
        }
    }

    internal companion object {
        
        const val INK_SCALE = 8

        
        private const val RERENDER_RATIO = 0.2f
        private const val OPAQUE = 255
        private const val LIGHT_PATTERN_ALPHA = 0.20f
        private const val DARK_PATTERN_ALPHA = 0.25f
        private const val GRADIENT_DARK_PATTERN_ALPHA = 0.38f
        private const val LAGOON_PATTERN_ALPHA = 0.11f

        private fun color(context: Context, id: Int) = ContextCompat.getColor(context, id)

        private inline fun renderSmall(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap {
            val bitmap = createBitmap(max(1, ceil(width / INK_SCALE.toFloat()).toInt()), max(1, ceil(height / INK_SCALE.toFloat()).toInt()))
            val canvas = Canvas(bitmap)
            canvas.scale(bitmap.width / width.toFloat(), bitmap.height / height.toFloat())
            draw(canvas)
            return bitmap
        }

        
        fun needsRender(renderedWidth: Int, renderedHeight: Int, width: Int, height: Int): Boolean =
            renderedWidth == 0 ||
                abs(width - renderedWidth) > renderedWidth * RERENDER_RATIO ||
                abs(height - renderedHeight) > renderedHeight * RERENDER_RATIO

        



        fun veiledInk(context: Context, width: Int, height: Int, veil: Int): Bitmap {
            val colors = intArrayOf(
                color(context, R.color.chat_wallpaper_ink_purple),
                color(context, R.color.chat_wallpaper_ink_blue),
                color(context, R.color.chat_wallpaper_ink_teal),
            )
            return renderSmall(width, height) { canvas ->
                drawInk(canvas, width, height, colors)
                canvas.drawColor(veil)
            }
        }

        private fun bitmapLayer(bitmap: Bitmap) =
            Layer(BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), bitmap.width, bitmap.height)

        
        fun linear135(width: Float, height: Float, colors: IntArray): LinearGradient {
            val angle = Math.toRadians(135.0)
            val dx = sin(angle).toFloat()
            val dy = -cos(angle).toFloat()
            val half = (abs(width * dx) + abs(height * dy)) / 2
            val cx = width / 2
            val cy = height / 2
            return LinearGradient(
                cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                colors, floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }

        
        private fun drawInk(canvas: Canvas, width: Int, height: Int, colors: IntArray) {
            val (purple, _, teal) = colors.toList()
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, Paint().apply { shader = linear135(w, h, colors) })
            ellipse(canvas, w, h, 0.06f, 0.96f, 0.36f, 0.30f, teal, solidUntil = 0f, fadeAt = 0.80f)
            ellipse(canvas, w, h, 0.92f, 0.08f, 0.36f, 0.30f, teal, solidUntil = 0f, fadeAt = 0.80f)
            ellipse(canvas, w, h, 0.98f, 0.95f, 0.50f, 0.42f, purple, solidUntil = 0.30f, fadeAt = 0.78f)
            ellipse(canvas, w, h, 0.55f, 0.52f, 0.46f, 0.38f, teal, solidUntil = 0.30f, fadeAt = 0.78f)
            ellipse(canvas, w, h, 0.10f, 0.12f, 0.48f, 0.40f, purple, solidUntil = 0.30f, fadeAt = 0.78f)
        }

        
        private fun drawLagoon(canvas: Canvas, width: Int, height: Int, base: Int, corners: IntArray) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(base)
            circle(canvas, w, h, 0f, 0.88f, corners[3], fadeAt = 0.60f)
            circle(canvas, w, h, 1f, 1f, corners[2], fadeAt = 0.75f)
            circle(canvas, w, h, 1f, 0.18f, corners[1], fadeAt = 0.60f)
            circle(canvas, w, h, 0f, 0f, corners[0], fadeAt = 0.65f)
        }

        
        @Suppress("LongParameterList")
        private fun ellipse(canvas: Canvas, w: Float, h: Float, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, solidUntil: Float, fadeAt: Float) {
            val clear = ColorUtils.setAlphaComponent(color, 0)
            val shader = RadialGradient(0f, 0f, 1f, intArrayOf(color, color, clear), floatArrayOf(0f, solidUntil, fadeAt), Shader.TileMode.CLAMP)
            canvas.save()
            canvas.translate(cx * w, cy * h)
            canvas.scale(rx * w, ry * h)
            val reach = max(w / (rx * w), h / (ry * h)) * 2
            canvas.drawRect(-reach, -reach, reach, reach, Paint().apply { this.shader = shader })
            canvas.restore()
        }

        private fun circle(canvas: Canvas, w: Float, h: Float, cx: Float, cy: Float, color: Int, fadeAt: Float) {
            val x = cx * w
            val y = cy * h
            val radius = max(max(hypot(x, y), hypot(w - x, y)), max(hypot(x, h - y), hypot(w - x, h - y)))
            val clear = ColorUtils.setAlphaComponent(color, 0)
            val shader = RadialGradient(x, y, radius, intArrayOf(color, clear), floatArrayOf(0f, fadeAt), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, Paint().apply { this.shader = shader })
        }
    }
}


fun prewarmChatWallpaper(context: Context) = ChatPatternMask.prewarm(context)





internal object ChatPatternMask {
    
    private val masks = ConcurrentHashMap<Int, Bitmap>()
    private val loading = HashSet<Int>()
    private val waiting: MutableSet<ChatWallpaperPainter> = Collections.newSetFromMap(WeakHashMap())
    private val main = Handler(Looper.getMainLooper())

    fun get(appContext: Context, configuration: Configuration, painter: ChatWallpaperPainter?): Bitmap? {
        val densityDpi = configuration.densityDpi
        masks[densityDpi]?.let { return it }
        synchronized(this) {
            painter?.let(waiting::add)
            if (!loading.add(densityDpi)) return null
        }
        val inflater = appContext.createConfigurationContext(configuration)
        appScope.launch(Dispatchers.Default) {
            val bitmap = try {
                render(inflater)
            } catch (e: Exception) {
                L.e { "[ChatWallpaper] mask render failed densityDpi=$densityDpi: ${e.stackTraceToString()}" }
                synchronized(this@ChatPatternMask) { loading.remove(densityDpi) }
                return@launch
            }
            main.post { publish(densityDpi, bitmap) }
        }
        return null
    }

    
    fun prewarm(context: Context) {
        get(context.applicationContext, Configuration(context.resources.configuration), painter = null)
    }

    private fun render(context: Context): Bitmap {
        val drawable = checkNotNull(ContextCompat.getDrawable(context, R.drawable.chat_pattern_hand)).mutate()
        val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ALPHA_8)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun publish(densityDpi: Int, bitmap: Bitmap) {
        val ready = synchronized(this) {
            loading.remove(densityDpi)
            masks[densityDpi] = bitmap
            waiting.toList().also { waiting.clear() }
        }
        ready.forEach { it.maskReady() }
    }
}
