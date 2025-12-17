package com.difft.android.call.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.user.CallConfig
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CountDownTimerView(modifier: Modifier, viewModel: LCallViewModel, callConfig: CallConfig) {

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val countDownEnabled by viewModel.timerManager.countDownEnabled.collectAsState(false)
    val countDownSeconds by viewModel.timerManager.countDownSeconds.collectAsState(0L)
    val countDownShakeAnim = remember { Animatable(0f) }
    val speakerCountDownDurationStr by viewModel.callUiController.countDownDurationStr.collectAsState("00:00")

    val countDownEndColor = Color(0xFFF84135)
    val countDownStartColor = Color(0xFF82C1FC)

    var preCountDownDurationStr by remember { mutableStateOf("00:00") }
    val shakingThreshold = (callConfig.countdownTimer?.shakingThreshold ?: 3000L) / 1000L

    LaunchedEffect(speakerCountDownDurationStr) {
        if(countDownEnabled && countDownSeconds> shakingThreshold && countDownShakeAnim.isRunning){
            countDownShakeAnim.stop()
            countDownShakeAnim.snapTo(0f)
        }
        if(countDownEnabled && preCountDownDurationStr != speakerCountDownDurationStr){
            when(countDownSeconds) {
                shakingThreshold -> {
                    coroutineScope.launch {
                        countDownShakeAnim.animateTo(
                            targetValue = 10f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 200, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                    }
                }
                0L -> {
                    coroutineScope.launch(Dispatchers.IO) { (context.getActivity() as? LCallActivity)?.countDownEndVibrate() }
                    countDownShakeAnim.stop()
                    countDownShakeAnim.snapTo(0f)
                }
            }
            preCountDownDurationStr = speakerCountDownDurationStr
        }else{
            preCountDownDurationStr = speakerCountDownDurationStr
        }
    }

    Row(
        modifier = modifier
            .wrapContentSize()
            .background(color = Color(0xCC181A20), shape = RoundedCornerShape(size = 4.dp))
            .padding(start = 4.dp, top = 2.dp, end = 4.dp, bottom = 2.dp)
            .graphicsLayer { rotationZ = countDownShakeAnim.value },
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ){
        Image(
            painter = painterResource(id = if (countDownTimeInLastThreeSeconds(countDownEnabled, countDownSeconds, callConfig.countdownTimer?.warningThreshold ?: 3000L) || countDownShakeAnim.isRunning) R.drawable.call_countdown_stopwatch else R.drawable.call_countdown_startwatch),
            contentDescription = "image description",
            contentScale = ContentScale.None
        )

        Text(
            text = speakerCountDownDurationStr,
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight(400),
                color = if (countDownTimeInLastThreeSeconds(countDownEnabled, countDownSeconds, callConfig.countdownTimer?.warningThreshold ?: 3000L) || countDownShakeAnim.isRunning) countDownEndColor else countDownStartColor,
            )
        )

    }
}

private fun countDownTimeInLastThreeSeconds(countDownEnabled: Boolean, countDownDuration: Long, warningThreshold: Long): Boolean {
    return countDownEnabled && countDownDuration <= warningThreshold / 1000L
}