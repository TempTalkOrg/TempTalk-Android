package com.difft.android.call.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.Palette
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.onMediaControlTapped
import com.difft.android.call.permission.CallMediaPermission
import com.difft.android.call.ui.actionbar.ActionBarLabels
import com.difft.android.call.ui.actionbar.ActionBarLayout
import com.difft.android.call.ui.actionbar.ActionBarPlan
import com.difft.android.call.ui.actionbar.ActionBarSlots
import com.difft.android.call.ui.actionbar.ActionCountBadge
import com.difft.android.call.ui.actionbar.CallActionBarPlanner
import com.difft.android.call.ui.actionbar.CallActionButton
import com.difft.android.call.ui.actionbar.CallSideRailOverlay
import com.difft.android.call.ui.actionbar.CallSideRailSpec
import com.difft.android.call.ui.actionbar.BookActionBar
import com.difft.android.call.ui.actionbar.CallTabletopDock
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.ui.actionbar.ControlsPlacementLog
import com.difft.android.call.ui.actionbar.OutsideEmojiButton
import com.difft.android.call.ui.actionbar.RailGroupEndButton
import com.difft.android.call.ui.actionbar.SingleRowActionBar
import com.difft.android.call.ui.actionbar.SplitActionBar
import com.difft.android.call.ui.actionbar.TwoRowActionBar
import com.difft.android.call.ui.actionbar.dockSlots
import com.difft.android.call.ui.actionbar.railSlots
import com.difft.android.call.ui.actionbar.rememberCallActionBarPlan
import com.difft.android.call.ui.actionbar.rememberCallChromeLayout
import com.difft.android.call.ui.actionbar.rememberChromeAlpha
import com.difft.android.call.ui.tabletop.rememberTabletopBottomInsetDp
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow


/** Control size on the tabletop dock (iOS standing console). */
private const val TABLETOP_DOCK_BUTTON_DP = 56

/**
 * Call action bar. The layout (two rows / split / one row / emoji outside / compact) comes from
 * [rememberCallActionBarPlan], a pure width-budget decision; this composable only binds the
 * controls to view-model state and hands them to the matching layout. Tall landscape windows
 * get the side rail instead of the bar (#1451).
 */
@Composable
fun MainPageWithBottomControlView(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean,
    onInviteUsersClick: () -> Unit,
    onWindowZoomOutClick: () -> Unit,
    endCallAction: (callType: String, callEndType: CallEndType) -> Unit,
) {
    val controller = viewModel.callUiController
    val (plan, rail, dock) = rememberCallChromeLayout(isGroup = !isOneVOneCall)
    val onRail = rail != null
    ControlsPlacementLog(rail = rail, isGroup = !isOneVOneCall, dock = dock)

    // The fade is read in the draw phase only. Once fully faded out the controls leave the tree,
    // so a hidden bar neither swallows taps meant for the 1v1 self-video underneath nor needs a
    // tap interceptor: the root's own click brings the chrome back. `derivedStateOf` flips this
    // Boolean only at the two ends of the fade, so the animation itself never recomposes here.
    val bottomAlpha = rememberChromeAlpha(controller, controller.showBottomToolBarViewEnabled)
    val barComposed by remember(bottomAlpha) { derivedStateOf { bottomAlpha.value > 0f } }

    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val micEnabled by viewModel.micEnabled.collectAsState(false)
    val videoEnabled by viewModel.cameraEnabled.collectAsState(false)
    val currentCallType by viewModel.callType.collectAsState()
    val voicePreset by viewModel.voicePreset.collectAsState()
    // Tap routing + system-request launching + Settings guide all live in
    // LCallActivityMediaPermissions (single decision point). Compose only renders
    // the mic badge from the coordinator state — camera is dialog-only, no badge.
    val micPermissionState by viewModel.mediaPermissions.micState.collectAsState()
    val showMicPermissionBadge = micPermissionState.showsBadge
    val context = LocalContext.current

    // The rail always has room for full-size controls; only the bar shrinks them (COMPACT). The
    // tabletop dock uses the standing console's larger controls.
    val size = when {
        dock != null -> TABLETOP_DOCK_BUTTON_DP.dp
        onRail -> CallSideRailSpec.BUTTON_DP.dp
        else -> plan.buttonSizeDp.dp
    }
    val glyph = if (onRail || dock != null) CallActionBarPlanner.ICON_DP.dp else plan.iconSizeDp.dp
    val onMediaTap: (CallMediaPermission) -> Unit = { permission ->
        if (viewModel.isControlButtonClickEnabled() && context is LCallActivity) {
            context.onMediaControlTapped(permission)
        }
    }

    val slots = ActionBarSlots(
        mic = {
            MicActionButton(micEnabled, showMicPermissionBadge, voicePreset, size) {
                L.i { "[call] LCallActivity onClick Mic" }
                onMediaTap(CallMediaPermission.Microphone)
            }
        },
        video = {
            val icon = if (videoEnabled) R.drawable.call_ic_camera_on else R.drawable.call_ic_camera_off
            // Camera-off keeps its red slash like the mic, so it is drawn untinted.
            SimpleActionButton(icon, "Camera", size, testTag = "call_btn_camera", tintIcon = videoEnabled) {
                L.i { "[call] LCallActivity onClick Camera" }
                onMediaTap(CallMediaPermission.Camera)
            }
        },
        speaker = { AudioRouteControl(viewModel = viewModel, isOneVOneCall = isOneVOneCall, controlSize = size) },
        invite = {
            SimpleActionButton(R.drawable.call_bottom_invite, "Invite", size, glyph, "call_btn_invite") {
                L.i { "[call] LCallActivity onClick Invite" }
                onInviteUsersClick()
            }
        },
        people = { PeopleActionButton(controller, participants.size, size) },
        more = {
            SimpleActionButton(R.drawable.call_btn_tabler_dots, "more options menu", size, glyph, "call_btn_more") {
                controller.setShowToolBarBottomViewEnable(true)
            }
        },
        emoji = {
            val emojiGlyph = CallActionBarPlanner.OUTSIDE_EMOJI_ICON_DP.dp
            SimpleActionButton(R.drawable.tabler_mood_smile, "emoji", size, emojiGlyph, "call_btn_emoji") {
                controller.setShowSimpleBarrageEnabled(true)
            }
        },
        end = { EndActionButton(controller, currentCallType, size, onRail || dock != null, endCallAction) },
    )

    if (dock != null) {
        val peopleAvailable = participantsPanelAvailable(isUserSharingScreen, rememberParticipantsPanelWide())
        CallTabletopDock(
            dock = dock,
            rows = dockSlots(isGroup = !isOneVOneCall, peopleAvailable = peopleAvailable),
            slots = slots,
            micEnabled = micEnabled,
            isGroup = !isOneVOneCall,
            bottomInsetDp = rememberTabletopBottomInsetDp(),
        )
    } else if (rail != null) {
        CallSideRailOverlay(
            rail = rail,
            order = railSlots(
                isGroup = !isOneVOneCall,
                isScreenShare = isUserSharingScreen,
                fitsSevenControls = rail.fitsSevenControls,
            ),
            slots = slots,
            onMinimize = onWindowZoomOutClick,
            composed = barComposed,
            alpha = { bottomAlpha.value },
        )
    } else {
        // Book posture: the bar splits around the fold the plan was laid out with (design §4.5).
        val layoutState by viewModel.layout.state.collectAsState()
        val bookFold = viewModel.layout.lastInput?.fold?.takeIf { layoutState.plan?.posture == Posture.BOOK }
        BottomActionBar(plan, slots, controller, micEnabled, videoEnabled, participants, barComposed, bottomAlpha, bookFold)
    }
}

