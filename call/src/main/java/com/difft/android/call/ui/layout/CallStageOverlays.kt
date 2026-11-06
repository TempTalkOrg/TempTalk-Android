package com.difft.android.call.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.core.callMemberId
import com.difft.android.call.layout.ContentMode
import com.difft.android.call.ui.video.FrameSizeListener
import com.difft.android.call.ui.video.ScaleType
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.ui.PARTICIPANT_NAME_MAX_LENGTH
import com.difft.android.call.ui.ShowSpeakerStatusView
import com.difft.android.call.ui.participantDisplayName
import com.difft.android.call.ui.screenshare.ScreenSharingView
import com.difft.android.call.util.StringUtil
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant

/** The callee's avatar on the ringing screen; smaller in picture in picture. */
private val RING_AVATAR = 96.dp
private val RING_AVATAR_PIP = 48.dp
private val PIP_LABEL_INSET = 6.dp

/**
 * The shared screen in the share tile, scaled by the plan's [contentMode]. A double tap switches
 * between the whole share and filling the window (the controller's share zoom), a tap toggles the
 * bars; while it fills, a drag looks around within [panSlack] (px it reaches past the window on
 * each side). A zoom change or picture in picture resets pinch and pan (design §4.3). Its frame
 * sizes go to the layout, which sizes the share from them.
 */
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
    Box(
        Modifier
            .fillMaxSize()
            // Kept to the region the plan used: a filling share never runs over the fold or the dock.
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
                    onDoubleTap = { viewModel.layout.toggleShareZoom() },
                    onTap = { viewModel.callUiController.toggleOverlays() },
                )
            },
    ) {
        ScreenSharingView(
            room = room,
            participant = sharer,
            reconnectCount = reconnectCount,
            zoomResetKey = inPip to contentMode,
            scaleType = if (contentMode == ContentMode.FILL) ScaleType.Fill else ScaleType.FitInside,
            onFrameSize = onFrameSize,
            panSlack = frame.panSlack,
        )
    }
    LaunchedEffect(sharer.sid) { viewModel.updateScreenShareFallback(sharer) }
}

/**
 * A 1on1 before the other person is in the room: the callee's avatar over the call, with the name
 * on the call screen (its title shows the status) or the status in picture in picture (design §4.6).
 */
@Composable
internal fun CallRingOverlay(
    viewModel: LCallViewModel,
    calleeUid: String,
    displayInfo: CallUserDisplayInfo?,
    inPip: Boolean,
    // False over your own camera, which stays visible under the callee's name.
    opaque: Boolean = true,
) {
    val callStatus by viewModel.callStatus.collectAsState()
    val controller = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance).callToChatController
    }
    val text = if (inPip) {
        stringResource(if (callStatus == CallStatus.CALLING) R.string.call_status_calling else R.string.call_connecting_title)
    } else {
        StringUtil.truncateWithEllipsis(participantDisplayName(calleeUid, displayInfo?.name), PARTICIPANT_NAME_MAX_LENGTH)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("call_ring_overlay")
            .then(if (opaque) Modifier.background(DifftTheme.colors.background) else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

/**
 * What picture in picture writes over its one view (guide §5.3): the name and mic bottom left (for a
 * share, "<name>'s screen") and the call duration top right.
 */
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
                Text(
                    text = StringUtil.truncateWithEllipsis(sharerName, PARTICIPANT_NAME_MAX_LENGTH) +
                        stringResource(R.string.call_screen_sharing_title),
                    style = DifftTheme.typography.p5,
                    color = DifftTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
