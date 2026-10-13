package com.difft.android.call.ui.incoming

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Geometry and timing from the design spec (incoming call, single-track slider). */
object IncomingCallSliderDefaults {
    val TrackHeight = 88.dp
    val TrackRadius = 44.dp
    val HandleWidth = 104.dp
    val HandleHeight = 72.dp
    val HandleRadius = 36.dp
    val HandleInset = 8.dp
    val HandleShadow = 4.dp
    val LabelInset = 32.dp
    val HintGap = 16.dp
    val HintHeight = 20.dp
    val HintLift = 4.dp
    val AnswerIconSize = 28.dp
    val TapSlop = 4.dp

    /** Fraction of the travel past which a release commits. */
    const val COMMIT_FRACTION = 0.6f

    /** Fraction of the travel past which the handle takes the side's colour. */
    const val TINT_FRACTION = 0.02f
    const val LABEL_FADE_RATE = 1.4f
    const val FILL_ALPHA = 0.34f
    const val DISABLED_ALPHA = 0.5f

    const val SETTLE_MS = 320
    const val TINT_MS = 200
    const val ICON_SWAP_MS = 120
    const val HINT_FADE_MS = 200
    const val HINT_VISIBLE_MS = 2_000L
    const val NUDGE_PERIOD_MS = 2_800

    const val HANDLE_TEST_TAG = "incoming_call_slider_handle"

    /** +1 answer, -1 decline, 0 spring back. */
    fun commitDirection(offsetPx: Float, maxTravelPx: Float): Int = when {
        maxTravelPx <= 0f -> 0
        offsetPx >= maxTravelPx * COMMIT_FRACTION -> 1
        offsetPx <= -maxTravelPx * COMMIT_FRACTION -> -1
        else -> 0
    }
}

/**
 * Single-track incoming-call slider for a screen the call itself lit up: one handle in the middle
 * of a pill track, dragged right to answer or left to decline. Nothing click-like can activate it.
 *
 * Idle: light handle with the green answer glyph, nudged sideways every 2.8 s. Dragging right tints
 * the handle green, fades the "answer" label and fills the right half; dragging left tints it red,
 * swaps the glyph for hang-up, fades "decline" and fills the left half. Releasing past 60 % of the
 * travel fires the callback once and settles the handle at the end; short of that it springs back.
 * A tap without movement fires nothing, re-nudges the handle and shows [hint] above the track for
 * two seconds. Re-enabling after a commit re-arms the slider (a join that failed may be retried).
 */
@Composable
fun IncomingCallSlider(
    answerIcon: Painter,
    declineIcon: Painter,
    declineLabel: String,
    answerLabel: String,
    hint: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** False while off screen (PiP): the idle nudge then holds still. */
    animateIdle: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember { SliderState(scope, density) }

    LaunchedEffect(enabled) {
        if (enabled && state.committed) state.rearm()
    }

    val colors = DifftTheme.colors
    val hintAlpha by animateFloatAsState(
        targetValue = if (state.hintVisible) 1f else 0f,
        animationSpec = tween(IncomingCallSliderDefaults.HINT_FADE_MS),
        label = "hintAlpha",
    )
    val idle = animateIdle && enabled && !state.dragging && !state.committed && state.offset == 0f
    val nudgePx = key(state.nudgeKey) { nudgeOffset(active = idle, density = density) }
    val liftPx = with(density) { IncomingCallSliderDefaults.HintLift.toPx() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else IncomingCallSliderDefaults.DISABLED_ALPHA),
    ) {
        // Reserved slot (grows with the font scale) so the track never moves; the text exists only while fading.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = IncomingCallSliderDefaults.HintHeight)) {
            if (hintAlpha > 0f) {
                Text(
                    text = hint,
                    color = colors.textSecondary,
                    style = DifftTheme.typography.p3,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        alpha = hintAlpha
                        translationY = (1f - hintAlpha) * liftPx
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(IncomingCallSliderDefaults.HintGap))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(IncomingCallSliderDefaults.TrackHeight)
                .clip(RoundedCornerShape(IncomingCallSliderDefaults.TrackRadius))
                .background(colors.backgroundSecondary)
                .onSizeChanged { state.onTrackWidth(it.width) },
        ) {
            // Left = decline, right = answer are absolute in the design and match the absolute drag
            // direction, so the sides never mirror under RTL (AbsoluteAlignment, absolutePadding).
            SideFill(AbsoluteAlignment.CenterLeft, colors.error, (-state.progress).coerceAtLeast(0f), fromLeft = true)
            SideFill(AbsoluteAlignment.CenterRight, colors.success, state.progress.coerceAtLeast(0f), fromLeft = false)
            SideLabel(declineLabel, AbsoluteAlignment.CenterLeft, state.labelAlpha(left = true))
            SideLabel(answerLabel, AbsoluteAlignment.CenterRight, state.labelAlpha(left = false))
            Handle(
                state = state,
                answerIcon = answerIcon,
                declineIcon = declineIcon,
                nudgePx = nudgePx,
                enabled = enabled,
                onAnswer = onAnswer,
                onDecline = onDecline,
            )
        }
    }
}

