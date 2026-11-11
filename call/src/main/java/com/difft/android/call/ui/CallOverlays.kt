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
import com.difft.android.call.ui.actionbar.isAnyPanelOpen
import com.difft.android.call.ui.alert.ShowCriticalAlertConfirmView
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch




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
        onInviteUsersClick = onInviteUsersClick,
        onExitClick = onExitClick,
        onE2eeHintClick = {
            viewModel.layout.setControlsHidden(false)
            showE2eeSheet = true
        },
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

    
    val isInPipModeForSheet by viewModel.callUiController.isInPipMode.collectAsState(false)
    
    
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
    onInviteUsersClick: () -> Unit,
    onExitClick: (CallExitParams, CallEndType?) -> Unit,
    onE2eeHintClick: () -> Unit,
) {
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)

    
    
    
    LaunchedEffect(isUserSharingScreen) {
        if (!isUserSharingScreen) return@LaunchedEffect
        viewModel.callUiController.showTopStatusViewEnabled.collectLatest { showTop ->
            if (!showTop) return@collectLatest
            viewModel.callUiController.notifyScreenShareInteraction()
            while (isActive) {
                delay(1_000L)
                
                
                
                if (viewModel.callUiController.isAnyPanelOpen() ||
                    viewModel.callUiController.participantMenuOpen.value
                ) {
                    viewModel.callUiController.notifyScreenShareInteraction()
                    continue
                }
                val elapsed = System.currentTimeMillis() -
                    viewModel.callUiController.screenShareLastInteractionTime
                if (elapsed >= 5_000L) {
                    viewModel.callUiController.autoHideChrome()
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
            onInviteUsersClick = onInviteUsersClick,
            onWindowZoomOutClick = onWindowZoomOutClick,
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

