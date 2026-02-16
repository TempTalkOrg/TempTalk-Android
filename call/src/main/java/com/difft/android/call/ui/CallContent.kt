package com.difft.android.call.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.handler.InviteCallHandler
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room
import org.difft.android.libraries.denoise_filter.DenoisePluginAudioProcessor

/**
 * 通话内容主入口组件
 * 
 * @param room 房间对象
 * @param viewModel 通话 ViewModel
 * @param audioSwitchHandler 音频切换处理器
 * @param isUserSharingScreen 用户是否在分享屏幕
 * @param callConfig 通话配置
 * @param callIntent 通话 Intent
 * @param callRole 通话角色
 * @param conversationId 会话 ID
 * @param autoHideTimeout 自动隐藏超时时间
 * @param muteOtherEnabled 是否允许静音其他人
 * @param audioProcessor 音频处理器
 * @param onScreenClick 屏幕点击回调
 * @param onCallTypeChanged 通话类型变化回调
 * @param onInviteUsersClick 邀请用户点击回调
 * @param onWindowZoomOutClick 窗口缩小点击回调
 * @param onExitClick 退出通话点击回调
 * @param onInviteViewAction 会议邀请视图状态回调
 * @param onBottomCallEndAction 底部通话结束操作回调
 */
@Composable
fun CallContent(
    room: Room? = null,
    viewModel: LCallViewModel,
    audioSwitchHandler: AudioSwitchHandler? = null,
    inviteCallHandler: InviteCallHandler? = null,
    isUserSharingScreen: Boolean = false,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    autoHideTimeout: Long,
    muteOtherEnabled: Boolean,
    audioProcessor: DenoisePluginAudioProcessor,
    onScreenClick: () -> Unit,
    onCallTypeChanged: (String) -> Unit,
    onInviteUsersClick: () -> Unit,
    onWindowZoomOutClick: () -> Unit,
    onInviteViewAction: (InviteViewState) -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit,
    onBottomCallEndAction: (BottomCallEndAction) -> Unit
) {
    val currentCallType by viewModel.callType.collectAsState()

    LaunchedEffect(currentCallType) {
        onCallTypeChanged(currentCallType)
    }

    DifftTheme(darkTheme = true) {
        val systemUiController = rememberSystemUiController()
        val backgroundElevateColor = DifftTheme.colors.backgroundElevate

        // 设置 systembar 颜色为 darkTheme 模式
        SideEffect {
            systemUiController.setStatusBarColor(
                color = backgroundElevateColor,
                darkIcons = false // darkTheme 使用浅色图标
            )
            systemUiController.setNavigationBarColor(
                color = backgroundElevateColor,
                darkIcons = false // darkTheme 使用浅色图标
            )
            // 隐藏底部系统导航栏，避免影响 insets 状态
            systemUiController.isNavigationBarVisible = false
        }

        CallContentContainer(
            room = room,
            currentCallType = currentCallType,
            viewModel = viewModel,
            audioSwitchHandler = audioSwitchHandler,
            inviteCallHandler = inviteCallHandler,
            isUserSharingScreen = isUserSharingScreen,
            callConfig = callConfig,
            callIntent = callIntent,
            callRole = callRole,
            conversationId = conversationId,
            autoHideTimeout = autoHideTimeout,
            muteOtherEnabled = muteOtherEnabled,
            audioProcessor = audioProcessor,
            onScreenClick = onScreenClick,
            onInviteUsersClick = onInviteUsersClick,
            onWindowZoomOutClick = onWindowZoomOutClick,
            onInviteViewAction = onInviteViewAction,
            onExitClick = onExitClick,
            onBottomCallEndAction = onBottomCallEndAction
        )
    }
}

/**
 * 通话内容容器
 */
