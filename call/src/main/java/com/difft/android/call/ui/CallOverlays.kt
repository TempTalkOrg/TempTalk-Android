package com.difft.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.difft.android.base.call.CallRole
import com.difft.android.base.ui.compose.e2ee.E2eeInfoSheetDialog
import com.difft.android.base.user.CallConfig
import com.difft.android.call.CallIntent
import com.difft.android.call.ui.alert.ShowCriticalAlertConfirmView
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 公共的通话覆盖层组件（顶部/底部控制栏、工具栏等）
 */
@Composable
fun CommonCallOverlays(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    muteOtherEnabled: Boolean,
    onInviteUsersClick: () -> Unit,
    onWindowZoomOutClick: () -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit
) {
    var showE2eeSheet by remember { mutableStateOf(false) }

    RenderTopAndBottomOverlays(
        viewModel = viewModel,
        isOneVOneCall = isOneVOneCall,
        isUserSharingScreen = isUserSharingScreen,
        callConfig = callConfig,
        callIntent = callIntent,
        callRole = callRole,
        conversationId = conversationId,
        onWindowZoomOutClick = onWindowZoomOutClick,
        onExitClick = onExitClick,
        onE2eeHintClick = { showE2eeSheet = true },
    )
    ShowItemsBottomView(
        viewModel,
        isOneVOneCall = isOneVOneCall,
        onDismiss = { viewModel.callUiController.setShowToolBarBottomViewEnable(false) },
        deNoiseCallBack = { enable -> viewModel.setDeNoiseEnabled(enable) },
        deNoiseModeCallBack = { mode -> viewModel.setDeNoiseMode(mode) },
        voicePresetCallBack = { preset -> viewModel.setVoicePreset(preset) },
        handleInviteUsersClick = onInviteUsersClick
    )
    ShowParticipantsListView(
        viewModel,
        muteOtherEnabled,
        handleInviteUsersClick = onInviteUsersClick
    )
    val criticalAlertScope = rememberCoroutineScope()
    ShowCriticalAlertConfirmView(
        viewModel,
        onDismiss = { viewModel.callUiController.setShowCriticalAlertConfirmViewEnabled(false) },
        sendCriticalAlert = { gid ->
            criticalAlertScope.launch {
                val success = viewModel.handleCriticalAlertNew(gid)
                viewModel.callUiController.setShowCriticalAlertConfirmViewEnabled(!success)
            }
        }
    )

    // Follows the exact "state + !isInPipMode" idiom already used by ShowItemsBottomView:
    // PiP entry force-dismisses the sheet (bottom sheets cannot render in PiP).
    val isInPipModeForSheet by viewModel.callUiController.isInPipMode.collectAsState(false)
    // Reset the underlying state on PiP entry, not just the derived `showSheet` gate below —
    // otherwise returning from PiP with `showE2eeSheet` still true reopens the sheet unprompted.
    LaunchedEffect(isInPipModeForSheet) {
        if (isInPipModeForSheet) showE2eeSheet = false
    }
    E2eeInfoSheetDialog(
        showSheet = showE2eeSheet && !isInPipModeForSheet,
        learnMoreUrl = viewModel.e2eeLearnMoreUrl,
        onDismissRequest = { showE2eeSheet = false },
    )
}

@Composable
private fun RenderTopAndBottomOverlays(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    callConfig: CallConfig,
    callIntent: CallIntent,
    callRole: CallRole,
    conversationId: String?,
    onWindowZoomOutClick: () -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit,
    onE2eeHintClick: () -> Unit,
) {
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)

    // 屏幕分享模式下，控制栏展示后 5 秒无操作自动隐藏。
    // 关键：这里不再通过 collectAsState 订阅 showTopStatusViewEnabled，否则父
    // composable 会在每次 toggle 时重组，进而把参数传递给 MainPageWithTopStatusView，
    // 即使子 composable 参数没变，未标记 @Stable 的 ViewModel/CallConfig 等复杂
    // 类型会让 Compose 无法跳过重组，从而仍然触发子树 re-measure / re-layout。
    // 改用 collectLatest 在 LaunchedEffect 内直接订阅 StateFlow：子 composable
    // 内部也只在 graphicsLayer/pointerInput 的 block 里读 state.value，整条链路
    // 上 toggle 不再触发任何 composable 重组，主线程堵塞 ≈ 0ms。
    LaunchedEffect(isUserSharingScreen) {
        if (!isUserSharingScreen) return@LaunchedEffect
        viewModel.callUiController.showTopStatusViewEnabled.collectLatest { showTop ->
            if (!showTop) return@collectLatest
            viewModel.callUiController.notifyScreenShareInteraction()
            while (isActive) {
                delay(1_000L)
                val elapsed = System.currentTimeMillis() -
                    viewModel.callUiController.screenShareLastInteractionTime
                if (elapsed >= 5_000L) {
                    viewModel.callUiController.setShowTopStatusViewEnabled(false)
                    viewModel.callUiController.setShowBottomToolBarViewEnabled(false)
                    break
                }
            }
        }
    }

    MainPageWithTopStatusView(
        viewModel = viewModel,
        isInPipMode = isInPipMode,
        isOneVOneCall = isOneVOneCall,
        isUserSharingScreen = isUserSharingScreen,
        callConfig = callConfig,
        callIntent = callIntent,
        windowZoomOutAction = onWindowZoomOutClick,
        onE2eeHintClick = onE2eeHintClick,
    )

    if (!isInPipMode) {
        MainPageWithBottomControlView(
            viewModel = viewModel,
            isOneVOneCall = isOneVOneCall,
            isUserSharingScreen = isUserSharingScreen,
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

