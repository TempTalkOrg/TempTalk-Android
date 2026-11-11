package com.difft.android.call.ui.video

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt


internal const val EDGE_SAMPLES = 8


private const val EDGE_SATURATION = 1.35f
private const val EDGE_BRIGHTNESS = 0.72f


private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f

private const val EDGE_SAMPLE_INTERVAL_MS = 500L


private const val EDGE_SAMPLE_MAX_WAITING = 2


private const val EDGE_SAMPLE_LONG_SIDE_PX = 32f
private const val EDGE_SAMPLE_FIRST_SCALE = 0.05f
private const val EDGE_SAMPLE_MIN_SCALE = 0.01f
private const val EDGE_SAMPLE_MAX_SCALE = 0.5f

private const val CHANNEL_MAX = 255
private const val OPAQUE = 0xFF shl 24







class EdgeColors internal constructor(
    val aspect: Float,
    val top: IntArray,
    val bottom: IntArray,
    val left: IntArray,
    val right: IntArray,
)


internal fun edgeColorsOf(pixels: IntArray, width: Int, height: Int): EdgeColors? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    fun row(y: Int) = IntArray(EDGE_SAMPLES) { i -> toned(average(sampleSpan(i, width)) { x -> pixels[y * width + x] }) }
    fun column(x: Int) = IntArray(EDGE_SAMPLES) { i -> toned(average(sampleSpan(i, height)) { y -> pixels[y * width + x] }) }
    return EdgeColors(width.toFloat() / height, row(0), row(height - 1), column(0), column(width - 1))
}

private fun edgeColorsOf(bitmap: Bitmap): EdgeColors? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return edgeColorsOf(pixels, bitmap.width, bitmap.height)
}


private fun sampleSpan(i: Int, length: Int): IntRange {
    val start = i * length / EDGE_SAMPLES
    return start until maxOf(start + 1, (i + 1) * length / EDGE_SAMPLES)
}

private inline fun average(span: IntRange, pixel: (Int) -> Int): Int {
    var r = 0
    var g = 0
    var b = 0
    for (i in span) {
        val p = pixel(i)
        r += p shr 16 and CHANNEL_MAX
        g += p shr 8 and CHANNEL_MAX
        b += p and CHANNEL_MAX
    }
    val n = span.last - span.first + 1
    return OPAQUE or (r / n shl 16) or (g / n shl 8) or (b / n)
}


internal fun toned(argb: Int): Int {
    val r = (argb shr 16 and CHANNEL_MAX).toFloat()
    val g = (argb shr 8 and CHANNEL_MAX).toFloat()
    val b = (argb and CHANNEL_MAX).toFloat()
    val luma = LUMA_R * r + LUMA_G * g + LUMA_B * b
    fun channel(v: Float) = ((luma + (v - luma) * EDGE_SATURATION) * EDGE_BRIGHTNESS).roundToInt().coerceIn(0, CHANNEL_MAX)
    return OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}


internal fun nextEdgeScale(scale: Float, longSidePx: Int): Float =
    (scale * EDGE_SAMPLE_LONG_SIDE_PX / longSidePx).coerceIn(EDGE_SAMPLE_MIN_SCALE, EDGE_SAMPLE_MAX_SCALE)







internal suspend fun sampleEdgeColors(
    renderer: TextureViewRenderer,
    shapeChanges: ReceiveChannel<Unit>,
    deliver: (EdgeColors) -> Unit,
) {
    val main = Handler(Looper.getMainLooper())
    val live = AtomicBoolean(true)
    var scale = EDGE_SAMPLE_FIRST_SCALE
    try {
        edgeSampleLoop(shapeChanges) { done ->
            renderer.addFrameListener({ bitmap ->
                val colors = bitmap?.let(::edgeColorsOf)
                val longSide = bitmap?.let { maxOf(it.width, it.height) } ?: 0
                bitmap?.recycle()
                main.post {
                    done()
                    if (live.get()) {
                        if (longSide > 0) scale = nextEdgeScale(scale, longSide)
                        colors?.let(deliver)
                    }
                }
            }, scale)
        }
    } finally {
        live.set(false)
    }
}






internal suspend fun edgeSampleLoop(shapeChanges: ReceiveChannel<Unit>, arm: (done: () -> Unit) -> Unit) {
    var waiting = 0
    var newShape = false
    while (true) {
        if (waiting == 0 || (newShape && waiting < EDGE_SAMPLE_MAX_WAITING)) {
            waiting++
            arm { waiting-- }
        }
        
        newShape = withTimeoutOrNull(EDGE_SAMPLE_INTERVAL_MS) {
            shapeChanges.receiveCatching().isSuccess || awaitCancellation()
        } == true
    }
}
