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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
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
import com.difft.android.call.ui.actionbar.rememberCallSideRail
import com.difft.android.call.ui.actionbar.rememberChromeAlpha
import com.difft.android.call.ui.actionbar.rememberChromeVisible
import com.difft.android.call.ui.actionbar.rememberMinimizeStart
import com.difft.android.call.ui.actionbar.rememberTitleRowTop
import com.difft.android.call.ui.alert.CallCriticalAlertView
import com.difft.android.call.ui.tabletop.LocalCallTabletop
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val callDuration by viewModel.timerManager.callDurationText.collectAsState("00:00")
    
    
    val callTimerRunning by viewModel.timerManager.callTimerRunning.collectAsState(false)
    val callStatus by viewModel.callStatus.collectAsState()
    val mediaSendIssue by viewModel.mediaSendIssue.collectAsState()
    val countDownEnabled by viewModel.timerManager.countDownEnabled.collectAsState(false)
    val callType by viewModel.callType.collectAsState()
    val screenSharingUser by viewModel.screenSharingUser.collectAsState()
    
    
    
    val participants by viewModel.participants.collectAsState()
    
    
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
        
        
        
        
        weakNetwork = WeakNetworkBanner.resolve(networkQuality, participants.size),
    )
    
    
    val isTopVisible by rememberChromeVisible(viewModel.callUiController, viewModel.callUiController.showTopStatusViewEnabled)
    val topAlpha = rememberChromeAlpha(viewModel.callUiController, viewModel.callUiController.showTopStatusViewEnabled)
    
    
    val overPicture = isUserSharingScreen || LocalCallTabletop.current != null
    val titleCutoutExtra = rememberTitleCutoutExtra(isLandscape, isUserSharingScreen, overPicture)
    val rowTop = titleCutoutExtra + if (!isLandscape || !isUserSharingScreen) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LCallUiConstants.TOP_BAR_MARGIN_TOP_DP.dp
    } else {
        0.dp
    }
    val barBottom = rowTop + LCallUiConstants.TOP_BAR_HEIGHT_DP.dp

    
    
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            
            .graphicsLayer { alpha = topAlpha.value }
            
            .then(if (overPicture) Modifier.drawBehind { drawTopShade(barBottom.toPx()) } else Modifier)
            .then(
                if (!isLandscape || !isUserSharingScreen) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        .padding(
                            top = LCallUiConstants.TOP_BAR_MARGIN_TOP_DP.dp + titleCutoutExtra,
                            bottom = LCallUiConstants.TOP_BAR_MARGIN_BOTTOM_DP.dp,
                        )
                } else {
                    Modifier.padding(top = titleCutoutExtra, bottom = LCallUiConstants.TOP_BAR_MARGIN_BOTTOM_DP.dp)
                }
            ),
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
            rowTop = rowTop,
            minimizeAside = overPicture,
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

        
        if (!isInPipMode) {
            CallStatusNotificationBar(
                status = statusNotification,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        val criticalAlertScope = rememberCoroutineScope()
        if (viewModel.is1v1ShowCriticalAlertEnable(callStatus)) {
            
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

private fun DrawScope.drawTopShade(bottomPx: Float) {
    drawRect(
        brush = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent), endY = bottomPx),
        size = Size(size.width, bottomPx),
    )
}

private val MINIMIZE_TARGET_SIZE = 44.dp





@Composable
private fun rememberTitleCutoutExtra(isLandscape: Boolean, isUserSharingScreen: Boolean, minimizeAside: Boolean): Dp {
    val baseTop = if (isLandscape && isUserSharingScreen) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val extra = (rememberTitleRowTop(baseTop, alignWithRail = !isUserSharingScreen, minimizeAside = minimizeAside) - baseTop).coerceAtLeast(0.dp)
    val extraDp = extra.value.toInt()
    val baseDp = baseTop.value.toInt()
    LaunchedEffect(extraDp) {
        if (extraDp > 0) L.i { "[Call] titleRowCutout extraDp=$extraDp baseDp=$baseDp share=$isUserSharingScreen landscape=$isLandscape" }
    }
    return extra
}





@Composable
private fun TopStatusBar(
    modifier: Modifier = Modifier,
    isInPipMode: Boolean,
    isTopVisible: Boolean,
    isLandscape: Boolean,
    isUserSharingScreen: Boolean,
    
    rowTop: Dp,
    minimizeAside: Boolean,
    windowZoomOutAction: () -> Unit,
    onE2eeHintClick: () -> Unit,
    statusContent: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LCallUiConstants.TOP_BAR_HEIGHT_DP.dp),
    ) {
        
        val onRail = rememberCallSideRail() != null
        val showMinimize = !isInPipMode && !onRail
        val basePadding = if (!isLandscape) 16.dp else 18.dp
        val controlPadding = if (minimizeAside) rememberMinimizeStart(rowTop, basePadding) else basePadding
        
        val titleInset = if (showMinimize) controlPadding + MINIMIZE_TARGET_SIZE else 0.dp
        if (showMinimize) {
            Box(
                modifier = Modifier
                    .testTag("call_topbar_zoom_out")
                    .align(Alignment.CenterStart)
                    .padding(start = controlPadding)
                    .size(MINIMIZE_TARGET_SIZE)
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

        
        
        
        
        
        
        val canClickE2eeHeader = isTopVisible && !isInPipMode && !isUserSharingScreen
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = titleInset)
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
            
            EncryptedStatusRow(
                textStyle = DifftTheme.typography.p3.copy(color = DifftTheme.colors.textPrimary),
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
    
    val callTimerRunning by viewModel.timerManager.callTimerRunning.collectAsState(false)

    if (isUserSharingScreen && screenSharingUser?.identity?.value != null) {
        screenShareUserName?.let { name ->
            
            
            
            val shareSuffix = ResUtils.getString(R.string.call_screen_sharing_title) +
                if (callTimerRunning) " $callDuration" else ""
            NameWithSuffix(
                name = name,
                suffix = shareSuffix,
                style = DifftTheme.typography.p3,
                color = Color.White,
                suffixModifier = Modifier.testTag("call_topbar_call_duration"),
            )
        }
    } else if (!isUserSharingScreen) {
        
        
        
        
        val headerTitle = if (isOneVOneCall) {
            oneOnOnePeerName?.let { StringUtil.truncateWithEllipsis(it, NAME_MEASURE_CAP) }
        } else {
            StringUtil.truncateWithEllipsis(viewModel.getCallRoomName(), NAME_MEASURE_CAP)
        }
        if (!headerTitle.isNullOrEmpty()) {
            Text(
                modifier = Modifier.testTag("call_topbar_room_name"),
                text = headerTitle,
                style = DifftTheme.typography.p3,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (!isUserSharingScreen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            
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
                style = DifftTheme.typography.p4,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (countDownEnabled) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(DifftTheme.hairline)
                        .height(10.dp)
                        .background(color = DifftTheme.colors.divider)
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

