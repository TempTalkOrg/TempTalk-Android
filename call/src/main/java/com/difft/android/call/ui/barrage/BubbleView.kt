package com.difft.android.call.ui.barrage

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.R
import com.difft.android.call.data.BubbleAnimationState
import com.difft.android.call.data.EmojiBubbleMessage
import com.difft.android.call.data.TextBubbleMessage
import kotlinx.coroutines.delay

private data class BubbleAnimParams(
    val bubblePadding: Float,
    val bubbleRiseHeight: Float,
    val animationDuration: Int,
    val alphaFadeStartMillis: Int,
    val offsetX: Float,
)

@Composable
fun BoxScope.BubbleView(
    emoji: String?,
    text: String?,
    userName: String,
    startOffsetPercent: Int,
    durationMillis: Long,
    messageId: Long,
    onAnimationEnd: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // 气泡起飞后冻结所有动画参数，避免后续 recomposition（新气泡加入/移除列表、
    // 遮罩切换等）重新求值导致 animateFloat 目标跳变而产生抖动。
    val params = remember(messageId) {
        val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bubblePadding = with(density) {
            if (isLandscape) 56.dp.toPx() else 120.dp.toPx()
        }
        val screenWidth = with(density) {
            val fullWidth = configuration.screenWidthDp.dp.toPx()
            if (isLandscape) fullWidth / 2 else fullWidth
        }
        val bubbleRiseHeight = screenHeight * 0.8f
        val speedFactor = if (!isLandscape) 0.6f else 0.5f
        val animationDuration = (durationMillis * speedFactor).toInt().coerceAtLeast(1000)

        BubbleAnimParams(
            bubblePadding = bubblePadding,
            bubbleRiseHeight = bubbleRiseHeight,
            animationDuration = animationDuration,
            alphaFadeStartMillis = (animationDuration * 0.7f).toInt(),
            offsetX = screenWidth * (startOffsetPercent / 100f),
        )
    }

    var animationState by remember { mutableStateOf(BubbleAnimationState.Start) }

    val transition = updateTransition(targetState = animationState, label = "bubbleAnimation")

    val offsetY by transition.animateFloat(
        transitionSpec = {
            tween(durationMillis = params.animationDuration, easing = LinearEasing)
        },
        label = "offsetY"
    ) { state ->
        when (state) {
            BubbleAnimationState.Start -> -params.bubblePadding
            BubbleAnimationState.End -> -(params.bubblePadding + params.bubbleRiseHeight)
        }
    }

    val alpha by transition.animateFloat(
        transitionSpec = {
            keyframes {
                this.durationMillis = params.animationDuration
                1f at 0
                1f at params.alphaFadeStartMillis
                0f at params.animationDuration
            }
        },
        label = "alpha"
    ) { state ->
        when (state) {
            BubbleAnimationState.Start -> 1f
            BubbleAnimationState.End -> 0f
        }
    }

    LaunchedEffect(messageId) {
        animationState = BubbleAnimationState.End
        delay(params.animationDuration.toLong())
        onAnimationEnd()
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .graphicsLayer {
                translationX = params.offsetX
                translationY = offsetY
                this.alpha = alpha
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!emoji.isNullOrEmpty()) {
            Text(
                text = emoji,
                style = TextStyle(
                    fontSize = 48.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight(400)
                )
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
                    color = colorResource(id = R.color.bg3_night),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
            text = bottomText,
            style = TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight(400),
                color = colorResource(id = R.color.t_primary_night)
            )
        )
    }
}

@Composable
fun BoxScope.EmojiBubbleView(
    bubbleMessage: EmojiBubbleMessage,
    onAnimationEnd: () -> Unit
) {
    BubbleView(
        emoji = bubbleMessage.emoji,
        text = null,
        userName = bubbleMessage.userName,
        startOffsetPercent = bubbleMessage.startOffsetPercent,
        durationMillis = bubbleMessage.durationMillis,
        messageId = bubbleMessage.id,
        onAnimationEnd = onAnimationEnd
    )
}

@Composable
fun BoxScope.TextBubbleView(
    bubbleMessage: TextBubbleMessage,
    onAnimationEnd: () -> Unit
) {
    BubbleView(
        emoji = bubbleMessage.emoji,
        text = bubbleMessage.text,
        userName = bubbleMessage.userName,
        startOffsetPercent = bubbleMessage.startOffsetPercent,
        durationMillis = bubbleMessage.durationMillis,
        messageId = bubbleMessage.id,
        onAnimationEnd = onAnimationEnd
    )
}
