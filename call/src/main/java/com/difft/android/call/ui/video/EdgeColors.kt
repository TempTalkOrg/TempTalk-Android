package com.difft.android.call.ui.video

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.delay
import livekit.org.webrtc.EglRenderer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Colours sampled along each side of a frame for the ambient letterbox. */
internal const val EDGE_SAMPLES = 8

// The call-layout simulator's ambient treatment (`src/sim/letterbox.ts`): the picture's edge colours,
// more saturated and dimmed, spill into the bands.
private const val EDGE_SATURATION = 1.35f
private const val EDGE_BRIGHTNESS = 0.72f

// Rec. 709 luma weights, as CSS `saturate()` uses them.
private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f

private const val EDGE_SAMPLE_INTERVAL_MS = 500L

// The sampled frame's long side (px): enough for EDGE_SAMPLES colours a side, cheap to read back.
private const val EDGE_SAMPLE_LONG_SIDE_PX = 32f
private const val EDGE_SAMPLE_FIRST_SCALE = 0.05f
private const val EDGE_SAMPLE_MIN_SCALE = 0.01f
private const val EDGE_SAMPLE_MAX_SCALE = 0.5f

private const val CHANNEL_MAX = 255
private const val OPAQUE = 0xFF shl 24

/**
 * The colours along each side of a frame as it is drawn (rotated and mirrored like the picture), for
 * the ambient letterbox: [EDGE_SAMPLES] opaque ARGB values a side, top and bottom from left to right,
 * left and right from top to bottom, already saturated and dimmed. [aspect] is the frame's width over
 * its height. Public only because [VideoRenderer] is.
 */
class EdgeColors internal constructor(
    val aspect: Float,
    val top: IntArray,
    val bottom: IntArray,
    val left: IntArray,
    val right: IntArray,
)

/** [EdgeColors] of an ARGB image [width] × [height] stored row by row; null when it is empty. */
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

/** The pixels sample [i] averages along an edge [length] long; never empty, so a short edge repeats pixels. */
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

/** [argb] saturated by [EDGE_SATURATION] and dimmed to [EDGE_BRIGHTNESS], as the simulator's CSS filter does. */
internal fun toned(argb: Int): Int {
    val r = (argb shr 16 and CHANNEL_MAX).toFloat()
    val g = (argb shr 8 and CHANNEL_MAX).toFloat()
    val b = (argb and CHANNEL_MAX).toFloat()
    val luma = LUMA_R * r + LUMA_G * g + LUMA_B * b
    fun channel(v: Float) = ((luma + (v - luma) * EDGE_SATURATION) * EDGE_BRIGHTNESS).roundToInt().coerceIn(0, CHANNEL_MAX)
    return OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/** The next sampling scale, so the next frame read back is about [EDGE_SAMPLE_LONG_SIDE_PX] long. */
internal fun nextEdgeScale(scale: Float, longSidePx: Int): Float =
    (scale * EDGE_SAMPLE_LONG_SIDE_PX / longSidePx).coerceIn(EDGE_SAMPLE_MIN_SCALE, EDGE_SAMPLE_MAX_SCALE)

/**
 * Samples [EdgeColors] from [renderer] until cancelled; call on the main thread. Every
 * [EDGE_SAMPLE_INTERVAL_MS] a one-shot frame listener draws the next frame about
 * [EDGE_SAMPLE_LONG_SIDE_PX] px long and reads its border on the render thread; [deliver] gets the
 * colours on the main thread. At most one listener waits at a time, so a paused video arms no more.
 */
internal suspend fun sampleEdgeColors(renderer: TextureViewRenderer, deliver: (EdgeColors) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    val live = AtomicBoolean(true)
    var scale = EDGE_SAMPLE_FIRST_SCALE
    var pending = false
    try {
        while (true) {
            if (!pending) {
                pending = true
                renderer.addFrameListener({ bitmap ->
                    val colors = bitmap?.let(::edgeColorsOf)
                    val longSide = bitmap?.let { maxOf(it.width, it.height) } ?: 0
                    bitmap?.recycle()
                    main.post {
                        pending = false
                        if (live.get()) {
                            if (longSide > 0) scale = nextEdgeScale(scale, longSide)
                            colors?.let(deliver)
                        }
                    }
                }, scale)
            }
            delay(EDGE_SAMPLE_INTERVAL_MS)
        }
    } finally {
        live.set(false)
    }
}
