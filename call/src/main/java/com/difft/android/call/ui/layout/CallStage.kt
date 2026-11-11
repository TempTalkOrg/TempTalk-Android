package com.difft.android.call.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toRect
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.core.callMemberId
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.ContentMode
import com.difft.android.call.layout.ControllerState
import com.difft.android.call.layout.LayoutInput
import com.difft.android.call.layout.LayoutMode
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.facingSide
import com.difft.android.call.layout.isOneOnOne
import com.difft.android.call.layout.oneOnOneMedia
import com.difft.android.call.layout.postureOf
import com.difft.android.call.ui.video.rememberCameraVideoOn
import com.difft.android.call.layout.LayoutPlan
import com.difft.android.call.layout.Point
import com.difft.android.call.layout.Tile
import com.difft.android.call.layout.TileRole
import com.difft.android.call.ui.AnimatedTileLayout
import com.difft.android.call.ui.TilePlacement
import com.difft.android.call.ui.TileMotionState
import com.difft.android.call.ui.participantDisplayName
import com.difft.android.call.ui.rememberTileMotionState
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.difft.android.call.layout.Insets as LayoutInsets
import com.difft.android.call.layout.Rect as LayoutRect


internal const val SHARE_TILE_ID = "share"


private const val PIP_FOCUS_Z = 1_000f


private data class StageNode(val tile: Tile, val participant: Participant?, val style: CallTileStyle, val placed: IntRect)










internal class StageSnapRule {
    private var lastState: ControllerState? = null
    private var lastKey: Any? = null
    private var lastPip = false
    private var lastSnap = true

    fun snapFor(state: ControllerState, key: Any?, inPip: Boolean): Boolean {
        if (state === lastState && inPip == lastPip) return lastSnap
        val newState = state !== lastState
        val snap = lastState == null || inPip || inPip != lastPip || (newState && (!state.animated || key != lastKey))
        if (newState) lastKey = key
        lastState = state
        lastPip = inPip
        lastSnap = snap
        return snap
    }
}






internal fun stageTiles(plan: LayoutPlan?, focusId: String?, inPip: Boolean): List<Tile> {
    val tiles = plan?.tiles.orEmpty().sortedBy { it.z }
    if (!inPip || focusId == null || tiles.any { it.id == focusId }) return tiles
    val focus = Tile(
        id = focusId,
        role = TileRole.SOLO,
        rect = LayoutRect(0.0, 0.0, 0.0, 0.0),
        contentMode = ContentMode.FIT,
        mismatch = 0.0,
        z = (tiles.maxOfOrNull { it.z } ?: 0) + 1,
    )
    return tiles + focus
}







internal fun showsRingOverlay(
    oneOnOne: Boolean,
    remoteInRoom: Boolean,
    callStatus: CallStatus,
    sharing: Boolean,
    wasRinging: Boolean = false,
): Boolean = oneOnOne && !remoteInRoom && !sharing &&
    (callStatus == CallStatus.CALLING || (wasRinging && callStatus == CallStatus.DISCONNECTED))






internal fun muteMenuAllowed(muteOtherEnabled: Boolean, input: LayoutInput?, oneOnOneCall: Boolean, people: Int): Boolean =
    muteOtherEnabled && !(input?.let { isOneOnOne(it.callType, it.participants.size) } ?: (oneOnOneCall && people <= 2))






internal fun ringArea(plan: LayoutPlan?): LayoutRect? = plan?.tiles?.firstOrNull { it.role == TileRole.SOLO }?.rect


internal data class RingPlacement(val area: IntRect?, val posture: Posture?)





internal val LocalCallSettledPosture = compositionLocalOf<Posture?> { null }





internal fun ringReady(placement: RingPlacement, settled: Posture?): Boolean =
    settled != null && placement.area != null && placement.posture == settled





internal class RingMemory {
    private var shown = false
    private var last = RingPlacement(null, null)

