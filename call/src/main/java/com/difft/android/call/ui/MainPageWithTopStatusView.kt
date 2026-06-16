package com.difft.android.call.ui

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallType
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.ui.alert.CallCriticalAlertView
import com.difft.android.call.util.StringUtil
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainPageWithTopStatusView(
    viewModel: LCallViewModel,
    isInPipMode: Boolean,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    callConfig: CallConfig,
    callIntent: CallIntent,
    windowZoomOutAction: () -> Unit
) {
    val showTopStatusState by viewModel.callUiController.showTopStatusViewEnabled.collectAsState(true)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val callDuration by viewModel.timerManager.callDurationText.collectAsState("00:00")
    val callStatus by viewModel.callStatus.collectAsState()
    val countDownEnabled by viewModel.timerManager.countDownEnabled.collectAsState(false)
    val callType by viewModel.callType.collectAsState()
    val screenSharingUser by viewModel.screenSharingUser.collectAsState()

    val contactorCacheManager = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance).contactorCacheManager
    }

    var screenShareUserName: String? by remember { mutableStateOf(null) }

    LaunchedEffect(screenSharingUser) {
        screenSharingUser?.let {
            it.identity?.value?.let { identityId ->
                screenShareUserName = withContext(Dispatchers.IO) {
                    contactorCacheManager.getDisplayNameById(identityId)
                }
            }
        }
    }

    val shouldShowLoading = shouldShowLoadingStatus(callStatus, callIntent, callType)
    // 仅在需要展示 loading 时才创建无限旋转动画。否则整通话期间动画帧循环会
    // 持续驱动主线程 recomposition，造成卡顿/ANR（见 MainPageWithTopStatusView
    // 加载动画 ANR）。
    val rotationAngle by if (shouldShowLoading) {
        val infiniteTransition = rememberInfiniteTransition(label = "loadingRotation")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
            ),
            label = "loadingRotationValue"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val isTopVisible = (isOneVOneCall && !isUserSharingScreen) || showTopStatusState

    // 关键：`tapInterceptor` 只挂在内层 [TopStatusBar]（约 62dp 高），而不是
    // 整个 Column。该 Column 是 Material `Surface` 的直接子节点，Surface 会把
    // 最小约束传播给子节点，使 Column 实际铺满全屏；若把 pointerInput 挂在
    // 这里，它会覆盖在屏幕共享视频（AndroidView）之上并“捕获”整屏手势区域，
    // 导致控制栏隐藏后整页无法双指缩放。Column 仅保留 `alpha` 做视觉隐藏（不
    // 进入指针命中测试），与底部控制栏的做法保持一致。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!isLandscape || !isUserSharingScreen) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp, bottom = 4.dp)
                } else {
                    Modifier.padding(bottom = 4.dp)
                }
            )
            .alpha(if (isTopVisible) 1f else 0f),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopStatusBar(
            modifier = Modifier.tapInterceptor(enabled = !isTopVisible) {
                viewModel.callUiController.toggleOverlays()
            },
            isInPipMode = isInPipMode,
            isLandscape = isLandscape,
            isUserSharingScreen = isUserSharingScreen,
            windowZoomOutAction = windowZoomOutAction,
        ) {
            CallStatusContent(
                callStatus = callStatus,
                callType = callType,
                callDuration = callDuration,
                isOneVOneCall = isOneVOneCall,
                isUserSharingScreen = isUserSharingScreen,
                screenSharingUser = screenSharingUser,
                screenShareUserName = screenShareUserName,
                countDownEnabled = countDownEnabled,
                shouldShowLoading = shouldShowLoading,
                rotationAngle = rotationAngle,
                viewModel = viewModel,
                callConfig = callConfig,
            )
        }

        val criticalAlertScope = rememberCoroutineScope()
        if (viewModel.is1v1ShowCriticalAlertEnable(callStatus)) {
            CallCriticalAlertView(
                clicked = {
                    criticalAlertScope.launch { viewModel.handleCriticalAlertNew() }
                }
            )
        }
    }
}

/**
 * 顶部状态栏容器：左侧缩小按钮 + 中间状态内容
 */
