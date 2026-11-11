package com.difft.android.call.ui.barrage

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.difft.android.base.ui.theme.DifftTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.call.data.EmojiBubbleMessage
import com.difft.android.call.data.TextBubbleMessage
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive


internal val LocalBubbleStageBottomPx = compositionLocalOf<Int?> { null }


private val TABLETOP_BUBBLE_PADDING = 24.dp

private data class BubbleAnimParams(
    val bubblePadding: Float,
    val bubbleRiseHeight: Float,
    val animationDuration: Int,
    val offsetX: Float,
    val screenHeightPx: Float,
)






@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun BoxScope.BubbleView(
    emoji: String?,
    text: String?,
    userName: String,
    startOffsetPercent: Int,
    durationMillis: Long,
    messageId: Long,
    onAnimationEnd: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val stageBottomPx = LocalBubbleStageBottomPx.current

    val params = remember(messageId, windowSize, density, stageBottomPx) {
        val screenHeight = stageBottomPx?.toFloat() ?: if (windowSize.height > 0) {
            windowSize.height.toFloat()
        } else {
            with(density) { configuration.screenHeightDp.dp.toPx() }
        }
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bubblePadding = with(density) {
            when {
                stageBottomPx != null -> TABLETOP_BUBBLE_PADDING.toPx()
                isLandscape -> 56.dp.toPx()
                else -> 120.dp.toPx()
            }
        }
        val fullWidth = if (windowSize.width > 0) {
            windowSize.width.toFloat()
        } else {
            with(density) { configuration.screenWidthDp.dp.toPx() }
        }
        val screenWidth = if (isLandscape) fullWidth / 2 else fullWidth
        val bubbleRiseHeight = screenHeight * 0.8f
        val speedFactor = if (!isLandscape) 0.6f else 0.5f
        val animationDuration = (durationMillis * speedFactor).toInt().coerceAtLeast(1000)

        BubbleAnimParams(
            bubblePadding = bubblePadding,
            bubbleRiseHeight = bubbleRiseHeight,
            animationDuration = animationDuration,
            offsetX = screenWidth * (startOffsetPercent / 100f),
            screenHeightPx = screenHeight,
        )
    }

    var renderedOffsetY by remember { mutableFloatStateOf(-params.bubblePadding) }
    var renderedAlpha by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(messageId) {
        var virtualElapsedMs = 0f
        var prevFrameNanos = withFrameNanos { it }
        val maxAdvanceMs = FRAME_MS * MAX_FRAME_ADVANCE

        while (isActive) {
            withFrameNanos { frameNanos ->
                val frameDeltaMs = (frameNanos - prevFrameNanos) / 1_000_000f
                prevFrameNanos = frameNanos

                virtualElapsedMs += frameDeltaMs.coerceAtMost(maxAdvanceMs)

                val fraction = (virtualElapsedMs / params.animationDuration).coerceAtMost(1f)
                renderedOffsetY = -params.bubblePadding - params.bubbleRiseHeight * fraction

                renderedAlpha = when {
                    fraction < FADE_START -> 1f
                    fraction >= 1f -> 0f
                    else -> 1f - (fraction - FADE_START) / (1f - FADE_START)
                }
            }

            if (virtualElapsedMs >= params.animationDuration) break
        }
        onAnimationEnd()
    }

    Column(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    constraints.copy(minWidth = 0, minHeight = 0)
                )
                
                
                val maxX = if (constraints.hasBoundedWidth) (constraints.maxWidth - placeable.width).coerceAtLeast(0) else Int.MAX_VALUE
                layout(placeable.width, placeable.height) {
                    placeable.place(
                        x = params.offsetX.roundToInt().coerceIn(0, maxX),
                        y = (params.screenHeightPx - placeable.height + renderedOffsetY).roundToInt()
                    )
                }
            }
            .graphicsLayer { this.alpha = renderedAlpha }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!emoji.isNullOrEmpty()) {
            Text(
                text = emoji,
                style = TextStyle(
                    fontSize = 48.sp,
                    fontWeight = FontWeight(400),
                ),
            )
        }

        val bottomText = if (text != null) {
            if (userName.length > 5) {
                "${userName.take(5)}... : $text"
            } else {
                "$userName : $text"
            }
        } else {
            if (userName.length > 10) {
                "${userName.take(10)}..."
            } else {
                userName
            }
        }

        val bottomTextModifier = if (!emoji.isNullOrEmpty()) {
            Modifier.padding(top = 4.dp)
        } else {
            Modifier
        }

        Text(
            modifier = bottomTextModifier
                .background(
                    color = DifftTheme.colors.backgroundTertiary,
                    shape = DifftTheme.radius.mediumShape,
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
            text = bottomText,
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textPrimary,
        )
    }
}

@Composable
fun BoxScope.EmojiBubbleView(
    bubbleMessage: EmojiBubbleMessage,
    onAnimationEnd: () -> Unit,
) {
    BubbleView(
        emoji = bubbleMessage.emoji,
        text = null,
        userName = bubbleMessage.userName,
        startOffsetPercent = bubbleMessage.startOffsetPercent,
        durationMillis = bubbleMessage.durationMillis,
        messageId = bubbleMessage.id,
        onAnimationEnd = onAnimationEnd,
    )
}

@Composable
fun BoxScope.TextBubbleView(
    bubbleMessage: TextBubbleMessage,
    onAnimationEnd: () -> Unit,
) {
    BubbleView(
        emoji = bubbleMessage.emoji,
        text = bubbleMessage.text,
        userName = bubbleMessage.userName,
        startOffsetPercent = bubbleMessage.startOffsetPercent,
        durationMillis = bubbleMessage.durationMillis,
        messageId = bubbleMessage.id,
        onAnimationEnd = onAnimationEnd,
    )
}

private const val FADE_START = 0.7f
private const val MAX_FRAME_ADVANCE = 1.5f
private const val FRAME_MS = 16.67f