    fun shows(oneOnOne: Boolean, remoteInRoom: Boolean, callStatus: CallStatus, sharing: Boolean): Boolean =
        showsRingOverlay(oneOnOne, remoteInRoom, callStatus, sharing, wasRinging = shown).also { shown = it }

    fun place(plan: LayoutPlan?, origin: Offset, density: Float): RingPlacement {
        val area = ringArea(plan) ?: return last
        return RingPlacement(area.toStagePx(origin, density), plan?.posture).also { last = it }
    }
}





internal fun shareRegion(input: LayoutInput?): LayoutRect? {
    input ?: return null
    val fold = input.fold
    if (fold != null && postureOf(fold) == Posture.TABLETOP) {
        val safe = input.safe ?: LayoutInsets.ZERO
        return LayoutRect(safe.left, safe.top, input.window.w - safe.left - safe.right, fold.y - safe.top)
    }
    return facingSide(input.window, fold) ?: LayoutRect(0.0, 0.0, input.window.w, input.window.h)
}


internal fun sharePanSlack(tile: IntRect, region: IntRect): Offset = Offset(
    ((tile.width - region.width) / 2f).coerceAtLeast(0f),
    ((tile.height - region.height) / 2f).coerceAtLeast(0f),
)


internal fun clampDrag(rect: IntRect, drag: Offset, bounds: IntSize): Offset {
    if (bounds.width <= 0 || bounds.height <= 0) return drag
    val minX = -rect.left.toFloat()
    val maxX = (bounds.width - rect.right).toFloat()
    val minY = -rect.top.toFloat()
    val maxY = (bounds.height - rect.bottom).toFloat()
    return Offset(drag.x.coerceIn(minOf(minX, maxX), maxOf(minX, maxX)), drag.y.coerceIn(minOf(minY, maxY), maxOf(minY, maxY)))
}


internal data class ShareFrame(val panSlack: Offset, val clip: IntRect?)


internal fun LayoutRect.toStagePx(origin: Offset, density: Float): IntRect = IntRect(
    left = ((x - origin.x) * density).roundToInt(),
    top = ((y - origin.y) * density).roundToInt(),
    right = ((x + w - origin.x) * density).roundToInt(),
    bottom = ((y + h - origin.y) * density).roundToInt(),
)







