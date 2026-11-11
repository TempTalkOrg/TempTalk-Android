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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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


private const val CONTENT_FADE_MS = 200





private val CARD_RADIUS = CARD_RADIUS_DP.dp


private const val AVATAR_SHORT_SIDE_RATIO = 0.46f
private const val AVATAR_MIN_DP = 48f
private const val AVATAR_MAX_DP = 120f





internal class PipDrag(val clamp: (Offset) -> Offset, val onRelease: (Offset) -> Unit, val onCancel: (Offset) -> Unit)


internal data class CallTileStyle(
    val role: TileRole,
    val contentMode: ContentMode?,
    
    val size: DpSize,
    
    val hidden: Boolean = false,
    
    val pipFocus: Boolean = false,
    
    val ambient: Boolean = false,
    
    val ambientSlot: AmbientSlot? = null,
)

internal fun avatarDiameter(size: DpSize): Dp =
    (min(size.width.value, size.height.value) * AVATAR_SHORT_SIDE_RATIO).coerceIn(AVATAR_MIN_DP, AVATAR_MAX_DP).dp

private fun cardShape(style: CallTileStyle): Shape = when {
    style.pipFocus -> RectangleShape
    style.role == TileRole.GRID || style.role == TileRole.PIP || style.role == TileRole.OVERFLOW -> RoundedCornerShape(CARD_RADIUS)
    else -> RectangleShape
}

















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
    val cornerState = remember { TileCornerState() }
    val screenCorners = LocalCallScreenCorners.current.takeIf { takesScreenCorners(style.role, style.pipFocus, style.hidden) }
    val corners = TileCorners(shape, cornerState, screenCorners)
    val density = LocalDensity.current.density
    
    
    val fullWindow = (style.role == TileRole.SOLO || style.role == TileRole.MAIN) && !style.pipFocus
    val displayName = rememberParticipantDisplayName(participant, displayInfo.name)
    val menu = remember { TileMuteMenuState() }
    val menuAvailable = style.role == TileRole.GRID && !style.hidden && !style.pipFocus &&
        participant.isMuteMenuTarget(muteOtherEnabled)
    val fit = style.contentMode == ContentMode.FIT
    
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
            
            .onGloballyPositioned { cornerState.place(it, density) }
            .graphicsLayer { alpha = if (style.hidden) 0f else 1f }
            .cardClip(corners)
            .background(DifftTheme.colors.background)
            .cardAmbient(ambient.cardPaint, DifftTheme.colors.background),
    ) {
        
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VideoItemTrackSelector(
                coroutineScope = coroutineScope,
                room = room,
                participant = participant,
                sourceType = Track.Source.CAMERA,
                
                
                scaleType = if (fit) ScaleType.FitInside else ScaleType.Fill,
                
                viewType = ViewType.Texture,
                reconnectCount = reconnectCount,
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
                corners = corners,
                muteOtherEnabled = muteOtherEnabled,
                menu = menu.takeIf { menuAvailable },
            )
        }
        if (menuAvailable) TileMuteMenu(viewModel, participant, displayName, menu, TileMenuAnchor.CARD)
    }
}






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
                
                onLongPress = { touch ->
                    if (menuAvailable && participant.isMicrophoneEnabled) menu.open(TileMenuAnchor.CARD, touch)
                },
            )
        }
        else -> this
    }
}
