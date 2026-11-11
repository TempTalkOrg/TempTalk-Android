package com.difft.android.call.ui.alert

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.difft.android.call.BuildConfig
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens



@Composable
fun AnimatedCssPoliceLightBar(modifier: Modifier = Modifier) {

    val infiniteTransition = rememberInfiniteTransition(label = "PoliceLightAnimation")

    val gradientPosition by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
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

            val offsetX = gradientPosition * (gradientWidth - w)
            val offsetY = (gradientHeight - h) * 0.5f

            val gradientStart = Offset(
                x = -offsetX,
                y = -offsetY
            )
            val gradientEnd = Offset(
                x = gradientWidth - offsetX,
                y = gradientHeight - offsetY
            )

            val gradient = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFF0000),
                    Color(0xFF000000),
                    Color(0xFFFF0000),
                    Color(0xFF000000)
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
            .background(Color.Black.copy(alpha = 0.45f))
            .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
            .testTag("critical_alert_root")
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .wrapContentWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ColorTokens.Dark.BackgroundTertiary)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(280.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                ) {
                    AnimatedCssPoliceLightBar(modifier = Modifier.matchParentSize())

                    Icon(
                        painter = painterResource(id = com.difft.android.call.R.drawable.close),
                        contentDescription = "Close critical alert",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(18.dp)
                            .testTag("critical_alert_close_btn")
                            .clickable { onCloseClick() }
                    )

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

                Text(
                    text = title,
                    style = DifftTheme.typography.h6,
                    color = DifftTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = message,
                    style = DifftTheme.typography.p4,
                    color = DifftTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                
                DifftButton(
                    text = stringResource(id = com.difft.android.call.R.string.join_call),
                    onClick = onJoinClick,
                    modifier = Modifier.testTag("critical_alert_join_btn"),
                )

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}