@Composable
private fun CallContentContainer(
    room: Room?,
    currentCallType: String,
    viewModel: LCallViewModel,
    audioSwitchHandler: AudioSwitchHandler?,
    inviteCallHandler: InviteCallHandler?,
    isUserSharingScreen: Boolean,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    autoHideTimeout: Long,
    muteOtherEnabled: Boolean,
    audioProcessor: DenoisePluginAudioProcessor,
    onScreenClick: () -> Unit,
    onInviteUsersClick: () -> Unit,
    onWindowZoomOutClick: () -> Unit,
    onInviteViewAction: (InviteViewState) -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit,
    onBottomCallEndAction: (BottomCallEndAction) -> Unit
) {
    ConstraintLayout(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onScreenClick() }
    ) {
        val (speakerView) = createRefs()
        room?.let {
            when (currentCallType) {
                CallType.ONE_ON_ONE.type -> {
                    OneOnOneCallContent(
                        room = room,
                        viewModel = viewModel,
                        audioSwitchHandler = audioSwitchHandler,
                        inviteCallHandler = inviteCallHandler,
                        isUserSharingScreen = isUserSharingScreen,
                        callConfig = callConfig,
                        callIntent = callIntent,
                        callRole = callRole,
                        conversationId = conversationId,
                        autoHideTimeout = autoHideTimeout,
                        muteOtherEnabled = muteOtherEnabled,
                        audioProcessor = audioProcessor,
                        onInviteUsersClick = onInviteUsersClick,
                        onInviteViewAction = onInviteViewAction,
                        onWindowZoomOutClick = onWindowZoomOutClick,
                        onExitClick = onExitClick,
                        modifier = Modifier.constrainAs(speakerView) {
                            top.linkTo(parent.top)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            bottom.linkTo(parent.bottom)
                            width = Dimension.fillToConstraints
                            height = Dimension.fillToConstraints
                        }
                    )
                }
                else -> {
                    MultiParticipantCallContent(
                        room = room,
                        viewModel = viewModel,
                        audioSwitchHandler = audioSwitchHandler,
                        inviteCallHandler = inviteCallHandler,
                        isUserSharingScreen = isUserSharingScreen,
                        callConfig = callConfig,
                        callIntent = callIntent,
                        callRole = callRole,
                        conversationId = conversationId,
                        autoHideTimeout = autoHideTimeout,
                        muteOtherEnabled = muteOtherEnabled,
                        audioProcessor = audioProcessor,
                        onInviteUsersClick = onInviteUsersClick,
                        onWindowZoomOutClick = onWindowZoomOutClick,
                        onExitClick = onExitClick,
                        onInviteViewAction = onInviteViewAction,
                        onBottomCallEndAction = onBottomCallEndAction,
                        modifier = Modifier.constrainAs(speakerView) {
                            top.linkTo(parent.top)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            bottom.linkTo(parent.bottom)
                            width = Dimension.fillToConstraints
                            height = Dimension.fillToConstraints
                        }
                    )
                }
            }
        }
    }
}

/**
 * 1v1 通话内容
 */
@Composable
private fun OneOnOneCallContent(
    room: Room,
    viewModel: LCallViewModel,
    audioSwitchHandler: AudioSwitchHandler?,
    inviteCallHandler: InviteCallHandler?,
    isUserSharingScreen: Boolean,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    autoHideTimeout: Long,
    muteOtherEnabled: Boolean,
    audioProcessor: DenoisePluginAudioProcessor,
    onInviteUsersClick: () -> Unit,
    onInviteViewAction: (InviteViewState) -> Unit,
    onWindowZoomOutClick: () -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit,
    modifier: Modifier = Modifier
) {
    CallSurface(modifier = modifier) {
        SingleParticipantCallPage(
            viewModel = viewModel,
            room = room,
            autoHideTimeout = autoHideTimeout,
            callConfig = callConfig,
            conversationId = conversationId,
            callRole = callRole
        )
        CommonCallOverlays(
            viewModel = viewModel,
            isOneVOneCall = true,
            isUserSharingScreen = isUserSharingScreen,
            audioSwitchHandler = audioSwitchHandler,
            callConfig = callConfig,
            callIntent = callIntent,
            callRole = callRole,
            conversationId = conversationId,
            muteOtherEnabled = muteOtherEnabled,
            audioProcessor = audioProcessor,
            onInviteUsersClick = onInviteUsersClick,
            onWindowZoomOutClick = onWindowZoomOutClick,
            onExitClick = onExitClick
        )
        // 会议邀请组件
        MeetingInviteScreen(
            callViewModel = viewModel,
            inviteCallHandler = inviteCallHandler,
            onInviteViewAction = onInviteViewAction
        )
    }
}

