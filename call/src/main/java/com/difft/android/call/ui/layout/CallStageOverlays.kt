package com.difft.android.call.ui.layout

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.core.callMemberId
import com.difft.android.call.layout.ContentMode
import com.difft.android.call.ui.video.FrameSizeListener
import com.difft.android.call.ui.video.ScaleType
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.layout.Posture
import com.difft.android.call.ui.NAME_MEASURE_CAP
import com.difft.android.call.ui.NameWithSuffix
import com.difft.android.call.ui.ShowSpeakerStatusView
import com.difft.android.call.ui.participantDisplayName
import com.difft.android.call.ui.screenshare.ScreenSharingView
import com.difft.android.call.util.StringUtil
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.delay


private val RING_AVATAR = 96.dp
private val RING_AVATAR_PIP = 48.dp
private val PIP_LABEL_INSET = 6.dp








@Composable
internal fun CallShareTile(
    viewModel: LCallViewModel,
    room: Room,
    sharer: RemoteParticipant?,
    inPip: Boolean,
    contentMode: ContentMode?,
    frame: ShareFrame,
) {
    sharer ?: return
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()
    val sharerId = callMemberId(sharer)
    val onFrameSize: FrameSizeListener = remember(sharerId) {
        { width, height, _ -> viewModel.layoutInputs.onShareFrameSize(sharerId, width, height) }
    }
    var zoomResets by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            
            .then(
                frame.clip?.let { clip ->
                    Modifier.drawWithContent {
                        clipRect(clip.left.toFloat(), clip.top.toFloat(), clip.right.toFloat(), clip.bottom.toFloat()) {
                            this@drawWithContent.drawContent()
                        }
                    }
                } ?: Modifier,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { zoomResets++ },
                    onTap = { viewModel.callUiController.toggleOverlays() },
                )
            },
    ) {
        ScreenSharingView(
            room = room,
            participant = sharer,
            reconnectCount = reconnectCount,
            zoomResetKey = inPip to contentMode,
            zoomResetRequest = zoomResets,
            scaleType = if (contentMode == ContentMode.FILL) ScaleType.Fill else ScaleType.FitInside,
            onFrameSize = onFrameSize,
            panSlack = frame.panSlack,
        )
    }
    LaunchedEffect(sharer.sid) { viewModel.updateScreenShareFallback(sharer) }
}









@Composable
internal fun CallRingOverlay(
    viewModel: LCallViewModel,
    calleeUid: String,
    displayInfo: CallUserDisplayInfo?,
    inPip: Boolean,
    
    opaque: Boolean = true,
    placement: RingPlacement = RingPlacement(null, null),
) {
    val callStatus by viewModel.callStatus.collectAsState()
    val controller = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance).callToChatController
    }
    val text = if (inPip) {
        stringResource(ringPipLabel(callStatus))
    } else {
        StringUtil.truncateWithEllipsis(participantDisplayName(calleeUid, displayInfo?.name), NAME_MEASURE_CAP)
    }
    val background = if (opaque) Modifier.background(DifftTheme.colors.background) else Modifier
    RingGate(placement, inPip, LocalCallSettledPosture.current, background) {
        RingCallee(controller, displayInfo, text, inPip)
    }
}






@Composable
internal fun RingGate(
    placement: RingPlacement,
    inPip: Boolean,
    settled: Posture?,
    background: Modifier = Modifier,
    callee: @Composable () -> Unit,
) {
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(RING_SETTLE_MAX_MS)
        waited = true
    }
    val drawn = remember { BooleanArray(1) }
    drawn[0] = drawn[0] || inPip || waited || ringReady(placement, settled)
    
    
    val showCallee = drawn[0]
    RingFrame(area = placement.area.takeUnless { inPip }, modifier = Modifier.testTag("call_ring_overlay").then(background)) {
        if (showCallee) callee()
    }
}







@Composable
internal fun RingFrame(area: IntRect?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val placed = if (area == null) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .absoluteOffset { IntOffset(area.left, area.top) }
            .size(with(density) { DpSize(area.width.toDp(), area.height.toDp()) })
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        Column(
            modifier = placed,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}





@StringRes
internal fun ringPipLabel(callStatus: CallStatus): Int =
    if (callStatus == CallStatus.CALLING || callStatus == CallStatus.DISCONNECTED) {
        R.string.call_status_calling
    } else {
        R.string.call_connecting_title
    }


internal const val RING_SETTLE_MAX_MS = 500L

@Composable
private fun RingCallee(
    controller: LCallToChatController,
    displayInfo: CallUserDisplayInfo?,
    text: String,
    inPip: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        displayInfo?.avatarData?.let { data ->
            controller.Avatar(data = data, size = if (inPip) RING_AVATAR_PIP else RING_AVATAR)
        }
        Spacer(Modifier.height(if (inPip) DifftTheme.spacing.stackXSmall else DifftTheme.spacing.stackSmall))
        Text(
            text = text,
            color = Color.White,
            style = if (inPip) DifftTheme.typography.p4 else DifftTheme.typography.p2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}





@Composable
internal fun CallPipOverlay(
    viewModel: LCallViewModel,
    focus: Participant?,
    focusName: String,
    sharerName: String?,
) {
    val duration by viewModel.timerManager.callDurationText.collectAsState()
    val timerRunning by viewModel.timerManager.callTimerRunning.collectAsState()
    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()
    Box(Modifier.fillMaxSize().testTag("call_pip_overlay")) {
        if (timerRunning) {
            PipLabel(Modifier.align(Alignment.TopEnd)) {
                Text(text = duration, style = DifftTheme.typography.p5, color = DifftTheme.colors.textPrimary, maxLines = 1)
            }
        }
        val bottomStart = Modifier.align(Alignment.BottomStart)
        when {
            sharerName != null -> PipLabel(bottomStart) {
                NameWithSuffix(
                    name = sharerName,
                    suffix = stringResource(R.string.call_screen_sharing_title),
                    style = DifftTheme.typography.p5,
                    color = DifftTheme.colors.textPrimary,
                )
            }
            focus != null -> PipLabel(bottomStart) { ShowSpeakerStatusView(focus, focusName, speakingEnabled = speakingEnabled) }
        }
    }
}

@Composable
private fun PipLabel(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .padding(PIP_LABEL_INSET)
            .background(DifftTheme.colors.backgroundPagePrimary, DifftTheme.radius.mediumShape)
            .padding(horizontal = DifftTheme.spacing.insetXSmall, vertical = 2.dp),
    ) { content() }
}
