package com.difft.android.call.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import com.difft.android.base.ui.compose.DifftModalBottomSheet
import com.difft.android.base.ui.compose.DifftSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.difft.android.call.BuildConfig
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.ui.actionbar.ActionBarQuickAction
import com.difft.android.call.ui.actionbar.ActionCountBadge
import com.difft.android.call.ui.actionbar.CallActionButton
import com.difft.android.call.ui.actionbar.LabeledActionSlot
import com.difft.android.call.ui.actionbar.rememberCallActionBarPlan
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowItemsBottomView(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    onDismiss: () -> Unit,
    deNoiseCallBack: (Boolean) -> Unit,
    deNoiseModeCallBack: (AudioModule) -> Unit,
    voicePresetCallBack: (VoicePreset) -> Unit,
    handleInviteUsersClick: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val coroutineScope = rememberCoroutineScope()
    val showToolBarBottomViewEnable by viewModel.callUiController.showToolBarBottomViewEnable.collectAsState(false)
    val isParticipantSharedScreen by viewModel.callUiController.isShareScreening.collectAsState(false)
    val deNoiseEnable by viewModel.deNoiseEnable.collectAsState(true)
    val deNoiseMode by viewModel.deNoiseMode.collectAsState()
    val voicePreset by viewModel.voicePreset.collectAsState()
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val callStatus by viewModel.callStatus.collectAsState()
    val isCriticalAlertEnable by viewModel.callUiController.isCriticalAlertEnable.collectAsState(false)
    val awaitingJoinInvitees by viewModel.participantManager.awaitingJoinInvitees.collectAsState()
    val cameraEnabled by viewModel.cameraEnabled.collectAsState(false)
    val callType by viewModel.callType.collectAsState()

    val showCriticalAlertEnable = viewModel.is1v1ShowCriticalAlertEnable(callStatus) || viewModel.isGroupShowCriticalAlertEnable(isCriticalAlertEnable) || viewModel.isInstantCriticalAlertEnable(awaitingJoinInvitees)

    // Quick actions are the controls the current bar plan dropped: Invite always, People too
    // for groups, none when the split layout already shows both in the bar.
    val plan = rememberCallActionBarPlan(isGroup = !isOneVOneCall)
    // People is offered only where the participants panel can actually render (shared screen
    // or a wide window); otherwise the toggle would flip with nothing on screen.
    val peopleAvailable = participantsPanelAvailable(isParticipantSharedScreen, rememberParticipantsPanelWide())
    val quickActions = plan.moreQuickActions.filter { it != ActionBarQuickAction.PEOPLE || peopleAvailable }
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val hasQuickRow = quickActions.isNotEmpty() || cameraEnabled || showCriticalAlertEnable
    val itemSpace = 24.dp

    val shouldShowSheet = showToolBarBottomViewEnable && !isInPipMode
    val dismissSheet: () -> Unit = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    LaunchedEffect(shouldShowSheet) {
        if (shouldShowSheet) {
            sheetState.show()
        } else {
            sheetState.hide()
        }
    }

    if (shouldShowSheet || sheetState.isVisible) {
        DifftModalBottomSheet(
            sheetState = sheetState,
            contentWindowInsets = { WindowInsets.navigationBars },
            hideNavigationBar = true,
            onDismissRequest = {
                dismissSheet()
            },
        ) {
            Column(
                modifier = Modifier
                    .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
                    .testTag("call_more_sheet")
                    .fillMaxWidth()
                    .wrapContentHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (hasQuickRow) Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp),
                    horizontalArrangement = Arrangement.spacedBy(itemSpace, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (ActionBarQuickAction.INVITE in quickActions) {
                        LabeledActionSlot(label = ResUtils.getString(R.string.call_toolbar_bottom_invite_text)) {
                            CallActionButton(
                                painter = painterResource(id = R.drawable.call_bottom_invite),
                                contentDescription = "invite",
                                iconSize = 24.dp,
                                testTag = "call_more_btn_invite",
                                containerColor = DifftTheme.colors.backgroundTertiary,
                                onClick = {
                                    L.i { "[call] ShowItemsBottomView onClick invite" }
                                    handleInviteUsersClick()
                                    dismissSheet()
                                },
                            )
                        }
                    }

                    if (ActionBarQuickAction.PEOPLE in quickActions) {
                        LabeledActionSlot(label = ResUtils.getString(R.string.call_action_people)) {
                            CallActionButton(
                                painter = painterResource(id = R.drawable.call_ic_users),
                                contentDescription = "people",
                                testTag = "call_more_btn_people",
                                containerColor = DifftTheme.colors.backgroundTertiary,
                                onClick = {
                                    L.i { "[call] ShowItemsBottomView onClick people" }
                                    dismissSheet()
                                    viewModel.callUiController.setShowUsersEnabled(true)
                                },
                            ) {
                                if (participants.isNotEmpty()) ActionCountBadge(participants.size)
                            }
                        }
                    }

                    if (cameraEnabled) {
                        Column(
                            modifier = Modifier
                                .width(80.dp)
                                .height(76.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                modifier = Modifier
                                    .testTag("call_more_btn_switch_camera")
                                    .width(48.dp)
                                    .height(48.dp)
                                    .background(color = DifftTheme.colors.backgroundTertiary, shape = RoundedCornerShape(size = 100.dp))
                                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        try {
                                            L.i { "[call] ShowItemsBottomView onClick switch" }
                                            viewModel.flipCamera()
                                        } catch (e: Exception) {
                                            L.e { "[call] ShowItemsBottomView Error switching camera: ${e.message}" }
                                        }
                                    },
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Image(
                                    modifier = Modifier
                                        .padding(1.dp)
                                        .width(24.dp)
                                        .height(24.dp),
                                    painter = painterResource(id = R.drawable.chat_tabler_camera_rotate),
                                    contentDescription = "switch camera",
                                    contentScale = ContentScale.Fit
                                )
                            }

                            Text(
                                text = ResUtils.getString(R.string.call_toolbar_bottom_switch_text),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight(400),
                                    color = DifftTheme.colors.textPrimary
                                )
                            )
                        }
                    }

                    if (showCriticalAlertEnable) {
                        Column(
                            modifier = Modifier
                                .width(80.dp)
                                .height(76.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                modifier = Modifier
                                    .testTag("call_more_btn_critical_alert")
                                    .width(48.dp)
                                    .height(48.dp)
                                    .background(color = DifftTheme.colors.backgroundTertiary, shape = RoundedCornerShape(size = 100.dp))
                                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        try {
                                            L.i { "[call] ShowItemsBottomView click critical alert" }
                                            if (callType == CallType.ONE_ON_ONE.type) {
                                                viewModel.conversationId?.let {
                                                    coroutineScope.launch {
                                                        val success = viewModel.handleCriticalAlertNew()
                                                        if (success) viewModel.callUiController.setShowToolBarBottomViewEnable(false)
                                                    }
                                                }
                                            } else {
                                                viewModel.callUiController.setShowToolBarBottomViewEnable(false)
                                                viewModel.callUiController.setShowCriticalAlertConfirmViewEnabled(true)
                                            }
                                        } catch (e: Exception) {
                                            L.e { "[call] ShowItemsBottomView click alert error: ${e.message}" }
                                        }
                                    },
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Image(
                                    modifier = Modifier
                                        .padding(1.dp)
                                        .width(24.dp)
                                        .height(24.dp),
                                    painter = painterResource(id = R.drawable.call_tabler_critical_alert),
                                    contentDescription = "critical alert",
                                    contentScale = ContentScale.Fit
                                )
                            }

                            Text(
                                text = ResUtils.getString(R.string.call_toolbar_bottom_critical_alert_text),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight(400),
                                    color = DifftTheme.colors.textPrimary
                                )
                            )
                        }
                    }

                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(55.dp)
                            .background(color = DifftTheme.colors.backgroundTertiary, shape = RoundedCornerShape(size = 8.dp))
                            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier
                                .wrapContentWidth()
                                .height(24.dp),
                            text = ResUtils.getString(R.string.call_toolbar_noise_suppression_text),
                            style = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight(400),
                                color = DifftTheme.colors.textPrimary,
                            )
                        )

                        DifftSwitch(
                            modifier = Modifier
                                .testTag("call_more_btn_denoise")
                                .semantics { contentDescription = "DeNoise" },
                            checked = deNoiseEnable,
                            onCheckedChange = {
                                viewModel.audioDeviceManager.switchDeNoiseEnable(it)
                                deNoiseCallBack(it)
                            }
                        )
                    }

                    AnimatedVisibility(visible = deNoiseEnable) {
                        DeNoiseModeSelector(
                            currentMode = deNoiseMode,
                            onModeSelected = { mode ->
                                viewModel.audioDeviceManager.switchDeNoiseMode(mode)
                                deNoiseModeCallBack(mode)
                            }
                        )
                    }

                    VoicePresetCard(
                        currentPreset = voicePreset,
                        onPresetSelected = { preset ->
                            viewModel.audioDeviceManager.switchVoicePreset(preset)
                            voicePresetCallBack(preset)
                        }
                    )
                }
            }
        }
    }
}