@Composable
internal fun CallStage(
    viewModel: LCallViewModel,
    room: Room,
    conversationId: String?,
    muteOtherEnabled: Boolean,
) {
    val controller = viewModel.layout
    val state by controller.state.collectAsState()
    val inPip by viewModel.callUiController.isInPipMode.collectAsState()
    val participants by viewModel.participants.collectAsState()
    val sharer by viewModel.screenSharingUser.collectAsState()
    val callType by viewModel.callType.collectAsState()
    val callStatus by viewModel.callStatus.collectAsState()
    val reportKey = LocalCallLayoutReportKey.current
    val density = LocalDensity.current
    val motion = rememberTileMotionState()
    val snapRule = remember { StageSnapRule() }
    val pillMemory = remember { PagerPillMemory() }
    
    
    val lastParticipants = remember { HashMap<String, Participant>() }
    
    val ambientSlots = remember { HashMap<String, AmbientSlot>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }

    val plan = state.plan
    val byId = remember(participants) { participants.associateBy(::callMemberId) }
    
    val ring = remember { RingMemory() }
    val ringUid = conversationId?.takeIf {
        ring.shows(
            oneOnOne = callType == CallType.ONE_ON_ONE.type,
            remoteInRoom = participants.any { it is RemoteParticipant },
            callStatus = callStatus,
            sharing = plan?.mode == LayoutMode.SHARE,
        )
    }
    
    val localCameraOn = rememberCameraVideoOn(room.localParticipant)
    val displayInfos = rememberDisplayInfos(participants, ringUid)

    val snap = snapRule.snapFor(state, reportKey?.value, inPip)
    val focusId = if (plan?.mode == LayoutMode.SHARE) SHARE_TILE_ID else state.focus
    val tiles = stageTiles(plan, focusId, inPip)
    tiles.forEach { tile -> byId[tile.id]?.let { lastParticipants[tile.id] = it } }
    val tileIds = tiles.mapTo(HashSet()) { it.id }
    lastParticipants.keys.retainAll(tileIds)
    ambientSlots.keys.retainAll(tileIds)
    val input = controller.lastInput
    val nodes = tiles
        .map { tile ->
            val pipFocus = inPip && tile.id == focusId
            
            val placed = if (pipFocus) IntRect(IntOffset.Zero, stageSize) else tile.rect.toStagePx(origin, density.density)
            val size = if (pipFocus) with(density) { DpSize(stageSize.width.toDp(), stageSize.height.toDp()) } else DpSize(tile.rect.w.dp, tile.rect.h.dp)
            val style = tileStyle(tile, size, pipFocus, inPip, input, ambientSlots.getOrPut(tile.id) { AmbientSlot() })
            StageNode(tile, lastParticipants[tile.id], style, placed)
        }
    val placements = nodes.map { node ->
        TilePlacement(
            id = node.tile.id,
            rect = node.placed,
            z = if (node.style.pipFocus) PIP_FOCUS_Z else node.tile.z.toFloat(),
            data = node,
            fill = node.style.pipFocus,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("call_stage")
            .onGloballyPositioned { coordinates ->
                origin = coordinates.positionInWindow() / density.density
                stageSize = coordinates.size
            }
            .pageSwipe(enabled = state.pager != null && !inPip && plan?.mode == LayoutMode.GRID, controller = controller),
    ) {
        
        val region = shareRegion(controller.lastInput)?.toStagePx(origin, density.density)
        StageMainAmbient(nodes, input, origin, motion)
        AnimatedTileLayout(tiles = placements, snap = snap, modifier = Modifier.fillMaxSize(), motion = motion) { placement ->
            val node = placement.data as? StageNode ?: return@AnimatedTileLayout
            val share = if (node.style.pipFocus || region == null) {
                ShareFrame(Offset.Zero, null)
            } else {
                ShareFrame(sharePanSlack(node.placed, region), region.translate(-node.placed.left, -node.placed.top))
            }
            val pipDrag = PipDrag(
                clamp = { drag -> clampDrag(motion.currentRect(node.tile.id) ?: node.placed, drag, stageSize) },
                onRelease = { drag -> releasePip(controller, motion, node, drag, origin, density) },
                onCancel = { drag -> motion.releaseAt(node.tile.id, (motion.currentRect(node.tile.id) ?: node.placed).translate(drag.round())) },
            )
            val muteMenu = muteMenuAllowed(muteOtherEnabled, controller.lastInput, callType == CallType.ONE_ON_ONE.type, participants.size)
            StageTile(viewModel, room, node, participants.size, muteMenu, displayInfos, sharer, inPip, share, pipDrag)
        }
        if (ringUid != null) {
            val placement = ring.place(plan, origin, density.density)
            CallRingOverlay(viewModel, ringUid, displayInfos[ringUid], inPip, opaque = !localCameraOn, placement = placement)
        } else if (inPip && plan != null) {
            val focus = focusId?.let(byId::get)
            CallPipOverlay(
                viewModel = viewModel,
                focus = focus,
                focusName = focus?.let { participantDisplayName(it, displayInfos[displayUid(it)]?.name) }.orEmpty(),
                sharerName = sharer?.takeIf { plan.mode == LayoutMode.SHARE }?.let { participantDisplayName(it, displayInfos[displayUid(it)]?.name) },
            )
        }
        val pager = state.pager
        if (pager != null && !inPip && plan?.mode == LayoutMode.GRID) {
            CallPagerOverlay(pager, plan.tiles.lastOrNull { it.role == TileRole.GRID }, origin, controller, pillMemory)
        }
    }
}


private fun tileStyle(tile: Tile, size: DpSize, pipFocus: Boolean, inPip: Boolean, input: LayoutInput?, slot: AmbientSlot) = CallTileStyle(
    role = tile.role,
    
    contentMode = if (pipFocus) tile.contentMode?.let { ContentMode.FIT } else tile.contentMode,
    size = size,
    hidden = inPip && !pipFocus,
    pipFocus = pipFocus,
    ambient = !inPip && ambientBandOf(tile, input) >= AMBIENT_MIN_BAND_DP,
    ambientSlot = slot,
)


@Composable
private fun StageMainAmbient(nodes: List<StageNode>, input: LayoutInput?, origin: Offset, motion: TileMotionState) {
    val density = LocalDensity.current.density
    val main = nodes.firstOrNull { it.tile.role == TileRole.MAIN }
    MainAmbient(
        slot = main?.style?.ambientSlot,
        shown = main?.style?.ambient == true,
        media = input?.takeIf { main != null }?.let { oneOnOneMedia(it).toStagePx(origin, density).toRect() },
        picture = { main?.let { (motion.currentRect(it.tile.id) ?: it.placed).toRect() } },
    )
}

@Composable
@Suppress("LongParameterList")
private fun StageTile(
    viewModel: LCallViewModel,
    room: Room,
    node: StageNode,
    participantCount: Int,
    muteOtherEnabled: Boolean,
    displayInfos: Map<String, CallUserDisplayInfo>,
    sharer: RemoteParticipant?,
    inPip: Boolean,
    share: ShareFrame,
    pipDrag: PipDrag,
) {
    if (node.tile.role == TileRole.SHARE) {
        CallShareTile(viewModel, room, sharer, inPip, node.style.contentMode, share)
        return
    }
    val participant = node.participant ?: return
    val uid = displayUid(participant)
    CallTile(
        viewModel = viewModel,
        room = room,
        participant = participant,
        uid = uid,
        displayInfo = displayInfos[uid] ?: CallUserDisplayInfo(null, null, null),
        participantCount = participantCount,
        muteOtherEnabled = muteOtherEnabled,
        style = node.style,
        pipDrag = pipDrag.takeIf { node.tile.role == TileRole.PIP },
    )
}





private fun releasePip(
    controller: CallLayoutController,
    motion: TileMotionState,
    node: StageNode,
    drag: Offset,
    origin: Offset,
    density: Density,
) {
    val released = (motion.currentRect(node.tile.id) ?: node.placed).translate(drag.round())
    motion.releaseAt(node.tile.id, released)
    val center = released.center
    controller.dropPip(Point(center.x / density.density + origin.x.toDouble(), center.y / density.density + origin.y.toDouble()))
}


private fun displayUid(participant: Participant): String =
    if (participant is LocalParticipant) globalServices.myId else participant.identity?.value.orEmpty()


@Composable
private fun rememberDisplayInfos(participants: List<Participant>, calleeUid: String?): Map<String, CallUserDisplayInfo> {
    val cache = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance).contactorCacheManager
    }
    val infos by cache.participantDisplayMap.collectAsState()
    
    
    val seen = remember { arrayOf(emptyMap<String, CallUserDisplayInfo>()) }
    val kept = remember(infos) { keepDisplayInfos(seen[0], infos).also { seen[0] = it } }
    LaunchedEffect(participants, calleeUid) {
        val known = cache.participantDisplayMap.value
        (participants.map(::displayUid) + listOfNotNull(calleeUid))
            .filter { it.isNotEmpty() && it !in known }
            .distinct()
            .forEach { uid -> launch { cache.loadParticipantDisplay(uid) } }
    }
    return kept
}


internal fun keepDisplayInfos(seen: Map<String, CallUserDisplayInfo>, now: Map<String, CallUserDisplayInfo>): Map<String, CallUserDisplayInfo> =
    seen + now