/** The bottom bar for [plan]: the planned layout, with the outside Emoji pill above it when planned. */
@Composable
private fun BottomActionBar(
    plan: ActionBarPlan,
    slots: ActionBarSlots,
    controller: CallUiController,
    micEnabled: Boolean,
    videoEnabled: Boolean,
    participants: List<Participant>,
    barComposed: Boolean,
    bottomAlpha: Animatable<Float, AnimationVector1D>,
    bookFold: LayoutRect?,
) {
    val remoteCameraOn = rememberRemoteCameraOn(participants)
    // The book bar is one row with Emoji in it: no plate and no outside pill (it reserves one row).
    val showPlate = bookFold == null && plan.layout == ActionBarLayout.TWO_ROW && (videoEnabled || remoteCameraOn)
    val bottomMargin = if (showPlate) CallActionBarPlanner.TWO_ROW_PLATE_BOTTOM_DP.dp else plan.bottomMarginDp.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = bottomMargin),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (barComposed) Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = bottomAlpha.value },
        ) {
            if (!plan.emojiInline && bookFold == null) {
                OutsideEmojiButton(
                    onClick = { controller.setShowSimpleBarrageEnabled(true) },
                    modifier = Modifier.padding(
                        start = CallActionBarPlanner.H_INSET_DP.dp,
                        bottom = CallActionBarPlanner.OUTSIDE_EMOJI_GAP_DP.dp,
                    ),
                )
            }
            when {
                bookFold != null -> BookActionBar(plan = plan, slots = slots, fold = bookFold)
                plan.layout == ActionBarLayout.TWO_ROW -> TwoRowActionBar(
                    slots = slots,
                    labels = ActionBarLabels(
                        mic = stringResource(id = if (micEnabled) R.string.call_action_mute else R.string.call_action_unmute),
                        video = stringResource(id = R.string.call_action_video),
                        speaker = stringResource(id = R.string.call_action_speaker),
                        emoji = stringResource(id = R.string.call_action_emoji),
                        more = stringResource(id = R.string.call_action_more),
                        end = stringResource(id = R.string.call_action_end),
                    ),
                    showPlate = showPlate,
                )
                plan.layout == ActionBarLayout.SPLIT -> SplitActionBar(plan = plan, slots = slots)
                else -> SingleRowActionBar(plan = plan, slots = slots)
            }
        }
    }
}

