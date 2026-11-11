


package com.difft.android.call.layout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min















data class CallMember(val id: String, val isLocal: Boolean, val cameraOn: Boolean)


data class ScreenShare(val sharerId: String, val width: Double, val height: Double)



data class Pager(val page: Int, val pages: Int, val later: Int, val area: Rect)

data class ControllerState(
    val plan: LayoutPlan? = null,
    
    
    val animated: Boolean = false,
    
    
    val controlsHidden: Boolean = false,
    
    val pager: Pager? = null,
    
    
    
    
    val focus: String? = null,
    
    
    
    
    val speaker: String? = null,
)



data class SpeakerState(val talker: String?, val pending: Pending?) {
    data class Pending(val to: String?, val since: Double, val due: Double)
}

fun createSpeakerState(): SpeakerState = SpeakerState(talker = null, pending = null)





fun hearSpeakers(s: SpeakerState, ids: List<String>, present: (String) -> Boolean, now: Double, cfg: LayoutConfig): SpeakerState {
    val top = ids.firstOrNull(present)
    if (top != null && s.talker == null) return SpeakerState(top, null)
    if (top == s.talker) return SpeakerState(s.talker, null)
    val since = s.pending?.since ?: now
    if (top != null && now - since >= cfg.speakerSwitchMaxMs) return SpeakerState(top, null)
    return SpeakerState(s.talker, SpeakerState.Pending(top, since, now + (if (top != null) cfg.speakerSwitchMs else cfg.speakerHoldMs)))
}


fun advanceSpeaker(s: SpeakerState, now: Double): SpeakerState {
    val p = s.pending
    return if (p != null && now >= p.due) SpeakerState(p.to, null) else s
}


fun keepSpeakers(s: SpeakerState, present: (String) -> Boolean): SpeakerState {
    val talker = if (s.talker != null && present(s.talker)) s.talker else null
    val p = s.pending
    val pending = if (p != null && p.to != null && !present(p.to)) null else p
    return if (talker == s.talker && pending === s.pending) s else SpeakerState(talker, pending)
}



fun speakerOf(s: SpeakerState, members: List<CallMember>, sharerId: String?): String? {
    if (s.talker != null && members.any { it.id == s.talker }) return s.talker
    val you = members.firstOrNull { it.isLocal }?.id
    if (sharerId != null && sharerId != you && members.any { it.id == sharerId }) return sharerId
    return you ?: members.firstOrNull()?.id
}


private const val SHARE_ASPECT_UNKNOWN = 16.0 / 9

