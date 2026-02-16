package com.difft.android.call.ui

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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

/**
 * 统一的气泡消息视图，支持 Emoji 和 Text 两种类型
 */
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
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }

    // 计算 bubble 的起始高度（dp 转 px）
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bubblePadding = with(density) {
        if (isLandscape) 56.dp.toPx() else 120.dp.toPx()
    }

    // 根据屏幕宽度，设置气泡的展示范围
    val screenWidth = with(density) {
        val fullWidth = configuration.screenWidthDp.dp.toPx()
        if (isLandscape) fullWidth / 2 else fullWidth
    }

    // 向上飘的高度为屏幕高度的80%
    val bubbleRiseHeight = screenHeight * 0.8f

    // 速度系数：小于1的值会加快动画速度（例如0.6表示动画时间缩短到60%，速度提升约67%）
    val speedFactor = if(!isLandscape) 0.6f else 0.5f // 0.6 = 速度提升约67%，0.5 = 速度提升100%，0.4 = 速度提升150%

    val animationDuration = (durationMillis * speedFactor).toInt().coerceAtLeast(1000) // 最少1秒，避免过快

    var animationState by remember { mutableStateOf(BubbleAnimationState.Start) }

    val transition = updateTransition(targetState = animationState, label = "bubbleAnimation")

    // 动画速度由 animationDuration 控制（已应用速度系数）
    val offsetY by transition.animateFloat(
        transitionSpec = {
            tween(durationMillis = animationDuration, easing = LinearEasing)
        },
        label = "offsetY"
    ) { state ->
        when (state) {
            BubbleAnimationState.Start -> -bubblePadding
            BubbleAnimationState.End -> -(bubblePadding + bubbleRiseHeight)
        }
    }

    val alphaFadeStartFraction = 0.7f
    val alphaFadeStartMillis = (animationDuration * alphaFadeStartFraction).toInt()

    val alpha by transition.animateFloat(
        transitionSpec = {
            keyframes {
                this.durationMillis = animationDuration
                1f at 0
                1f at alphaFadeStartMillis
                0f at animationDuration
            }
        },
        label = "alpha"
    ) { state ->
        when (state) {
            BubbleAnimationState.Start -> 1f
            BubbleAnimationState.End -> 0f
        }
    }

    // 计算左侧偏移量（基于百分比）
    val offsetX = screenWidth * (startOffsetPercent / 100f)

    LaunchedEffect(messageId) {
        animationState = BubbleAnimationState.End
        delay(animationDuration.toLong())
        onAnimationEnd()
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .offset(
                x = with(LocalDensity.current) { offsetX.toDp() },
                y = with(LocalDensity.current) { offsetY.toDp() }
            )
            .alpha(alpha)
            .shadow(
                elevation = 6.dp,
                spotColor = Color(0x14000000),
                ambientColor = Color(0x14000000)
            )
            .shadow(
                elevation = 14.dp,
                spotColor = Color(0x14000000),
                ambientColor = Color(0x14000000)
            )
            .background(
                color = Color.Transparent,
                shape = RoundedCornerShape(size = 8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Emoji 在上（如果有）
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

        // 底部文本：根据是否有 text 来决定显示格式
        val bottomText = if (text != null) {
            // TextBubbleMessage: 用户名 + 文本内容
            if (userName.length > 5) {
                "${userName.take(5)}... : $text"
            } else {
                "$userName : $text"
            }
        } else {
            // EmojiBubbleMessage: 只显示用户名
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

/**
 * Emoji 气泡消息视图（兼容旧接口）
 */
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

/**
 * Text 气泡消息视图（兼容旧接口）
 */
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