/** Drag state and the settle / hint jobs, kept outside the composition tree so no late delta can strand the handle. */
private class SliderState(private val scope: CoroutineScope, density: Density) {
    private val handleWidthPx = with(density) { IncomingCallSliderDefaults.HandleWidth.toPx() }
    private val insetPx = with(density) { IncomingCallSliderDefaults.HandleInset.toPx() }
    private val slopPx = with(density) { IncomingCallSliderDefaults.TapSlop.toPx() }

    var maxTravelPx by mutableFloatStateOf(0f)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    var dragging by mutableStateOf(false)
        private set
    var committed by mutableStateOf(false)
        private set
    var hintVisible by mutableStateOf(false)
        private set
    var nudgeKey by mutableIntStateOf(0)
        private set

    private var moved = false
    private var settle: Job? = null
    private var hintJob: Job? = null

    val progress: Float
        get() = if (maxTravelPx > 0f) (offset / maxTravelPx).coerceIn(-1f, 1f) else 0f
    val toRight: Boolean get() = progress > IncomingCallSliderDefaults.TINT_FRACTION
    val toLeft: Boolean get() = progress < -IncomingCallSliderDefaults.TINT_FRACTION

    fun onTrackWidth(widthPx: Int) {
        maxTravelPx = ((widthPx - 2 * insetPx - handleWidthPx) / 2f).coerceAtLeast(0f)
    }

    /** The side label fades as the handle approaches it. */
    fun labelAlpha(left: Boolean): Float {
        val p = progress
        val fading = if (left) p < 0f else p > 0f
        return if (fading) (1f - abs(p) * IncomingCallSliderDefaults.LABEL_FADE_RATE).coerceIn(0f, 1f) else 1f
    }

    fun onDragStarted() {
        settle?.cancel()
        settle = null
        dragging = true
        moved = false
    }

    fun onDrag(delta: Float) {
        val next = (offset + delta).coerceIn(-maxTravelPx, maxTravelPx)
        if (abs(next) > slopPx) moved = true
        offset = next
    }

    fun onDragStopped(onAnswer: () -> Unit, onDecline: () -> Unit) {
        dragging = false
        val direction = IncomingCallSliderDefaults.commitDirection(offset, maxTravelPx)
        if (direction != 0) {
            committed = true
            // The action fires at the threshold; the settle to the rail end is visual only, so a
            // cancel arriving during those 320 ms cannot race a still-pending callback.
            settleTo(direction * maxTravelPx) {}
            if (direction > 0) onAnswer() else onDecline()
        } else {
            val tapped = !moved
            settleTo(0f) {
                nudgeKey++
                if (tapped) showHint()
            }
        }
    }

    fun rearm() {
        settle?.cancel()
        settle = null
        committed = false
        offset = 0f
        nudgeKey++
    }

    /** A tap without movement never activates: it re-nudges the handle and shows the hint. */
    fun onTap() {
        if (committed || dragging) return
        nudgeKey++
        showHint()
    }

    private fun settleTo(target: Float, then: () -> Unit) {
        settle?.cancel()
        settle = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                animationSpec = tween(IncomingCallSliderDefaults.SETTLE_MS, easing = SettleEasing),
            ) { value, _ -> offset = value }
            then()
        }
    }

    private fun showHint() {
        hintJob?.cancel()
        hintJob = scope.launch {
            hintVisible = true
            delay(IncomingCallSliderDefaults.HINT_VISIBLE_MS)
            hintVisible = false
        }
    }
}