/** A plain bar control: glyph resource in, tap out. [iconSize] defaults to the full-frame glyph. */
@Composable
private fun SimpleActionButton(
    iconRes: Int,
    contentDescription: String,
    size: Dp,
    iconSize: Dp = size,
    testTag: String,
    tintIcon: Boolean = true,
    onClick: () -> Unit,
) {
    CallActionButton(
        painter = painterResource(id = iconRes),
        contentDescription = contentDescription,
        size = size,
        iconSize = iconSize,
        tintIcon = tintIcon,
        testTag = testTag,
        onClick = onClick,
    )
}

/** People control with the participant count badge; toggles the participant list. */
@Composable
private fun PeopleActionButton(controller: CallUiController, participantCount: Int, size: Dp) {
    CallActionButton(
        painter = painterResource(id = R.drawable.call_ic_users),
        contentDescription = "Users",
        size = size,
        testTag = "call_btn_users",
        onClick = {
            L.i { "[call] LCallActivity onClick Users" }
            controller.setShowUsersEnabled(!controller.showUsersEnabled.value)
        },
    ) {
        if (participantCount > 0) ActionCountBadge(participantCount)
    }
}

/**
 * 1v1: hang up. Group: leave, with the chevron tail opening the end menu; on the rail and the
 * tabletop dock ([onRail]) a single End control opens that menu directly.
 */
@Composable
private fun EndActionButton(
    controller: CallUiController,
    callType: String,
    size: Dp,
    onRail: Boolean,
    endCallAction: (callType: String, callEndType: CallEndType) -> Unit,
) {
    if (callType == CallType.ONE_ON_ONE.type) {
        OneOnOneHangupButton(onHangup = { endCallAction(callType, CallEndType.END) }, size = size)
    } else if (onRail) {
        RailGroupEndButton(onShowEndMenu = { controller.setShowBottomCallEndViewEnable(true) }, size = size)
    } else {
        GroupCallLeaveButton(
            onLeave = { endCallAction(callType, CallEndType.LEAVE) },
            onShowEndMenu = { controller.setShowBottomCallEndViewEnable(true) },
            size = size,
        )
    }
}

/** Mic control with its two overlays: the permission badge, or the voice-preset ring + badge. */
@Composable
private fun MicActionButton(
    micEnabled: Boolean,
    showPermissionBadge: Boolean,
    voicePreset: VoicePreset,
    size: Dp,
    onClick: () -> Unit,
) {
    // Preset ring hides with the preset badge while mic permission is denied: the voice changer
    // only shapes local capture, and with no RECORD_AUDIO there is no capture — advertising it
    // would mislead. The permission badge replaces the entire voice-preset treatment while
    // visible (Figma 17129:3875 shows only the alert badge).
    val showPreset = voicePreset.isEnabled && !showPermissionBadge
    CallActionButton(
        painter = painterResource(id = if (micEnabled) R.drawable.call_ic_mic_on else R.drawable.call_ic_mic_off),
        contentDescription = "Mic",
        size = size,
        // The muted glyph keeps its red slash (pre-existing salience cue), so it is not tinted.
        tintIcon = micEnabled,
        testTag = "call_btn_mic",
        onClick = onClick,
    ) {
        if (showPreset) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(width = 2.dp, color = Palette.Blue400, shape = CircleShape)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Palette.Blue300, Palette.Blue200, Palette.Blue400),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                        ),
                        shape = CircleShape
                    )
            ) {
                Text(
                    text = voicePreset.emoji,
                    style = DifftTheme.typography.p4,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (showPermissionBadge) {
            MicPermissionBadge(modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * Whether the (first) remote participant is publishing an unmuted camera track. Drives the
 * two-row backplate together with the local camera, so captions stay legible over video.
 */
@Composable
private fun rememberRemoteCameraOn(participants: List<Participant>): Boolean {
    val remote = participants.filterIsInstance<RemoteParticipant>().firstOrNull() ?: return false
    val videoPubs by remote::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val cameraPub = videoPubs
        .filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }
        .firstOrNull { pub -> pub.source == Track.Source.CAMERA }
    var muted by remember(remote.sid) { mutableStateOf(true) }
    LaunchedEffect(cameraPub) {
        val pub = cameraPub
        if (pub == null) {
            muted = true
            return@LaunchedEffect
        }
        pub::muted.flow.collect { muted = it }
    }
    return !muted
}

/**
 * Red alert badge on the mic toggle while RECORD_AUDIO is denied (spec: persistent,
 * non-blocking denial indicator; mic only — camera/screen-share are dialog-only).
 *
 * Per the Figma spec (node 17129:3860): 16dp tabler alert-circle-filled, error red,
 * flush to the button's top-end corner; the "!" is a cutout showing the background.
 */
@Composable
internal fun MicPermissionBadge(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.call_ic_mic_permission_badge),
        contentDescription = null,
        modifier = modifier
            .testTag("call_mic_permission_badge")
            .size(16.dp)
    )
}