class CallLayoutController(
    
    private val overflow: Overflow = Overflow.PAGES,
    private var allowRotation: Boolean = false,
    
    private val now: () -> Double = { System.nanoTime() / 1e6 },
    private val cfg: LayoutConfig = LayoutConfig.DEFAULT,
) {
    private class Share(val sharerId: String, val aspect: Double)

    val specVersion = LAYOUT_SPEC_VERSION

    private var window: Size? = null
    private var safe = Insets.ZERO
    private var fold: Rect? = null
    private var controls: Size? = null
    private var shownContent: Rect? = null
    private var hiddenContent: Rect? = null
    private var callType = CallType.GROUP
    
    private var members = listOf<CallMember>()
    
    private val frames = mutableMapOf<String, Double>()
    private var speakers = setOf<String>()
    
    private var speakerIds = listOf<String>()
    private var share: Share? = null
    private var shareZoom = ContentMode.FIT
    private var swapped = false
    
    private var pipCorner = if (overflow == Overflow.MORE) PipCorner.TL else PipCorner.BR
    private var page = 0
    private var hidden = false
    private var holdUntil = Double.NEGATIVE_INFINITY
    private var held = false
    private var lastStep = Double.NEGATIVE_INFINITY
    private var scheduler = createScheduler()
    private var aspects = createAspectState()
    private var focus: String? = null
    private var speakerState = createSpeakerState()
    
    private data class Shape(val plan: LayoutPlan, val hidden: Boolean, val pager: Pager?, val focus: String?, val speaker: String?)
    private var shape: Shape? = null

    
    
    private data class LaidOut(val input: LayoutInput, val hidden: Boolean, val focus: String?)
    private var laidOut: LaidOut? = null
    private var timer: Job? = null

    private val _state = MutableStateFlow(ControllerState())
    val state: StateFlow<ControllerState> = _state.asStateFlow()

    
    @get:Synchronized
    var lastInput: LayoutInput? = null
        private set

    
    
    @Synchronized
    fun start(scope: CoroutineScope, onError: (Exception) -> Unit) {
        if (timer?.isActive == true) return
        timer = scope.launch {
            while (true) {
                delay(cfg.tickMs)
                try {
                    tick()
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        timer?.cancel()
        timer = null
    }

    

    
    
    @Synchronized
    fun setWindow(size: Size, safe: Insets = Insets.ZERO, fold: Rect? = null) {
        window = size
        this.safe = safe
        this.fold = crossingFold(size, fold)
        update(false)
    }

    
    
    
    @Synchronized
    fun setTabletopControls(size: Size?) {
        if (size == controls) return
        controls = size
        update(false)
    }

    
    @Synchronized
    fun setContent(shown: Rect, hidden: Rect = shown) {
        shownContent = shown
        hiddenContent = hidden
        update(false)
    }

    
    @Synchronized
    fun setCallType(type: CallType) {
        if (type == callType) return
        callType = type
        swapped = false
        update(true)
    }

    
    
    
    
    @Synchronized
    fun setParticipants(list: List<CallMember>) {
        val byId = list.associateBy { it.id }
        val wasIn = members.map { it.id }.toSet()
        val before = speakerIds.firstOrNull { it in wasIn }
        val kept = members.filter { it.id in byId }.map { byId.getValue(it.id) }
        val known = kept.map { it.id }.toSet()
        val next = kept + list.filter { it.id !in known }
        val you = next.firstOrNull { it.isLocal }
        members = if (you != null) listOf(you) + next.filter { it !== you } else next
        scheduler = keepOnly(scheduler, byId.keys)
        speakerState = keepSpeakers(speakerState) { it in byId }
        
        
        val after = speakerIds.firstOrNull { it in byId }
        if (after != null && after != before && after !in wasIn) {
            speakerState = hearSpeakers(speakerState, speakerIds, { it in byId }, now(), cfg)
        }
        frames.keys.retainAll { byId[it]?.cameraOn == true }
        if (callType == CallType.ONE_ON_ONE && (members.size != 2 || members.any { !it.cameraOn })) swapped = false
        update(true)
    }

    
    
    @Synchronized
    fun setVideoSize(id: String, width: Double, height: Double) {
        val m = members.firstOrNull { it.id == id }
        if (m?.cameraOn != true || !(width > 0 && height > 0)) return
        frames[id] = width / height
        update(true)
    }

    
    
    @Synchronized
    fun setSpeakers(ids: List<String>) {
        speakerIds = ids.toList()
        speakers = ids.toSet()
        speakerState = hearSpeakers(speakerState, ids, { id -> members.any { it.id == id } }, now(), cfg)
        publishSpeaker()
    }

    
    
    @Synchronized
    fun setShare(share: ScreenShare?) {
        if (share != null && this.share == null) shareZoom = ContentMode.FIT
        this.share = share?.let {
            val known = it.width > 0 && it.height > 0
            val kept = this.share?.takeIf { s -> s.sharerId == it.sharerId }?.aspect ?: SHARE_ASPECT_UNKNOWN
            Share(it.sharerId, if (known) it.width / it.height else kept)
        }
        update(true)
    }

    @Synchronized
    fun setAllowRotation(on: Boolean) {
        allowRotation = on
        update(false)
    }

    
    
    @Synchronized
    fun setControlsHidden(hidden: Boolean) {
        if (hidden == this.hidden) return
        this.hidden = hidden
        update(true)
    }

    
    
    @Synchronized
    fun setSchedulerHold(on: Boolean) {
        held = on
    }

    

    
    @Synchronized
    fun toggleControls() {
        if (share != null) return
        hidden = !hidden
        update(true)
    }

    
    @Synchronized
    fun turnPage(delta: Int) {
        val g = _state.value.plan?.grid ?: return
        showPage(g.page + delta)
    }

    
    @Synchronized
    fun showPage(n: Int) {
        val g = _state.value.plan?.grid
        if (g == null || g.pages <= 1) return
        holdUntil = now() + cfg.pageTurnHoldMs
        val next = max(0, min(g.pages - 1, n))
        if (next == page) return
        page = next
        update(true)
    }

    
    @Synchronized
    fun dropPip(center: Point) {
        val area = _state.value.plan?.pipArea ?: return
        pipCorner = snapPipCorner(center, area)
        update(true)
    }

    
    
    
    @Synchronized
    fun toggleSwap() {
        if (callType == CallType.ONE_ON_ONE && lastInput?.participants?.let { ps -> ps.size == 2 && ps.all { it.videoOn } } != true) return
        swapped = !swapped
        update(true)
    }

    @Synchronized
    fun toggleShareZoom() {
        if (share == null) return
        shareZoom = if (shareZoom == ContentMode.FIT) ContentMode.FILL else ContentMode.FIT
        update(true)
    }

    
    
    @Synchronized
    fun tick() {
        val now = now()
        speakerState = advanceSpeaker(speakerState, now)
        update(true, now - lastStep >= cfg.schedulerStepMs)
        publishSpeaker()
    }

    

    private fun speakerNow(): String? = speakerOf(speakerState, members, share?.sharerId)

    
    private fun publishSpeaker() {
        val speaker = speakerNow()
        if (speaker == _state.value.speaker) return
        _state.value = _state.value.copy(animated = false, speaker = speaker)
    }

    

    
    private fun participants(now: Double): List<Participant> {
        val raw = members.associate { m -> m.id to if (m.cameraOn) frames[m.id] else null }
        aspects = settleAspects(aspects, raw, now.toLong(), cfg)
        return members.map { m ->
            val aspect = if (m.cameraOn) aspects.settled[m.id] else null
            Participant(id = m.id, isLocal = m.isLocal, videoOn = aspect != null, videoAspect = aspect, speaking = m.id in speakers, cameraOn = m.cameraOn)
        }
    }

    
    private fun focusOf(ordered: List<Participant>, now: Double): String? {
        val others = ordered.filter { !it.isLocal }
        if (others.isEmpty()) return ordered.firstOrNull()?.id
        if (isOneOnOne(callType, ordered.size)) return others[0].id
        val since = scheduler.speakingSince
        val speakers = others
            .filter { p -> p.speaking && since[p.id]?.let { now - it >= cfg.promoteSpeakingMs } == true }
            .sortedBy { since.getValue(it.id) }
        if (speakers.any { it.id == focus }) return focus
        if (speakers.isNotEmpty()) return speakers[0].id
        return if (others.any { it.id == focus }) focus else others[0].id
    }

    private fun update(animated: Boolean, step: Boolean = false) {
        val now = now()
        val ps = participants(now)
        if (step) {
            lastStep = now
            val visible = _state.value.plan?.grid?.visibleCount ?: ps.size
            scheduler = stepScheduler(scheduler, ps, now.toLong(), visible, held || now < holdUntil, cfg)
        }
        val win = window ?: return
        val shown = shownContent ?: return
        val hidden = this.hidden && share == null
        val content = if (hidden) hiddenContent ?: shown else shown
        val ordered = applyOrder(ps, scheduler)
        focus = focusOf(ordered, now)
        val input = LayoutInput(
            window = win,
            content = content,
            callType = callType,
            participants = ordered,
            share = share?.let { ShareInput(sharerId = it.sharerId, aspect = it.aspect, zoom = shareZoom) },
            swapped = swapped,
            pipCorner = pipCorner,
            safe = safe,
            fold = fold,
            allowRotation = allowRotation,
            overflow = overflow,
            page = page,
            pagingContent = if (hidden) shown else null,
            controlsSize = controls,
        )
        
        
        val key = LaidOut(input.copy(participants = ordered.map { it.copy(speaking = false, cameraOn = null) }), hidden, focus)
        if (key == laidOut) return
        laidOut = key
        val plan = resolveLayout(input, cfg)
        val g = plan.grid
        if (g != null && g.page != page) page = g.page
        val pager = if (g != null && g.pages > 1) Pager(g.page, g.pages, g.later, saneRect(groupStage(win, content, fold, cfg))) else null
        lastInput = input
        val speaker = speakerNow()
        val shape = Shape(plan, hidden, pager, focus, speaker)
        if (shape == this.shape) return
        this.shape = shape
        _state.value = ControllerState(plan = plan, animated = animated, controlsHidden = hidden, pager = pager, focus = focus, speaker = speaker)
    }
}