@Composable
private fun TopStatusBar(
    modifier: Modifier = Modifier,
    isInPipMode: Boolean,
    isLandscape: Boolean,
    isUserSharingScreen: Boolean,
    windowZoomOutAction: () -> Unit,
    statusContent: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .then(
                if (isUserSharingScreen) Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent
                        ),
                    )
                ) else Modifier
            )
            .padding(top = 8.dp),
    ) {
        if (!isInPipMode) {
            val controlPadding = if (!isLandscape) 16.dp else 18.dp
            Box(
                modifier = Modifier
                    .testTag("call_topbar_zoom_out")
                    .align(Alignment.TopStart)
                    .padding(start = controlPadding)
                    .size(44.dp)
                    .clickable(
                        onClick = windowZoomOutAction,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(id = R.drawable.chat_ic_window_zoom_out),
                    contentDescription = "WINDOW_ZOOM_OUT",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center,
        ) {
            statusContent()
        }
    }
}

/**
 * 通话状态内容：根据 callStatus 显示不同信息（通话中/连接中/重连失败等）
 */
@Composable
private fun CallStatusContent(
    callStatus: CallStatus,
    callType: String,
    callDuration: String,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    screenSharingUser: Participant?,
    screenShareUserName: String?,
    countDownEnabled: Boolean,
    shouldShowLoading: Boolean,
    rotationAngle: Float,
    viewModel: LCallViewModel,
    callConfig: CallConfig,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (callStatus == CallStatus.CONNECTED || callStatus == CallStatus.RECONNECTED) {
            ConnectedStatusContent(
                callDuration = callDuration,
                isOneVOneCall = isOneVOneCall,
                isUserSharingScreen = isUserSharingScreen,
                screenSharingUser = screenSharingUser,
                screenShareUserName = screenShareUserName,
                countDownEnabled = countDownEnabled,
                viewModel = viewModel,
                callConfig = callConfig,
            )
        } else {
            DisconnectedStatusContent(
                callStatus = callStatus,
                callType = callType,
                shouldShowLoading = shouldShowLoading,
                rotationAngle = rotationAngle,
            )
        }
    }
}

@Composable
private fun ConnectedStatusContent(
    callDuration: String,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    screenSharingUser: Participant?,
    screenShareUserName: String?,
    countDownEnabled: Boolean,
    viewModel: LCallViewModel,
    callConfig: CallConfig,
) {
    if (isUserSharingScreen && screenSharingUser?.identity?.value != null) {
        screenShareUserName?.let { name ->
            Text(
                modifier = Modifier.testTag("call_topbar_call_duration"),
                text = "${StringUtil.truncateWithEllipsis(name, 14)}${ResUtils.getString(R.string.call_screen_sharing_title)} $callDuration",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else if (!isOneVOneCall) {
        Text(
            modifier = Modifier.testTag("call_topbar_room_name"),
            text = StringUtil.truncateWithEllipsis(viewModel.getCallRoomName(), 25),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (!isUserSharingScreen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.testTag("call_topbar_call_duration"),
                text = callDuration,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (countDownEnabled) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.dp)
                        .height(10.dp)
                        .background(color = Color(0xFF474D57))
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

@Composable
private fun DisconnectedStatusContent(
    callStatus: CallStatus,
    callType: String,
    shouldShowLoading: Boolean,
    rotationAngle: Float,
) {
    if (callType == CallType.ONE_ON_ONE.type && callStatus == CallStatus.CALLING) {
        Text(
            modifier = Modifier.testTag("call_topbar_status_text"),
            text = ResUtils.getString(R.string.call_status_calling),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                color = DifftTheme.colors.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    } else if (shouldShowLoading) {
        val painter = if (callStatus == CallStatus.RECONNECT_FAILED) {
            painterResource(id = R.drawable.gg_spinner_alt)
        } else {
            painterResource(id = R.drawable.ant_design_loading_outlined)
        }

        val status = if (callStatus == CallStatus.RECONNECT_FAILED) {
            ResUtils.getString(R.string.call_disconnected_title)
        } else {
            ResUtils.getString(R.string.call_connecting_title)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                contentScale = ContentScale.Fit,
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rotationAngle)
            )

            Text(
                modifier = Modifier
                    .testTag("call_topbar_status_text")
                    .padding(start = 4.dp),
                text = status,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun shouldShowLoadingStatus(callStatus: CallStatus, callIntent: CallIntent, callType: String): Boolean {
    return callStatus == CallStatus.RECONNECTING ||
            (callIntent.action != CallIntent.Action.START_CALL && callStatus != CallStatus.DISCONNECTED) ||
            (callType != CallType.ONE_ON_ONE.type && callStatus != CallStatus.DISCONNECTED)
}
