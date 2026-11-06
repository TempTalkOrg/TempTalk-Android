package com.difft.android.call.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.difft.android.call.BuildConfig
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.BottomCallEndAction
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.handler.InviteCallHandler
import com.difft.android.call.layout.Posture
import com.difft.android.call.ui.actionbar.rememberCallSideRail
import com.difft.android.call.ui.actionbar.sideRailEndPadding
import com.difft.android.call.ui.barrage.BubbleOverlayWindowHost
import com.difft.android.call.ui.invite.InviteViewState
import com.difft.android.call.ui.layout.CallLayoutReportKey
import com.difft.android.call.ui.layout.CallLayoutReporter
import com.difft.android.call.ui.layout.CallStage
import com.difft.android.call.ui.layout.LocalCallLayoutReportKey
import com.difft.android.call.ui.layout.LocalCallFrameSizeSink
import com.difft.android.call.ui.screenshare.ScreenShareSpeakerBanner
import com.difft.android.call.ui.screenshare.LocalCallStatusBarHidden
import com.difft.android.call.ui.screenshare.getActivity
import com.difft.android.base.widget.DialogAnchor
import com.difft.android.call.ui.tabletop.LocalCallTabletop
import com.difft.android.call.ui.tabletop.TabletopGeometry
import com.difft.android.call.ui.tabletop.planTabletop
import com.difft.android.call.ui.tabletop.rememberGatedFold
import com.difft.android.call.ui.tabletop.rememberShareHalfOpen
import com.difft.android.call.ui.tabletop.rememberTabletopBottomInsetDp
import kotlin.math.roundToInt
import com.difft.android.call.ui.layout.gatedFoldDp
import com.difft.android.call.ui.actionbar.rememberCallWindowSizeDp
import com.difft.android.call.layout.postureOf
import androidx.compose.ui.platform.LocalDensity
import com.difft.android.call.layout.Size as LayoutSize
import com.difft.android.call.ui.invite.MeetingInviteScreen
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import io.livekit.android.room.Room

/**
 * 通话内容主入口组件
 * 
 * @param room 房间对象
 * @param viewModel 通话 ViewModel
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
        val pagePrimaryColor = DifftTheme.colors.backgroundPagePrimary

        SideEffect {
            systemUiController.setStatusBarColor(
                color = pagePrimaryColor,
                darkIcons = false
            )
        }

        // The half-open fold, debounced once here and reported to the layout (design §4.5).
        val gatedFold = rememberGatedFold(viewModel.callUiController)
        val window = rememberCallWindowSizeDp()
        val windowDp = LayoutSize(window.width.toDouble(), window.height.toDouble())
        val navBottomDp = rememberTabletopBottomInsetDp()
        val reportedFold = remember(gatedFold, windowDp, navBottomDp) { gatedFoldDp(gatedFold, windowDp, navBottomDp) }
        // A flip phone standing half open keeps a share upright above the fold (design decision 38):
        // the activity owns the share's orientation lock, so it hears the fold the layout gets, held
        // through the rotation that lock causes.
        val halfOpen = rememberShareHalfOpen(reported = reportedFold != null)
        val callActivity = LocalContext.current.getActivity() as? LCallActivity
        LaunchedEffect(halfOpen, callActivity) { callActivity?.onFoldHalfOpenChanged(halfOpen) }
        val layoutState by viewModel.layout.state.collectAsState()
        // What tabletop consumers draw around: the plan's fold and dock, null outside the posture.
        val tabletop = planTabletop(layoutState.plan, viewModel.layout.lastInput?.fold, windowDp, LocalDensity.current.density)
        // One owner of the status bar (design §4.4): hidden with the controls, half open flat (from
        // the fold being reported, so it does not wait a turn for the plan), and through a share.
        val statusBarHidden = isUserSharingScreen || layoutState.controlsHidden ||
            postureOf(reportedFold) == Posture.TABLETOP
        CallStatusBarEffect(statusBarHidden)
        CallDialogAnchorEffect(tabletop)

        CompositionLocalProvider(
            LocalCallTabletop provides tabletop,
            LocalCallFrameSizeSink provides viewModel.layoutInputs,
            LocalCallLayoutReportKey provides remember { CallLayoutReportKey() },
            LocalCallStatusBarHidden provides statusBarHidden,
        ) {
            CallLayoutReporter(
                controller = viewModel.layout,
                uiController = viewModel.callUiController,
                isGroup = currentCallType != CallType.ONE_ON_ONE.type,
                statusBarHidden = statusBarHidden,
                gatedFold = gatedFold,
            )
            CallContentContainer(
                room = room,
                currentCallType = currentCallType,
                viewModel = viewModel,
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
}

/**
 * 通话内容容器
 */