/** The draggable handle: tinted by direction, answer glyph crossfading to the hang-up glyph on the way left. */
@Composable
private fun BoxScope.Handle(
    state: SliderState,
    answerIcon: Painter,
    declineIcon: Painter,
    nudgePx: Float,
    enabled: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = DifftTheme.colors
    val handleColor by animateColorAsState(
        targetValue = when {
            state.toRight -> colors.success
            state.toLeft -> colors.error
            else -> colors.bgWhite
        },
        animationSpec = tween(IncomingCallSliderDefaults.TINT_MS),
        label = "handleColor",
    )
    val answerTint by animateColorAsState(
        targetValue = if (state.toRight) colors.bgWhite else colors.success,
        animationSpec = tween(IncomingCallSliderDefaults.TINT_MS),
        label = "answerTint",
    )
    val declineAlpha by animateFloatAsState(
        targetValue = if (state.toLeft) 1f else 0f,
        animationSpec = tween(IncomingCallSliderDefaults.ICON_SWAP_MS),
        label = "declineAlpha",
    )
    val shape = RoundedCornerShape(IncomingCallSliderDefaults.HandleRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(Alignment.Center)
            .absoluteOffset { IntOffset((state.offset + nudgePx).roundToInt(), 0) }
            .size(IncomingCallSliderDefaults.HandleWidth, IncomingCallSliderDefaults.HandleHeight)
            .shadow(IncomingCallSliderDefaults.HandleShadow, shape)
            .background(handleColor, shape)
            .testTag(IncomingCallSliderDefaults.HANDLE_TEST_TAG)
            // Tap detection sits beside the drag: draggable only reports once the touch slop is
            // crossed, so a plain tap would otherwise be invisible to the state.
            .pointerInput(enabled) { if (enabled) detectTapGestures(onTap = { state.onTap() }) }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled && !state.committed,
                state = rememberDraggableState { delta -> state.onDrag(delta) },
                onDragStarted = { state.onDragStarted() },
                onDragStopped = { state.onDragStopped(onAnswer, onDecline) },
            ),
    ) {
        Image(
            painter = answerIcon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(answerTint),
            modifier = Modifier
                .size(IncomingCallSliderDefaults.AnswerIconSize)
                .alpha(1f - declineAlpha),
        )
        Image(
            painter = declineIcon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.bgWhite),
            modifier = Modifier.alpha(declineAlpha),
        )
    }
}

@Composable
private fun BoxScope.SideLabel(text: String, alignment: Alignment, alpha: Float) {
    val padding = if (alignment == AbsoluteAlignment.CenterLeft) {
        Modifier.absolutePadding(left = IncomingCallSliderDefaults.LabelInset)
    } else {
        Modifier.absolutePadding(right = IncomingCallSliderDefaults.LabelInset)
    }
    Text(
        text = text,
        color = DifftTheme.colors.textPrimary,
        style = DifftTheme.typography.p2,
        modifier = Modifier.align(alignment).then(padding).alpha(alpha),
    )
}

/** Half-track gradient that grows in as the handle moves toward that side. */
@Composable
private fun BoxScope.SideFill(alignment: Alignment, color: Color, alpha: Float, fromLeft: Boolean) {
    val strong = color.copy(alpha = IncomingCallSliderDefaults.FILL_ALPHA)
    val clear = color.copy(alpha = 0f)
    val brush = if (fromLeft) {
        Brush.horizontalGradient(listOf(strong, clear))
    } else {
        Brush.horizontalGradient(listOf(clear, strong))
    }
    Box(
        modifier = Modifier
            .align(alignment)
            .fillMaxWidth(0.5f)
            .fillMaxHeight()
            .alpha(alpha)
            .background(brush),
    )
}

/**
 * The idle "you can drag me" nudge: still for 62 % of each 2.8 s cycle, then 0 → 12 → -8 → 4 → 0 dp.
 * Returns 0 when [active] is false so the transition never fights a drag or a settle.
 */
@Composable
private fun nudgeOffset(active: Boolean, density: Density): Float {
    if (!active) return 0f
    val transition = rememberInfiniteTransition(label = "nudge")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = IncomingCallSliderDefaults.NUDGE_PERIOD_MS
                0f at 0
                0f at (NUDGE_PERIOD * 0.62f).toInt()
                12f at (NUDGE_PERIOD * 0.70f).toInt()
                -8f at (NUDGE_PERIOD * 0.78f).toInt()
                4f at (NUDGE_PERIOD * 0.86f).toInt()
                0f at NUDGE_PERIOD.toInt()
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "nudgeDp",
    )
    return with(density) { value.dp.toPx() }
}

private const val NUDGE_PERIOD = IncomingCallSliderDefaults.NUDGE_PERIOD_MS.toFloat()

/** `cubic-bezier(.2,.9,.3,1.2)` from the design: a settle with a slight overshoot. */
private val SettleEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f)
