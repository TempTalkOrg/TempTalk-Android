package com.difft.android.call.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.call.R
import kotlinx.coroutines.delay


@Composable
fun CallCriticalAlertView(
    showDelayMillis: Long = 15000,
    autoDismissMillis: Long = 3000,
    clicked: () -> Unit = {},
) {
    var showAlert by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(showDelayMillis)
        showAlert = true
        delay(autoDismissMillis)
        showAlert = false
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = colorResource(id = com.difft.android.base.R.color.bg3_night),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable {
                onClicked()
            }
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)
            .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
    ) {
        Image(
            modifier = Modifier
                .border(width = 1.23148.dp, color = colorResource(id = com.difft.android.base.R.color.t_error_night))
                .padding(1.23148.dp)
                .width(11.08333.dp)
                .height(11.08333.dp),
            painter = painterResource(id = R.drawable.call_critical_alert),
            contentDescription = "image description",
            contentScale = ContentScale.Fit
        )

        Text(
            text = "No answer? Send a critical alert.",
            style = TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = SfProFont,
                fontWeight = FontWeight(400),
                color = colorResource(id = com.difft.android.base.R.color.t_white),
            )
        )
    }
}