/**
 * 多人通话内容
 */
@Composable
private fun MultiParticipantCallContent(
    room: Room,
    viewModel: LCallViewModel,
    audioSwitchHandler: AudioSwitchHandler?,
    inviteCallHandler: InviteCallHandler?,
    isUserSharingScreen: Boolean,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    autoHideTimeout: Long,
    muteOtherEnabled: Boolean,
    audioProcessor: DenoisePluginAudioProcessor,
    onInviteUsersClick: () -> Unit,
    onInviteViewAction: (InviteViewState) -> Unit,
    onWindowZoomOutClick: () -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit,
    onBottomCallEndAction: (BottomCallEndAction) -> Unit,
    modifier: Modifier = Modifier
) {
    CallSurface(modifier = modifier) {
        MultiParticipantCallPage(
            viewModel = viewModel,
            room = room,
            muteOtherEnabled = muteOtherEnabled,
            autoHideTimeout = autoHideTimeout,
            callConfig = callConfig
        )
        CommonCallOverlays(
            viewModel = viewModel,
            isOneVOneCall = false,
            isUserSharingScreen = isUserSharingScreen,
            audioSwitchHandler = audioSwitchHandler,
            callConfig = callConfig,
            callIntent = callIntent,
            callRole = callRole,
            conversationId = conversationId,
            muteOtherEnabled = muteOtherEnabled,
            audioProcessor = audioProcessor,
            onInviteUsersClick = onInviteUsersClick,
            onWindowZoomOutClick = onWindowZoomOutClick,
            onExitClick = onExitClick
        )
        // 多人通话特有的组件
        ShowBottomCallEndView(
            viewModel,
            onDismiss = {
                viewModel.callUiController.setShowBottomCallEndViewEnable(false)
                viewModel.callUiController.setShowBottomToolBarViewEnabled(true)
            },
            onClickItem = onBottomCallEndAction
        )
        // 会议邀请组件
        MeetingInviteScreen(
            callViewModel = viewModel,
            inviteCallHandler = inviteCallHandler,
            onInviteViewAction = onInviteViewAction
        )
    }
}

/**
 * 公共的通话 Surface 容器
 */
@Composable
private fun CallSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = DifftTheme.colors.backgroundElevate
    ) {
        PreloadCallPainters()
        content()
    }
}

/**
 * 公共的通话覆盖层组件（顶部/底部控制栏、举手视图、工具栏等）
 */
