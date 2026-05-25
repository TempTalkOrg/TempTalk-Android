package com.difft.android.call.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.difft.android.call.BuildConfig
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.ui.barrage.BubbleOverlayWindowHost
import com.difft.android.call.ui.invite.InviteViewState
import com.difft.android.call.ui.screenshare.ScreenShareSpeakerBanner
import com.difft.android.call.ui.invite.MeetingInviteScreen
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room

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

        SideEffect {
            systemUiController.setStatusBarColor(
                color = backgroundElevateColor,
                darkIcons = false
            )
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
    onScreenClick: () -> Unit,
    onInviteUsersClick: () -> Unit,
    onWindowZoomOutClick: () -> Unit,
    onInviteViewAction: (InviteViewState) -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit,
    onBottomCallEndAction: (BottomCallEndAction) -> Unit
) {
    val configuration = LocalConfiguration.current
    val activity = LocalActivity.current
    val isDualPane = remember(configuration, activity) {
        activity?.let { WindowSizeClassUtil.shouldUseDualPaneLayout(it) } ?: false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
            .testTag("call_root")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        if (viewModel.callUiController.isShareScreening.value) {
                            viewModel.callUiController.notifyScreenShareInteraction()
                        }
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onScreenClick() }
    ) {
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
                        isDualPane = isDualPane,
                        onInviteUsersClick = onInviteUsersClick,
                        onInviteViewAction = onInviteViewAction,
                        onWindowZoomOutClick = onWindowZoomOutClick,
                        onExitClick = onExitClick,
                        modifier = Modifier.fillMaxSize()
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
                        isDualPane = isDualPane,
                        onInviteUsersClick = onInviteUsersClick,
                        onWindowZoomOutClick = onWindowZoomOutClick,
                        onExitClick = onExitClick,
                        onInviteViewAction = onInviteViewAction,
                        onBottomCallEndAction = onBottomCallEndAction,
                        modifier = Modifier.fillMaxSize()
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
    isDualPane: Boolean = false,
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
            callRole = callRole,
            isDualPane = isDualPane,
        )
        // 气泡飘动层：通过 WindowManager 挂在独立的 APPLICATION_PANEL
        // 子窗口里，和主 Activity 窗口走两条独立的 measure/layout/draw
        // 流水线，主界面任何 recomposition / remeasure 都影响不到它。
        BubbleOverlayWindowHost(viewModel = viewModel)
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
            onInviteUsersClick = onInviteUsersClick,
            onWindowZoomOutClick = onWindowZoomOutClick,
            onExitClick = onExitClick
        )
        if (isUserSharingScreen) {
            ScreenShareSpeakerBanner(viewModel = viewModel)
        }
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
    isDualPane: Boolean = false,
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
            callConfig = callConfig,
            isDualPane = isDualPane,
        )
        // 气泡飘动层：通过 WindowManager 挂在独立子窗口里，避免主窗口
        // re-measure 波及气泡。详见 [BubbleOverlayWindowHost]。
        BubbleOverlayWindowHost(viewModel = viewModel)
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
            onInviteUsersClick = onInviteUsersClick,
            onWindowZoomOutClick = onWindowZoomOutClick,
            onExitClick = onExitClick
        )
        if (isUserSharingScreen) {
            ScreenShareSpeakerBanner(viewModel = viewModel)
        }
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
        content()
    }
}
