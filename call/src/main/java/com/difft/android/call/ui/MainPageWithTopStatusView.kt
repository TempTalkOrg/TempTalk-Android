package com.difft.android.call.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
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
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.WeakNetworkBanner
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
    windowZoomOutAction: () -> Unit,
    onE2eeHintClick: () -> Unit,
) {
    val showTopStatusState by viewModel.callUiController.showTopStatusViewEnabled.collectAsState(true)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val callDuration by viewModel.timerManager.callDurationText.collectAsState("00:00")
    // 1v1 进入 CONNECTED 后计时还要等对端 RTC 通道就绪，这段时间悬浮条显示「连接中…」,
    // title 第二行保持静态 E2EE 文案而不是静止的 00:00。
    val callTimerRunning by viewModel.timerManager.callTimerRunning.collectAsState(false)
    val callStatus by viewModel.callStatus.collectAsState()
    val mediaSendIssue by viewModel.mediaSendIssue.collectAsState()
    val countDownEnabled by viewModel.timerManager.countDownEnabled.collectAsState(false)
    val callType by viewModel.callType.collectAsState()
    val screenSharingUser by viewModel.screenSharingUser.collectAsState()
    // Independent collectAsState of `participants` — StateFlow supports multiple collectors
    // natively, this is not a leak; kept separate from any sibling composable's collector so
    // this file's recomposition scope stays decoupled.
    val participants by viewModel.participants.collectAsState()
    // Title bar is a light node, so a collector here is cheap — unlike the participant tiles,
    // where the badge owns its own collector to keep the video renderers out of the scope.
    val networkQuality by viewModel.callUiController.networkQuality.collectAsState()

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

    var oneOnOnePeerName: String? by remember { mutableStateOf(null) }

    LaunchedEffect(isOneVOneCall, participants) {
        if (!isOneVOneCall) return@LaunchedEffect
        viewModel.getOneOnOnePeerId()?.let { peerId ->
            oneOnOnePeerName = withContext(Dispatchers.IO) {
                contactorCacheManager.getDisplayNameById(peerId)
            }
        }
    }

    val statusNotification = callStatusNotification(
        callStatus = callStatus,
        callType = callType,
        callIntent = callIntent,
        callTimerRunning = callTimerRunning,
        mediaSendIssue = mediaSendIssue,
        // Headcount, not `isOneVOneCall`: the peer banner and the tile badge split on how many
        // people are in the call, so a 2-person group call must take the banner branch too. This
        // list is exactly `localParticipant + room.remoteParticipants`, so ringing invitees are
        // correctly excluded.
        weakNetwork = WeakNetworkBanner.resolve(networkQuality, participants.size),
    )
    val isTopVisible = (isOneVOneCall && !isUserSharingScreen) || showTopStatusState

    // 关键：`tapInterceptor` 只挂在内层 [TopStatusBar]（52dp 高，与全 app 标题栏一致），而不是
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
                        .padding(
                            top = LCallUiConstants.TOP_BAR_MARGIN_TOP_DP.dp,
                            bottom = LCallUiConstants.TOP_BAR_MARGIN_BOTTOM_DP.dp,
                        )
                } else {
                    Modifier.padding(bottom = LCallUiConstants.TOP_BAR_MARGIN_BOTTOM_DP.dp)
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
            isTopVisible = isTopVisible,
            isLandscape = isLandscape,
            isUserSharingScreen = isUserSharingScreen,
            windowZoomOutAction = windowZoomOutAction,
            onE2eeHintClick = onE2eeHintClick,
        ) {
            CallStatusContent(
                callStatus = callStatus,
                callType = callType,
                callDuration = callDuration,
                callTimerRunning = callTimerRunning,
                isOneVOneCall = isOneVOneCall,
                isUserSharingScreen = isUserSharingScreen,
                isInPipMode = isInPipMode,
                screenSharingUser = screenSharingUser,
                screenShareUserName = screenShareUserName,
                oneOnOnePeerName = oneOnOnePeerName,
                countDownEnabled = countDownEnabled,
                viewModel = viewModel,
                callConfig = callConfig,
            )
        }

        // 悬浮条不进 PiP:PiP 窗口装不下 notification 胶囊,title 的 E2EE/时长仍在。
        if (!isInPipMode) {
            CallStatusNotificationBar(
                status = statusNotification,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        val criticalAlertScope = rememberCoroutineScope()
        if (viewModel.is1v1ShowCriticalAlertEnable(callStatus)) {
            // 规则上与悬浮条不并存(critical alert 只在 1v1 等待接听阶段出现,该阶段悬浮条为空),
            // 8dp 为防御性间距——将来若新增可并存的状态,两条不至于粘连。
            Box(modifier = Modifier.padding(top = 8.dp)) {
                CallCriticalAlertView(
                    clicked = {
                        criticalAlertScope.launch { viewModel.handleCriticalAlertNew() }
                    }
                )
            }
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
    isTopVisible: Boolean,
    isLandscape: Boolean,
    isUserSharingScreen: Boolean,
    windowZoomOutAction: () -> Unit,
    onE2eeHintClick: () -> Unit,
    statusContent: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LCallUiConstants.TOP_BAR_HEIGHT_DP.dp)
            .then(
                if (isUserSharingScreen) Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent
                        ),
                    )
                ) else Modifier
            ),
    ) {
        if (!isInPipMode) {
            val controlPadding = if (!isLandscape) 16.dp else 18.dp
            Box(
                modifier = Modifier
                    .testTag("call_topbar_zoom_out")
                    .align(Alignment.CenterStart)
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

        // Screen-sharing excludes the E2EE surface entirely. isTopVisible prevents hidden-header
        // taps from stealing tapInterceptor's gesture; !isInPipMode mirrors every existing :call
        // sheet. The `clickable` modifier is mounted ONLY when this is true — `clickable(enabled
        // = false)` still unconditionally consumes the down/up pointer events (Compose Foundation),
        // which would silently swallow taps instead of letting them fall through to the ancestor
        // tapInterceptor.
        val canClickE2eeHeader = isTopVisible && !isInPipMode && !isUserSharingScreen
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .testTag("call_topbar_status_click_target")
                .then(
                    if (canClickE2eeHeader) {
                        Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onE2eeHintClick,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            statusContent()
        }
    }
}

/**
 * 通话状态内容：title 负责名字、E2EE/时长与「等待接听…」(呼叫进程,含 E2EE 轮换,PR#1125
 * 需求、与 Mac 一致);连接健康度状态(连接中/重连/连接中断/发送异常)由
 * [CallStatusNotificationBar] 悬浮条承载,联通即消失。
 */
@Composable
private fun CallStatusContent(
    callStatus: CallStatus,
    callType: String,
    callDuration: String,
    callTimerRunning: Boolean,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    isInPipMode: Boolean,
    screenSharingUser: Participant?,
    screenShareUserName: String?,
    oneOnOnePeerName: String?,
    countDownEnabled: Boolean,
    viewModel: LCallViewModel,
    callConfig: CallConfig,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // callTimerRunning 是「通话中途」的判别:RECONNECTING/SWITCHING_SERVER 等中途瞬态
        // 也保持名字+时长常驻(状态在悬浮条,与时长共存),不回落到接通前的 E2EE 占位。
        if (callStatus == CallStatus.CONNECTED || callStatus == CallStatus.RECONNECTED || callTimerRunning) {
            ConnectedStatusContent(
                callDuration = callDuration,
                isOneVOneCall = isOneVOneCall,
                isUserSharingScreen = isUserSharingScreen,
                screenSharingUser = screenSharingUser,
                screenShareUserName = screenShareUserName,
                oneOnOnePeerName = oneOnOnePeerName,
                countDownEnabled = countDownEnabled,
                viewModel = viewModel,
                callConfig = callConfig,
            )
        } else if (callType == CallType.ONE_ON_ONE.type && callStatus == CallStatus.CALLING) {
            AlternatingCallStatusText(
                primaryLabel = ResUtils.getString(R.string.call_status_calling),
                shouldAnimate = rememberShouldAnimateCallStatus(isInPipMode),
            )
        } else {
            // 连接类状态在悬浮条,title 静态显示 E2EE 提示。
            EncryptedStatusRow(
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = DifftTheme.colors.textPrimary),
                testTag = "call_topbar_status_text",
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
    oneOnOnePeerName: String?,
    countDownEnabled: Boolean,
    viewModel: LCallViewModel,
    callConfig: CallConfig,
) {
    // 计时未就绪(1v1 等对端 RTC)时第二行保持 E2EE 文案;时长开始后常驻,不再被任何
    // 状态顶掉——瞬态状态(含 media send issue)都在悬浮条里,与时长共存。
    val callTimerRunning by viewModel.timerManager.callTimerRunning.collectAsState(false)

    if (isUserSharingScreen && screenSharingUser?.identity?.value != null) {
        screenShareUserName?.let { name ->
            // Append the duration only once the timer runs: `callDuration` is the raw ticker
            // text, so during the media-ready gate it would read as a frozen "00:00" (the exact
            // bug the timer gate exists for) — and the floating pill already says "Connecting…".
            val shareTitle = "${StringUtil.truncateWithEllipsis(name, 14)}${ResUtils.getString(R.string.call_screen_sharing_title)}"
            Text(
                modifier = Modifier.testTag("call_topbar_call_duration"),
                text = if (callTimerRunning) "$shareTitle $callDuration" else shareTitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else if (!isUserSharingScreen) {
        // Gated on isUserSharingScreen, not isOneVOneCall: during the transient where sharing has
        // started but the sharer's identity hasn't resolved yet, neither branch renders — showing
        // no title is preferred over showing an ambiguous one.
        // Pre-truncate BOTH branches: maxLines/ellipsis alone don't bound Compose text-measurement
        // cost for long strings (main-thread ANR, see the getCallRoomName precedent).
        val headerTitle = if (isOneVOneCall) oneOnOnePeerName?.let { StringUtil.truncateWithEllipsis(it, 25) } else StringUtil.truncateWithEllipsis(viewModel.getCallRoomName(), 25)
        if (!headerTitle.isNullOrEmpty()) {
            Text(
                modifier = Modifier.testTag("call_topbar_room_name"),
                text = headerTitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (!isUserSharingScreen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Literal Color.White (not DifftTheme.colors.*) is intentional: this subtree is
            // forced dark-theme (CallContent.kt:90) and every other text/icon in this function
            // already uses literal colors.
            Icon(
                imageVector = ImageVector.vectorResource(id = com.difft.android.base.R.drawable.base_tabler_lock),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                modifier = Modifier.testTag("call_topbar_call_duration"),
                text = if (callTimerRunning) callDuration else ResUtils.getString(R.string.call_status_encrypted),
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