@Composable
private fun CommonCallOverlays(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    audioSwitchHandler: AudioSwitchHandler?,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    muteOtherEnabled: Boolean,
    audioProcessor: DenoisePluginAudioProcessor,
    onInviteUsersClick: () -> Unit,
    onWindowZoomOutClick: () -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit
) {
    RenderTopAndBottomOverlays(
        viewModel = viewModel,
        isOneVOneCall = isOneVOneCall,
        isUserSharingScreen = isUserSharingScreen,
        audioSwitchHandler = audioSwitchHandler,
        callConfig = callConfig,
        callIntent = callIntent,
        callRole = callRole,
        conversationId = conversationId,
        onWindowZoomOutClick = onWindowZoomOutClick,
        onExitClick = onExitClick
    )
    ShowHandsUpBottomView(
        viewModel,
        onDismiss = { viewModel.callUiController.setShowHandsUpBottomViewEnabled(false) }
    )
    ShowItemsBottomView(
        viewModel,
        isOneVOneCall = isOneVOneCall,
        onDismiss = { viewModel.callUiController.setShowToolBarBottomViewEnable(false) },
        deNoiseCallBack = { enable -> audioProcessor.setEnabled(enable) },
        handleInviteUsersClick = onInviteUsersClick
    )
    ShowParticipantsListView(
        viewModel,
        muteOtherEnabled,
        handleInviteUsersClick = onInviteUsersClick
    )
    ShowCriticalAlertConfirmView(
        viewModel,
        onDismiss = { viewModel.callUiController.setShowCriticalAlertConfirmViewEnabled(false) },
        sendCriticalAlert = { gid -> viewModel.handleCriticalAlertNew(gid, callback = { isSuccess ->
            viewModel.callUiController.setShowCriticalAlertConfirmViewEnabled(!isSuccess)
        }) }
    )
}

/**
 * 渲染顶部和底部覆盖层
 */
@Composable
private fun RenderTopAndBottomOverlays(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    audioSwitchHandler: AudioSwitchHandler?,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    onWindowZoomOutClick: () -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit
) {
    val showTopStatusViewEnabled by viewModel.callUiController.showTopStatusViewEnabled.collectAsState(true)
    val showBottomToolBarViewEnabled by viewModel.callUiController.showBottomToolBarViewEnabled.collectAsState(true)
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)

    // 顶部悬浮控件布局逻辑
    MainPageWithTopStatusView(
        viewModel = viewModel,
        isInPipMode = isInPipMode,
        isOneVOneCall = isOneVOneCall,
        showTopStatusViewEnabled = showTopStatusViewEnabled,
        isUserSharingScreen = isUserSharingScreen,
        callConfig = callConfig,
        callIntent = callIntent,
        windowZoomOutAction = onWindowZoomOutClick
    )

    if (!isInPipMode) {
        // 底部悬浮控件布局逻辑
        MainPageWithBottomControlView(
            viewModel = viewModel,
            isOneVOneCall = isOneVOneCall,
            showBottomToolBarViewEnabled = showBottomToolBarViewEnabled,
            isUserSharingScreen = isUserSharingScreen,
            audioSwitchHandler = audioSwitchHandler,
            endCallAction = { callType, callEndType ->
                val callExitParams = CallExitParams(
                    viewModel.getRoomId(),
                    callIntent.callerId,
                    callRole,
                    callType,
                    conversationId
                )
                onExitClick(callExitParams, callEndType)
            }
        )
    }
}

@Composable
private fun PreloadCallPainters() {
    // 进入页面即预加载，避免首次显示时阻塞主线程
    painterResource(id = R.drawable.chat_ic_window_zoom_out)
    painterResource(id = R.drawable.ant_design_loading_outlined)
    painterResource(id = R.drawable.gg_spinner_alt)

    // 底部控制栏资源预加载
    painterResource(id = R.drawable.call_btn_microphone_open)
    painterResource(id = R.drawable.call_btn_microphone_close)
    painterResource(id = R.drawable.call_btn_camera_open)
    painterResource(id = R.drawable.call_btn_camera_close)
    painterResource(id = R.drawable.call_btn_volume_phone)
    painterResource(id = R.drawable.call_btn_volume_speaker)
    painterResource(id = R.drawable.call_btn_volume_headphones)
    painterResource(id = R.drawable.call_btn_volume_airpod)
    painterResource(id = R.drawable.users)
    painterResource(id = R.drawable.call_btn_tabler_dots)
    painterResource(id = R.drawable.call_btn_hangup)
    painterResource(id = R.drawable.call_btn_tabler_chevron_right)
    painterResource(id = R.drawable.call_btn_mingcute_exit_line)
}
