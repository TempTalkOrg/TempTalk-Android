package com.difft.android.call.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import kotlinx.coroutines.delay


@Composable
fun CallCriticalAlertView(
    showDelayMillis: Long = 15000,
    clicked: () -> Unit = {},
) {
    var showAlert by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(showDelayMillis)
        showAlert = true
    }

    AnimatedVisibility(
        visible = showAlert,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -80 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -80 }),
    ) {
        CallCriticalAlertBanner(
            onClicked = {
                clicked()
                showAlert = false
            }
        )
    }
}

@Composable
fun CallCriticalAlertBanner(onClicked: () -> Unit) {
    var showTooltip by remember { mutableStateOf(false) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    var tooltipBounds by remember { mutableStateOf<Rect?>(null) }
    var popupCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    val rowWidthDp = if (rowWidthPx > 0) with(density) { rowWidthPx.toDp() } else null
    val tooltipWidthModifier = if (rowWidthDp != null) Modifier.width(rowWidthDp) else Modifier

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        rowWidthPx = coordinates.size.width
                        rowBounds = coordinates.boundsInWindow()
                    }
                    .background(
                        color = DifftTheme.colors.backgroundTertiary,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                    .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                    .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)
            ) {
                Image(
                    modifier = Modifier
                        .padding(0.64167.dp)
                        .width(14.dp)
                        .height(14.dp),
                    painter = painterResource(id = R.drawable.call_critical_alert),
                    contentDescription = "image description",
                    contentScale = ContentScale.Fit
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = ResUtils.getString(R.string.call_topbar_critical_alert_entrance_text1),
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight(400),
                            color = Color.White,
                        )
                    )

                    Text(
                        modifier = Modifier.clickable {
                            onClicked()
                        },
                        text = ResUtils.getString(R.string.call_topbar_critical_alert_entrance_text2),
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight(400),
                            color = ColorTokens.InfoLight,
                        )
                    )
                }

                Image(
                    modifier = Modifier
                        .padding(0.66667.dp)
                        .width(16.dp)
                        .height(16.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            showTooltip = !showTooltip
                        },
                    painter = painterResource(id = R.drawable.tabler_info_circle),
                    contentDescription = "info icon",
                    contentScale = ContentScale.Fit
                )
            }

            if (showTooltip) {
                Text(
                    modifier = tooltipWidthModifier
                        .onGloballyPositioned { coordinates ->
                            tooltipBounds = coordinates.boundsInWindow()
                        }
                        .background(
                            color = DifftTheme.colors.backgroundTooltip,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(start = 10.dp, top = 5.dp, end = 10.dp, bottom = 5.dp),
                    text = ResUtils.getString(R.string.call_critical_alert_tooltip),
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = Color.White
                    )
                )
            }
        }

        if (showTooltip) {
            Popup(properties = PopupProperties(focusable = false)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            popupCoordinates = coordinates
                        }
                        .pointerInput(showTooltip) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue
                                    if (change.changedToUpIgnoreConsumed()) {
                                        val popup = popupCoordinates ?: continue
                                        val tapInWindow = popup.localToWindow(change.position)
                                        val isInsideRow = rowBounds?.contains(tapInWindow) == true
                                        val isInsideTooltip = tooltipBounds?.contains(tapInWindow) == true
                                        if (!isInsideRow && !isInsideTooltip) {
                                            showTooltip = false
                                        }
                                    }
                                }
                            }
                        }
                )
            }
        }
    }
}