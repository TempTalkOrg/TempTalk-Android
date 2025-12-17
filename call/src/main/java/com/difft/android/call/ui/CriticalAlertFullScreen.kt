package com.difft.android.call.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.tokens.ColorTokens



@Composable
fun AnimatedCssPoliceLightBar(modifier: Modifier = Modifier) {

    val infiniteTransition = rememberInfiniteTransition(label = "PoliceLightAnimation")

    // 渐变动画：背景位置从0%到100%，对应background-position动画
    val gradientPosition by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing), // 1秒动画，对应CSS的1s
            repeatMode = RepeatMode.Restart
        )
    )

    Box(
        modifier = modifier.drawWithCache {

            val w = size.width
            val h = size.height

            val gradientSize = 4f
            val gradientWidth = w * gradientSize
            val gradientHeight = h * gradientSize

            // 这意味着水平方向移动，垂直方向保持在50%
            // 计算背景位置偏移
            val offsetX = gradientPosition * (gradientWidth - w) // 从0到(gradientWidth - w)
            val offsetY = (gradientHeight - h) * 0.5f // 垂直方向保持在50%

            // -45度意味着从左上到右下
            // 颜色序列：红色 -> 黑色 -> 红色 -> 黑色
            val gradientStart = Offset(
                x = -offsetX,
                y = -offsetY
            )
            val gradientEnd = Offset(
                x = gradientWidth - offsetX,
                y = gradientHeight - offsetY
            )

            // 创建渐变画笔，颜色序列对应CSS中的渐变
            val gradient = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFF0000), // #ff0000 红色
                    Color(0xFF000000), // #000000 黑色
                    Color(0xFFFF0000), // #ff0000 红色
                    Color(0xFF000000)  // #000000 黑色
                ),
                start = gradientStart,
                end = gradientEnd
            )

            onDrawBehind {
                drawRect(gradient)
            }
        }
    )
}


@Composable
fun CriticalAlertFullScreen(
    title: String,
    message: String,
    onJoinClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))  // 背景遮罩
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .wrapContentWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ColorTokens.Dark.BackgroundTertiary) // 深灰主体背景
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(280.dp)
            ) {
                // 顶部 CSS 动态渐变警灯效果
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                ) {
                    AnimatedCssPoliceLightBar(modifier = Modifier.matchParentSize())

                    // 右上角关闭按钮
                    Icon(
                        painter = painterResource(id = com.difft.android.call.R.drawable.close),
                        contentDescription = "Close critical alert",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(18.dp)
                            .clickable { onCloseClick() }
                    )

                    // 图标（示意）
                    Icon(
                        painter = painterResource(id = com.difft.android.call.R.drawable.critical_tabler_bulb),
                        contentDescription = "Critical alert icon",
                        tint = Color.White,
                        modifier = Modifier
                            .size(40.dp)
                            .align(Alignment.Center)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Title
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontWeight = FontWeight(510),
                        color = colorResource(R.color.t_primary_night),
                        textAlign = TextAlign.Center,
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Message
                Text(
                    text = message,
                    style = TextStyle(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontWeight = FontWeight(400),
                        color = colorResource(R.color.t_secondary_night),
                        textAlign = TextAlign.Center,
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Join button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3D73F5))
                        .clickable { onJoinClick() }
                        .padding(vertical = 10.dp, horizontal = 30.dp)
                ) {
                    Text(
                        text = stringResource(id = com.difft.android.call.R.string.join_call),
                        color = Color.White,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        fontWeight = FontWeight(400),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}