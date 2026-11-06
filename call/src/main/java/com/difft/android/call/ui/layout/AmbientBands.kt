package com.difft.android.call.ui.layout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.layout.ContentMode
import com.difft.android.call.layout.LayoutInput
import com.difft.android.call.layout.Tile
import com.difft.android.call.layout.TileRole
import com.difft.android.call.layout.fitRect
import com.difft.android.call.layout.oneOnOneMedia
import com.difft.android.call.ui.video.EdgeColors
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Rect as PxRect
import com.difft.android.call.layout.Rect as LayoutRect

/** Bands thinner than this (dp a side) keep the plain background and sample nothing. */
internal const val AMBIENT_MIN_BAND_DP = 4.0

/** The ambient letterbox fades in and out over this. */
internal const val AMBIENT_FADE_MS = 200

// Between two samples the colours ease over this.
private const val AMBIENT_EASE_MS = 300

// Each band fades into the background towards its outer side, as the simulator's mask does: the
// background covers nothing at the picture, 55% halfway and all of the outer edge.
private const val FADE_HALFWAY = 0.55f
private const val HALFWAY = 0.5f

// Bands thinner than this (px) are not drawn.
private const val MIN_BAND_PX = 1f

/**
 * The thickest letterbox band (dp) of a [tile] shown whole: around its centred video inside a grid
 * card, the solo view or a 1on1 floating window widened past its video (call-layout decision 41), or,
 * for the 1on1 main view, whose card is the video itself, around the card in the area the main video
 * fits into. 0 for a filled or avatar tile, or a video of unknown shape.
 */
internal fun ambientBandOf(tile: Tile, input: LayoutInput?): Double {
    if (input == null || tile.contentMode != ContentMode.FIT) return 0.0
    return when (tile.role) {
        TileRole.MAIN -> bandAround(oneOnOneMedia(input), tile.rect)
        TileRole.GRID, TileRole.SOLO, TileRole.PIP -> {
            val aspect = input.participants.firstOrNull { it.id == tile.id }?.videoAspect ?: return 0.0
            if (aspect > 0) bandAround(tile.rect, fitRect(aspect, tile.rect)) else 0.0
        }
        else -> 0.0
    }
}

private fun bandAround(box: LayoutRect, picture: LayoutRect): Double = maxOf(
    maxOf(picture.x - box.x, box.x + box.w - picture.x - picture.w),
    maxOf(picture.y - box.y, box.y + box.h - picture.y - picture.h),
    0.0,
)

/** One tile's latest edge colours: written while its renderer samples, read where its bands are drawn. */
@Stable
internal class AmbientSlot {
    var colors: EdgeColors? by mutableStateOf(null)
}

internal enum class BandSide { TOP, BOTTOM, LEFT, RIGHT }

/** A letterbox band (px) on [side] of the picture. */
internal data class Band(val side: BandSide, val rect: PxRect)

/**
 * The bands between [box] and the [picture] inside it: top and bottom run the full width, the sides
 * fill what lies between them. Bands thinner than a pixel are left out.
 */
internal fun bandsOf(box: PxRect, picture: PxRect): List<Band> {
    val top = picture.top - box.top >= MIN_BAND_PX
    val bottom = box.bottom - picture.bottom >= MIN_BAND_PX
    val y0 = if (top) picture.top else box.top
    val y1 = if (bottom) picture.bottom else box.bottom
    return buildList {
        if (top) add(Band(BandSide.TOP, PxRect(box.left, box.top, box.right, picture.top)))
        if (bottom) add(Band(BandSide.BOTTOM, PxRect(box.left, picture.bottom, box.right, box.bottom)))
        if (picture.left - box.left >= MIN_BAND_PX) add(Band(BandSide.LEFT, PxRect(box.left, y0, picture.left, y1)))
        if (box.right - picture.right >= MIN_BAND_PX) add(Band(BandSide.RIGHT, PxRect(picture.right, y0, box.right, y1)))
    }
}

/** A picture of [aspect] (width over height) fitted whole and centred in [box]. */
internal fun fittedPicture(box: PxRect, aspect: Float): PxRect {
    if (aspect <= 0f || box.width <= 0f || box.height <= 0f) return box
    val w = minOf(box.width, box.height * aspect)
    val h = w / aspect
    return PxRect(Offset(box.center.x - w / 2, box.center.y - h / 2), Size(w, h))
}

/** [a] and [b] mixed: [b] at [t] = 1. */
internal fun lerpEdges(a: EdgeColors, b: EdgeColors, t: Float): EdgeColors {
    if (t >= 1f) return b
    fun mix(x: IntArray, y: IntArray) = IntArray(y.size) { i -> lerp(Color(x.getOrElse(i) { y[i] }), Color(y[i]), t).toArgb() }
    return EdgeColors(b.aspect, mix(a.top, b.top), mix(a.bottom, b.bottom), mix(a.left, b.left), mix(a.right, b.right))
}

/** What an ambient letterbox draws now: [colors] eased from sample to sample, faded by [alpha]. */
@Stable
internal class AmbientPaint(val colors: () -> EdgeColors?, alphaState: State<Float>) {
    val alpha by alphaState
}

/**
 * The ambient letterbox for [slot]'s colours, shown while [shown] and faded in and out over
 * [AMBIENT_FADE_MS]; a new sample eases in over [AMBIENT_EASE_MS]. Read it while drawing, so both
 * animations redraw.
 */
@Composable
internal fun rememberAmbientPaint(slot: AmbientSlot?, shown: Boolean): AmbientPaint {
    val target = slot?.colors
    val ease = remember { EdgeEase() }
    LaunchedEffect(target) { if (target != null) ease.easeTo(target) }
    val alpha = animateFloatAsState(
        targetValue = if (shown && target != null) 1f else 0f,
        animationSpec = tween(AMBIENT_FADE_MS),
        label = "ambientAlpha",
    )
    return remember(ease, alpha) { AmbientPaint(ease::current, alpha) }
}

