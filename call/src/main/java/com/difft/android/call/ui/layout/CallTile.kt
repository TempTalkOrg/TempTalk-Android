package com.difft.android.call.ui.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.absoluteOffset
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.layout.ContentMode
import com.difft.android.call.layout.TileRole
import com.difft.android.call.ui.isMuteMenuTarget
import com.difft.android.call.ui.rememberParticipantDisplayName
import com.difft.android.call.ui.video.FirstFrameGate
import com.difft.android.call.ui.video.ScaleType
import com.difft.android.call.ui.video.VideoItemTrackSelector
import com.difft.android.call.ui.video.ViewType
import com.difft.android.call.ui.video.rememberCameraVideoOn
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import kotlin.math.min
import kotlin.math.roundToInt

/** Video fades in on its first frame, the avatar on camera off (guide §5.1). */
private const val CONTENT_FADE_MS = 200

/** Card corner for grid, floating window and "+N" cards; main, solo and share are square (guide §5.1). */
private val CARD_RADIUS = 12.dp

/** Avatar diameter: the card's short side × 0.46, kept within 48–120dp (guide §5.1). */
private const val AVATAR_SHORT_SIDE_RATIO = 0.46f
private const val AVATAR_MIN_DP = 48f
private const val AVATAR_MAX_DP = 120f

/**
 * The floating window's drag: [clamp] keeps an offset inside the stage, [onRelease] drops it where
 * it was let go, [onCancel] returns it to its corner.
 */
internal class PipDrag(val clamp: (Offset) -> Offset, val onRelease: (Offset) -> Unit, val onCancel: (Offset) -> Unit)

/** How one [CallTile] is drawn this frame; everything else about the tile stays put across roles. */
internal data class CallTileStyle(
    val role: TileRole,
    val contentMode: ContentMode?,
    /** The card's size, for the avatar. */
    val size: DpSize,
    /** Picture in picture: kept composed but not shown, its video paused. */
    val hidden: Boolean = false,
    /** Picture in picture: the one tile shown, with no decorations (the window draws its own). */
    val pipFocus: Boolean = false,
    /** Its letterbox bands are thick enough for the ambient treatment ([ambientBandOf]). */
    val ambient: Boolean = false,
    /** Where its edge colours go, for the stage to draw the 1on1 main view's bands; stable per tile. */
    val ambientSlot: AmbientSlot? = null,
)

internal fun avatarDiameter(size: DpSize): Dp =
    (min(size.width.value, size.height.value) * AVATAR_SHORT_SIDE_RATIO).coerceIn(AVATAR_MIN_DP, AVATAR_MAX_DP).dp

private fun cardShape(style: CallTileStyle): Shape = when {
    style.pipFocus -> RectangleShape
    style.role == TileRole.GRID || style.role == TileRole.PIP || style.role == TileRole.OVERFLOW -> RoundedCornerShape(CARD_RADIUS)
    else -> RectangleShape
}

/**
 * One participant's card in every role (guide §5.1–5.2). The camera renderer sits at the same place
 * in this tree whatever the role, so a tile that moves between grid, main, floating window and
 * pages keeps it. The renderer is mounted whenever the tile is, so a camera's first frame arrives
 * even while the plan still shows the avatar ([CallTileStyle.contentMode] null); the avatar stays
 * on top until the plan shows video and the renderer has drawn.
 *
 * Role [TileRole.PIP] drags through [pipDrag] (clamped to the stage, released or cancelled with the
 * drag offset in px) and swaps
 * on a tap; [TileRole.GRID] toggles the controls on a tap and opens the mute menu on a long press.
 */
