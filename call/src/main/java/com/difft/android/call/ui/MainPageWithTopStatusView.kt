package com.difft.android.call.ui

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import com.difft.android.base.call.CallType
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.util.StringUtil
import kotlinx.coroutines.launch

@Composable
fun MainPageWithTopStatusView(
    viewModel: LCallViewModel,
    isInPipMode:Boolean,
    isOneVOneCall: Boolean,
    showTopStatusViewEnabled: Boolean = true,
    isUserSharingScreen: Boolean,
    callConfig: CallConfig,
    callIntent: CallIntent,
    windowZoomOutAction: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val coroutineScope = rememberCoroutineScope()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val callDuration by viewModel.timerManager.callDurationText.collectAsState("00:00")
    val callStatus by viewModel.callStatus.collectAsState()
    val countDownEnabled by viewModel.timerManager.countDownEnabled.collectAsState(false)
    val callType by viewModel.callType.collectAsState()
    val screenSharingUser by viewModel.screenSharingUser.collectAsState()

    var screenShareUserName: String? by remember { mutableStateOf(null) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }

    val dividerColor = Color(0xFF474D57)

    LaunchedEffect(callStatus) {
        val animationDurationMillis = 2000
        animate(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = animationDurationMillis,
                    easing = LinearEasing
                ),
            )
        ) { value, /* velocity */ _ ->
            rotationAngle = value
        }
    }

    LaunchedEffect(screenSharingUser) {
        coroutineScope.launch {
            screenSharingUser?.let {
                it.identity?.value?.let { identityId ->
                    screenShareUserName = "${LCallManager.getDisplayNameById(identityId)}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (!isLandscape) 44.dp else 0.dp,
                bottom = if (!isLandscape) 4.dp else 4.dp
            ),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if((isOneVOneCall && !isUserSharingScreen) || showTopStatusViewEnabled){
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .background(
                        if (!isUserSharingScreen)
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Transparent),
                            )
                        else
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.4f),
                                    Color.Transparent
                                ),
                            ),
                    )
            ){
                val controlSize = 26.dp
                val controlPadding = 4.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                )
                {
                    ConstraintLayout(
                        modifier = Modifier.fillMaxWidth()
                    ){
                        val (windowZoomOut, textView) = createRefs()

                        if(!isInPipMode){
                            Box(
                                modifier = Modifier
                                    .constrainAs(windowZoomOut) {
                                        start.linkTo(parent.start)
                                    }
                                    .size(44.dp, 44.dp)
                                    .clickable(
                                        onClick = { windowZoomOutAction() },
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() })
                                    .background(Color.Transparent), // 设置背景为透明，确保不影响视觉
                            ){
                                Surface(
                                    modifier = Modifier
                                        .wrapContentSize(Alignment.Center)
                                        .size(controlSize),
                                    color = Color.Transparent
                                ) {
                                    val resource = R.drawable.chat_ic_window_zoom_out
                                    Icon(
                                        painterResource(id = resource),
                                        contentDescription = "WINDOW_ZOOM_OUT",
                                        tint = Color.White,
                                    )
                                }
                            }
                        }


                        Column(
                            modifier = Modifier.constrainAs(textView){
                                centerHorizontallyTo(parent)
                            },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ){
                            if(callStatus == CallStatus.CONNECTED || callStatus == CallStatus.RECONNECTED) {
                                if(isUserSharingScreen){
                                    screenSharingUser?.let { participant ->
                                        participant.identity?.value?.let { identityId ->
                                            screenShareUserName?.let{ it->
                                                Text(
                                                    text = "${StringUtil.getShowUserName(it, 14)}${context.getString(R.string.call_screen_sharing_title)} $callDuration",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                    color = colorResource(id = com.difft.android.base.R.color.t_white),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }else if(!isOneVOneCall) {
                                    Text(
                                        text = viewModel.getCallRoomName(),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                        color = colorResource(id = com.difft.android.base.R.color.t_white),
                                        maxLines = 1
                                    )
                                }
                                if(!isUserSharingScreen){
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ){
                                            Text(
                                                text = callDuration,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                color = colorResource(id = com.difft.android.base.R.color.t_white),
                                                maxLines = 1
                                            )

                                            if(countDownEnabled) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(0.dp)
                                                        .width(1.dp)
                                                        .height(10.dp)
                                                        .background(color = dividerColor) // 设置竖线的背景颜色
                                                )

                                                CountDownTimerView(
                                                    modifier = Modifier,
                                                    viewModel = viewModel,
                                                    callConfig = callConfig
                                                )
                                            }
                                        }
                                    }
                                }
                            }else {
                                if (callType == CallType.ONE_ON_ONE.type && callStatus == CallStatus.CALLING) {
                                    Text(
                                        text = ResUtils.getString(R.string.call_status_calling),
                                        style = TextStyle(
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            fontFamily = SfProFont,
                                            fontWeight = FontWeight(400),
                                            color = colorResource(id = com.difft.android.base.R.color.t_primary_night),
                                        )
                                    )
                                } else {
                                    if(shouldShowLoadingStatus(callStatus, callIntent, callType)) {
                                        val painter = when {
                                            callStatus == CallStatus.RECONNECT_FAILED -> painterResource(id = R.drawable.gg_spinner_alt)
                                            else -> painterResource(id = R.drawable.ant_design_loading_outlined)
                                        }

                                        val status = if(callStatus == CallStatus.RECONNECT_FAILED) context.getString(R.string.call_disconnected_title) else context.getString(R.string.call_connecting_title)

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ){
                                            Image(
                                                contentScale = ContentScale.Fit,
                                                painter = painter,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .rotate(rotationAngle)
                                            )

                                            Text(
                                                modifier = Modifier.padding(start = 4.dp),
                                                text = status,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                color = colorResource(id = com.difft.android.base.R.color.t_white),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if(viewModel.is1v1ShowCriticalAlertEnable(callStatus)) {
                CallCriticalAlertView(
                    clicked = {
                        callIntent.conversationId?.let {
                            viewModel.handleCriticalAlert(it)
                        }
                    }
                )
            }
        }
    }

}

private fun shouldShowLoadingStatus(callStatus: CallStatus, callIntent: CallIntent, callType: String): Boolean {
    return callStatus == CallStatus.RECONNECTING ||
            (callIntent.action != CallIntent.Action.START_CALL && callStatus != CallStatus.DISCONNECTED) ||
            (callType != CallType.ONE_ON_ONE.type && callStatus != CallStatus.DISCONNECTED)
}