/** A tile's ambient letterbox: what its renderer reports to while it samples, and what its card draws. */
internal class TileAmbient(val onEdgeColors: ((EdgeColors) -> Unit)?, val cardPaint: AmbientPaint)

/**
 * A tile samples while its bands are thick enough and its camera is on; a grid card or the solo view
 * draws them itself once its video shows, the stage draws the 1on1 main view's ([MainAmbient]). The
 * colours outlive sampling by the fade-out and then go, so a camera back on starts from its own frames.
 */
@Composable
internal fun rememberTileAmbient(style: CallTileStyle, cameraOn: Boolean, showVideo: Boolean): TileAmbient {
    val own = remember { AmbientSlot() }
    val slot = style.ambientSlot ?: own
    val sampling = style.ambient && cameraOn
    LaunchedEffect(slot, sampling) {
        if (!sampling) {
            delay(AMBIENT_FADE_MS.toLong())
            slot.colors = null
        }
    }
    val paint = rememberAmbientPaint(slot, style.ambient && showVideo && style.role != TileRole.MAIN)
    val report = remember(slot) { { colors: EdgeColors -> slot.colors = colors } }
    return TileAmbient(report.takeIf { sampling }, paint)
}

/**
 * The 1on1 main view's ambient letterbox, under every tile: its card is the video itself, so the
 * bands are the [media] area (stage px) around the card where it is drawn now, [picture], mid-move
 * included. Always composed; draws nothing while not [shown].
 */
@Composable
internal fun MainAmbient(slot: AmbientSlot?, shown: Boolean, media: PxRect?, picture: () -> PxRect?) {
    val paint = rememberAmbientPaint(slot, shown)
    val background = DifftTheme.colors.backgroundPagePrimary
    Spacer(
        Modifier.fillMaxSize().drawBehind {
            val alpha = paint.alpha
            if (alpha <= 0f || media == null) return@drawBehind
            val colors = paint.colors() ?: return@drawBehind
            val video = picture() ?: return@drawBehind
            drawAmbientBands(bandsOf(media, video), video, colors, background, alpha)
        },
    )
}

private class EdgeEase {
    private val progress = Animatable(1f)
    private var from: EdgeColors? = null
    private var to: EdgeColors? by mutableStateOf(null)

    fun current(): EdgeColors? {
        val next = to ?: return null
        return from?.let { lerpEdges(it, next, progress.value) } ?: next
    }

    suspend fun easeTo(next: EdgeColors) {
        from = current()
        to = next
        progress.snapTo(0f)
        progress.animateTo(1f, tween(AMBIENT_EASE_MS))
    }
}

/**
 * A grid card's or the solo view's ambient letterbox, behind its content: the bands between the card
 * and its video, which the renderer fits and centres in the card, fading into [background].
 */
internal fun Modifier.cardAmbient(paint: AmbientPaint, background: Color): Modifier = drawBehind {
    val alpha = paint.alpha
    if (alpha <= 0f) return@drawBehind
    val colors = paint.colors() ?: return@drawBehind
    val box = PxRect(Offset.Zero, size)
    val picture = fittedPicture(box, colors.aspect)
    drawAmbientBands(bandsOf(box, picture), picture, colors, background, alpha)
}

/**
 * Draws an ambient letterbox (call-layout simulator, `src/sim/letterbox.ts`): each band stretches the
 * picture's edge colours across itself and fades into [background] towards its outer side. [alpha]
 * fades the whole of it.
 */
internal fun DrawScope.drawAmbientBands(bands: List<Band>, picture: PxRect, colors: EdgeColors, background: Color, alpha: Float) {
    if (bands.isEmpty() || alpha <= 0f) return
    drawIntoCanvas { canvas ->
        val layered = alpha < 1f
        if (layered) canvas.saveLayer(PxRect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
        bands.forEach { drawBand(it, picture, colors, background) }
        if (layered) canvas.restore()
    }
}

private fun DrawScope.drawBand(band: Band, picture: PxRect, colors: EdgeColors, background: Color) {
    val r = band.rect
    val edge = when (band.side) {
        BandSide.TOP -> colors.top
        BandSide.BOTTOM -> colors.bottom
        BandSide.LEFT -> colors.left
        BandSide.RIGHT -> colors.right
    }.map(::Color)
    // Along the edge the colours follow the picture's border, held past its corners.
    val along = when (band.side) {
        BandSide.TOP, BandSide.BOTTOM -> Brush.linearGradient(edge, Offset(picture.left, r.top), Offset(picture.right, r.top))
        BandSide.LEFT, BandSide.RIGHT -> Brush.linearGradient(edge, Offset(r.left, picture.top), Offset(r.left, picture.bottom))
    }
    drawRect(along, r.topLeft, r.size)
    // Across the band, from the picture outwards.
    val (inner, outer) = when (band.side) {
        BandSide.TOP -> Offset(r.left, r.bottom) to Offset(r.left, r.top)
        BandSide.BOTTOM -> Offset(r.left, r.top) to Offset(r.left, r.bottom)
        BandSide.LEFT -> Offset(r.right, r.top) to Offset(r.left, r.top)
        BandSide.RIGHT -> Offset(r.left, r.top) to Offset(r.right, r.top)
    }
    val fade = Brush.linearGradient(
        0f to background.copy(alpha = 0f),
        HALFWAY to background.copy(alpha = FADE_HALFWAY),
        1f to background,
        start = inner,
        end = outer,
    )
    drawRect(fade, r.topLeft, r.size)
}