@Composable
private fun CallContentContainer(
    room: Room?,
    currentCallType: String,
    viewModel: LCallViewModel,
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
            .testTag("call_root")
            .pointerInput(isUserSharingScreen) {
                if (!isUserSharingScreen) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        viewModel.callUiController.notifyScreenShareInteraction()
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onScreenClick() }
    ) {
        room?.let {
            CallStageContent(
                room = room,
                viewModel = viewModel,
                inviteCallHandler = inviteCallHandler,
                isOneVOneCall = currentCallType == CallType.ONE_ON_ONE.type,
                isUserSharingScreen = isUserSharingScreen,
                callConfig = callConfig,
                callIntent = callIntent,
                callRole = callRole,
                conversationId = conversationId,
                autoHideTimeout = autoHideTimeout,
                muteOtherEnabled = muteOtherEnabled,
                onInviteUsersClick = onInviteUsersClick,
                onInviteViewAction = onInviteViewAction,
                onWindowZoomOutClick = onWindowZoomOutClick,
                onExitClick = onExitClick,
                onBottomCallEndAction = onBottomCallEndAction,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * The call page: the layout stage draws every mode, the chrome and sheets sit above it. In picture
 * in picture only the stage and its own overlay show (guide §5.3).
 */
@Composable
private fun CallStageContent(
    room: Room,
    viewModel: LCallViewModel,
    inviteCallHandler: InviteCallHandler?,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    autoHideTimeout: Long,
    muteOtherEnabled: Boolean,
    onInviteUsersClick: () -> Unit,
    onInviteViewAction: (InviteViewState) -> Unit,
    onWindowZoomOutClick: () -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit,
    onBottomCallEndAction: (BottomCallEndAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState()
    CallSurface(modifier = modifier) {
        CallStage(
            viewModel = viewModel,
            room = room,
            conversationId = conversationId,
            muteOtherEnabled = muteOtherEnabled,
        )
        // Kept composed through picture in picture, only not drawn; min constraints pass through as
        // the surface's own box passes them.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (isInPipMode) 0f else 1f },
            propagateMinConstraints = true,
        ) {
            CallBarrageMessageSection(
                viewModel = viewModel,
                callConfig = callConfig,
                autoHideTimeout = autoHideTimeout,
                isOneVOneCall = isOneVOneCall,
                room = room,
            )
            // 气泡飘动层：通过 WindowManager 挂在独立的 APPLICATION_PANEL 子窗口里，
            // 主界面任何 recomposition / remeasure 都影响不到它。
            BubbleOverlayWindowHost(viewModel = viewModel)
            CommonCallOverlays(
                viewModel = viewModel,
                isOneVOneCall = isOneVOneCall,
                isUserSharingScreen = isUserSharingScreen,
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
                // The banner and its drag range stay clear of the side rail (#1451) and above the tabletop fold.
                ScreenShareSpeakerBanner(viewModel = viewModel, modifier = Modifier.speakerBannerBounds(viewModel))
            }
            if (!isOneVOneCall) {
                ShowBottomCallEndView(
                    viewModel,
                    onDismiss = {
                        viewModel.callUiController.setShowBottomCallEndViewEnable(false)
                        viewModel.callUiController.setShowBottomToolBarViewEnabled(true)
                    },
                    onClickItem = onBottomCallEndAction
                )
            }
            // 会议邀请组件
            MeetingInviteScreen(
                callViewModel = viewModel,
                inviteCallHandler = inviteCallHandler,
                onInviteViewAction = onInviteViewAction
            )
        }
    }
}

/**
 * The speaker banner's region: beside the side rail, and above the fold while the plan is in the
 * tabletop posture. The window is edge to edge, so the fold's window dp are the surface's.
 */
@Composable
private fun Modifier.speakerBannerBounds(viewModel: LCallViewModel): Modifier {
    val state by viewModel.layout.state.collectAsState()
    val foldTop = viewModel.layout.lastInput?.fold?.y?.takeIf { state.plan?.posture == Posture.TABLETOP }
    val bounds = sideRailEndPadding(rememberCallSideRail())
    // Top-anchored inside a surface that forces its full size: wrapContentHeight frees the height.
    return if (foldTop == null) bounds else bounds.fillMaxSize().wrapContentHeight(Alignment.Top, unbounded = false).height(foldTop.dp)
}

/**
 * Centres the page's dialogs in the region below the fold while [tabletop] is shown, instead of
 * on the fold; cleared with the posture and when the page leaves.
 */
@Composable
private fun CallDialogAnchorEffect(tabletop: TabletopGeometry?) {
    val activity = LocalContext.current.getActivity() ?: return
    val centerYPx = tabletop?.let { (it.dockTopDp * it.density).roundToInt().let { top -> (top + it.heightPx) / 2 } }
    DisposableEffect(activity, centerYPx) {
        DialogAnchor.set(activity, centerYPx)
        onDispose { DialogAnchor.set(activity, null) }
    }
}

/**
 * Shows or hides the status bar (swipe brings it back for a moment); the navigation bar stays as
 * the window keeps it, hidden.
 */
@Composable
private fun CallStatusBarEffect(hidden: Boolean) {
    val activity = LocalContext.current.getActivity() ?: return
    DisposableEffect(activity, hidden) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
        }
        // Leaving the call page (or this effect) hands the status bar back shown.
        onDispose {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

/**
 * 公共的通话 Surface 容器
 */
@Composable
internal fun CallSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = DifftTheme.colors.backgroundPagePrimary
    ) {
        content()
    }
}