@Composable
internal fun CallTile(
    viewModel: LCallViewModel,
    room: Room,
    participant: Participant,
    uid: String,
    displayInfo: CallUserDisplayInfo,
    participantCount: Int,
    muteOtherEnabled: Boolean,
    style: CallTileStyle,
    pipDrag: PipDrag?,
) {
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val firstFrameGate = remember { FirstFrameGate() }
    val cameraOn = rememberCameraVideoOn(participant)
    // A muted renderer keeps its last image: frames count only while the camera is on.
    LaunchedEffect(cameraOn) { firstFrameGate.setAccepting(cameraOn) }

    val showVideo = style.contentMode != null && firstFrameGate.hasFrame
    val avatarAlpha = animateFloatAsState(
        targetValue = if (showVideo) 0f else 1f,
        animationSpec = tween(CONTENT_FADE_MS),
        label = "callTileAvatarAlpha",
    )
    val avatarDrawn by remember { derivedStateOf { avatarAlpha.value > 0f } }
    val ambient = rememberTileAmbient(style, cameraOn, showVideo)

    val shape = cardShape(style)
    // Solo and 1v1 main fill the window: nothing is drawn over the video, and the camera-off
    // avatar column carries the name and mic (guide §5.1, #1366).
    val fullWindow = (style.role == TileRole.SOLO || style.role == TileRole.MAIN) && !style.pipFocus
    val displayName = rememberParticipantDisplayName(participant, displayInfo.name)
    val menu = remember { TileMuteMenuState() }
    val menuAvailable = style.role == TileRole.GRID && !style.hidden && !style.pipFocus &&
        participant.isMuteMenuTarget(muteOtherEnabled)
    val fit = style.contentMode == ContentMode.FIT
    // A menu left open when the card loses it (picture in picture) must not come back with it.
    LaunchedEffect(menuAvailable) { if (!menuAvailable) menu.close() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("call_tile_${style.role.json}")
            .then(
                if (style.role == TileRole.PIP && !style.hidden && !style.pipFocus && pipDrag != null) {
                    Modifier.floatingWindowDrag(pipDrag) { viewModel.layout.toggleSwap() }
                } else {
                    Modifier
                },
            )
            .tileGestures(viewModel, style, menuAvailable, menu, participant)
            .graphicsLayer { alpha = if (style.hidden) 0f else 1f }
            .clip(shape)
            .background(DifftTheme.colors.background)
            .cardAmbient(ambient.cardPaint, DifftTheme.colors.background),
    ) {
        // The renderer first and unconditionally, so its place in the tree never changes.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VideoItemTrackSelector(
                coroutineScope = coroutineScope,
                room = room,
                participant = participant,
                sourceType = Track.Source.CAMERA,
                // Unsized on purpose: under loose constraints FIT shrinks the renderer to the video,
                // and the card's own background, or its ambient bands, show around it.
                scaleType = if (fit) ScaleType.FitInside else ScaleType.Fill,
                // A TextureView moves and resizes with the card and can sit under other cards.
                viewType = ViewType.Texture,
                // Pinch zoom on the 1on1 main view while it shows the whole video.
                draggable = style.role == TileRole.MAIN && fit && !style.pipFocus,
                reconnectCount = reconnectCount,
                // The plan size, not the measured one, which changes every frame of a move.
                zoomResetKey = Triple(style.size, style.role, style.pipFocus),
                firstFrameGate = firstFrameGate,
                hidden = style.hidden,
                onEdgeColors = ambient.onEdgeColors,
            )
        }
        if (avatarDrawn) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = avatarAlpha.value }
                    .background(DifftTheme.colors.background),
                contentAlignment = Alignment.Center,
            ) {
                if (fullWindow) {
                    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()
                    FullWindowAvatarInfo(participant, displayInfo, displayName, speakingEnabled)
                } else {
                    displayInfo.avatarData?.let { data ->
                        val entryPoint = remember {
                            EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance)
                        }
                        entryPoint.callToChatController.Avatar(data = data, size = avatarDiameter(style.size))
                    }
                }
            }
        }
        if (!style.hidden && !style.pipFocus && !fullWindow) {
            TileDecorations(
                viewModel = viewModel,
                participant = participant,
                uid = uid,
                displayName = displayName,
                participantCount = participantCount,
                shape = shape,
                muteOtherEnabled = muteOtherEnabled,
                menu = menu.takeIf { menuAvailable },
            )
        }
        if (menuAvailable) TileMuteMenu(viewModel, participant, displayName, menu, TileMenuAnchor.CARD)
    }
}

/**
 * The floating window's drag and tap: it follows the finger in window coordinates (never mirrored
 * in RTL), kept inside the stage by [PipDrag.clamp]; let go it is dropped, cancelled it goes back.
 * A tap calls [onTap].
 */
@Composable
internal fun Modifier.floatingWindowDrag(pipDrag: PipDrag, onTap: () -> Unit): Modifier {
    var drag by remember { mutableStateOf(Offset.Zero) }
    val current by rememberUpdatedState(pipDrag)
    val currentOnTap by rememberUpdatedState(onTap)
    fun end(cancelled: Boolean) {
        val released = drag
        drag = Offset.Zero
        if (cancelled) current.onCancel(released) else current.onRelease(released)
    }
    return this
        .absoluteOffset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
        .pointerInput(Unit) { detectTapGestures { currentOnTap() } }
        .pointerInput(Unit) {
            detectDragGestures(onDragEnd = { end(cancelled = false) }, onDragCancel = { end(cancelled = true) }) { change, amount ->
                change.consume()
                drag = current.clamp(drag + amount)
            }
        }
}

/** A grid card's gestures: a tap toggles the controls, a long press opens the mute menu. */
private fun Modifier.tileGestures(
    viewModel: LCallViewModel,
    style: CallTileStyle,
    menuAvailable: Boolean,
    menu: TileMuteMenuState,
    participant: Participant,
): Modifier {
    if (style.hidden || style.pipFocus) return this
    return when (style.role) {
        TileRole.GRID -> this.pointerInput(menuAvailable) {
            detectTapGestures(
                onTap = { viewModel.callUiController.toggleOverlays() },
                // Long press anywhere on the card opens the same menu as the pill; it never mutes directly.
                onLongPress = { touch ->
                    if (menuAvailable && participant.isMicrophoneEnabled) menu.open(TileMenuAnchor.CARD, touch)
                },
            )
        }
        else -> this
    }
}
