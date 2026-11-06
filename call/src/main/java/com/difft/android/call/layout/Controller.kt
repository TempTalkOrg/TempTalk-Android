// Synced from call-layout bf6b84ecbe, ports/kotlin/src/main/kotlin/org/difft/calllayout/Controller.kt (spec v25), by scripts/sync-apps.ts.
// Do not edit here: change call-layout and sync again.

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

// The call page's layout state (decision 32), ported from src/core/controller.ts. The app reports
// what happens — the window, its own bars, who is in the call and whose camera is on, video frame
// sizes, who speaks, a share, and the user's taps, swipes and drags — and draws the plan the
// controller publishes. The controller keeps everything in between: the order people joined in,
// your card first; video from its first frame; aspects through settleAspects; the speaker
// scheduler; the page; the controls hidden; the 1on1 swap and corner; the share zoom; who to show
// where there is room for one person. Its timer fires every tickMs in the scope given to start();
// tests drive tick() with their own clock instead.
//
// Every method takes a lock, so the app may report from any thread; the state is published on the
// thread that changed it.

// A person as the app knows them: who, and whether their camera is published and unmuted.
data class CallMember(val id: String, val isLocal: Boolean, val cameraOn: Boolean)

// A share as the app knows it: who shares, and the size of its frames.
data class ScreenShare(val sharerId: String, val width: Double, val height: Double)

// The page indicator: the page shown, how many there are, how many people are on the pages after,
// and the area the grid's cards sit in, beside which the bar goes.
data class Pager(val page: Int, val pages: Int, val later: Int, val area: Rect)

data class ControllerState(
    val plan: LayoutPlan? = null,
    // Move the cards to this plan over layoutMoveMs; false after a change of window or bars, which
    // lays out at once.
    val animated: Boolean = false,
    // The status bar, title bar and toolbar are hidden: the user tapped the video (decision 25) or the
    // app asked (setControlsHidden, decision 33); never while a share is on, which keeps its own tap.
    val controlsHidden: Boolean = false,
    // Phones and folds with more than one page.
    val pager: Pager? = null,
    // The one person to show where there is room for one (Android's picture in picture, Desktop's
    // floating bar; decision 33): in a 1on1 the other person; in a group whoever has spoken for
    // promoteSpeakingMs, kept until someone else has, and before anyone has the first person after
    // you; you while alone; null with nobody reported. A share, while on, is shown instead.
    val focus: String? = null,
)

// A share's aspect until its size is known.
private const val SHARE_ASPECT_UNKNOWN = 16.0 / 9

class CallLayoutController(
    // Phones and folds turn pages; Desktop shows a "+N" cell (LayoutInput.overflow).
    private val overflow: Overflow = Overflow.PAGES,
    private var allowRotation: Boolean = false,
    // Milliseconds on a monotonic clock.
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
    // Join order, you first.
    private var members = listOf<CallMember>()
    // The latest frame aspect of each camera that is on.
    private val frames = mutableMapOf<String, Double>()
    private var speakers = setOf<String>()
    private var share: Share? = null
    private var shareZoom = ContentMode.FIT
    private var swapped = false
    private var pipCorner = PipCorner.BR
    private var page = 0
    private var hidden = false
    private var holdUntil = Double.NEGATIVE_INFINITY
    private var held = false
    private var lastStep = Double.NEGATIVE_INFINITY
    private var scheduler = createScheduler()
    private var aspects = createAspectState()
    private var focus: String? = null
    // What subscribers see apart from `animated`: a change of any of it publishes a new state.
    private data class Shape(val plan: LayoutPlan, val hidden: Boolean, val pager: Pager?, val focus: String?)
    private var shape: Shape? = null

    // What the last plan was made from, with the controls and focus, so a tick that changes none of
    // it skips the layout.
    private data class LaidOut(val input: LayoutInput, val hidden: Boolean, val focus: String?)
    private var laidOut: LaidOut? = null
    private var timer: Job? = null

    private val _state = MutableStateFlow(ControllerState())
    val state: StateFlow<ControllerState> = _state.asStateFlow()

    // The input of the latest plan, for logs and for replaying a report in the simulator.
    @get:Synchronized
    var lastInput: LayoutInput? = null
        private set

    // Ticks every tickMs in `scope` until stop() or the scope ends. A tick that throws goes to
    // onError for the app to log, and the timer goes on: a layout bug costs one frame, not the call.
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

    // ---------- what the app reports ----------

    // The window, its safe area as it is now (with the status bar hidden when it is), and the fold
    // while half open, in window coordinates.
    @Synchronized
    fun setWindow(size: Size, safe: Insets = Insets.ZERO, fold: Rect? = null) {
        window = size
        this.safe = safe
        this.fold = crossingFold(size, fold)
        update(false)
    }

    // The size of the controls the app keeps below a half-open level fold, for the layout to place
    // them (LayoutPlan.controls) and the floating window around them (decision 39); null when it has
    // none there. The content areas then run down to the window bottom.
    @Synchronized
    fun setTabletopControls(size: Size?) {
        if (size == controls) return
        controls = size
        update(false)
    }

    // The area the cards may use with the app's bars shown, and with them hidden (decision 25).
    @Synchronized
    fun setContent(shown: Rect, hidden: Rect = shown) {
        shownContent = shown
        hiddenContent = hidden
        update(false)
    }

    // A new call type starts unswapped.
    @Synchronized
    fun setCallType(type: CallType) {
        if (type == callType) return
        callType = type
        swapped = false
        update(true)
    }

    // Everyone in the call, in any order: people keep the order they joined in, newcomers and
    // people reconnecting go last (decision 20), you first: whoever left drops out of the scheduler at
    // once, not at its next step. An empty list publishes a plan without cards. A camera turned off
    // waits for a new first frame when it comes back, and in a 1on1 ends a swap (decision 33).
    @Synchronized
    fun setParticipants(list: List<CallMember>) {
        val byId = list.associateBy { it.id }
        val kept = members.filter { it.id in byId }.map { byId.getValue(it.id) }
        val known = kept.map { it.id }.toSet()
        val next = kept + list.filter { it.id !in known }
        val you = next.firstOrNull { it.isLocal }
        members = if (you != null) listOf(you) + next.filter { it !== you } else next
        scheduler = keepOnly(scheduler, byId.keys)
        frames.keys.retainAll { byId[it]?.cameraOn == true }
        if (callType == CallType.ONE_ON_ONE && (members.size != 2 || members.any { !it.cameraOn })) swapped = false
        update(true)
    }

    // A video frame's size, rotation applied: the first one shows the video, later ones reshape its
    // card once they hold for aspectSettleMs.
    @Synchronized
    fun setVideoSize(id: String, width: Double, height: Double) {
        val m = members.firstOrNull { it.id == id }
        if (m?.cameraOn != true || !(width > 0 && height > 0)) return
        frames[id] = width / height
        update(true)
    }

    // Who speaks now; the scheduler acts on it at its next step.
    @Synchronized
    fun setSpeakers(ids: List<String>) {
        speakers = ids.toSet()
    }

    // A share starts whole (fit); null ends it. A size not known yet (0 × 0, as a track's dimensions
    // are before its first frame) keeps the share on at the aspect it had, or 16:9 until it comes.
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

    // Hides or shows the controls whatever they are now, for the app's own reasons (a panel opens, an
    // alert comes in; decision 33). A share keeps them shown while it lasts either way.
    @Synchronized
    fun setControlsHidden(hidden: Boolean) {
        if (hidden == this.hidden) return
        this.hidden = hidden
        update(true)
    }

    // While on (a menu open on a card), the scheduler moves nobody; joins and leaves still show
    // (decision 33).
    @Synchronized
    fun setSchedulerHold(on: Boolean) {
        held = on
    }

    // ---------- what the user does ----------

    // A tap on the video, not on the floating window or a share.
    @Synchronized
    fun toggleControls() {
        if (share != null) return
        hidden = !hidden
        update(true)
    }

    // Up (negative) or down (positive) by `delta` pages.
    @Synchronized
    fun turnPage(delta: Int) {
        val g = _state.value.plan?.grid ?: return
        showPage(g.page + delta)
    }

    // Shows page n, clamped; a turn holds the scheduler for pageTurnHoldMs.
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

    // The floating window let go at `center`: it snaps to the nearest corner of pipArea.
    @Synchronized
    fun dropPip(center: Point) {
        val area = _state.value.plan?.pipArea ?: return
        pipCorner = snapPipCorner(center, area)
        update(true)
    }

    // 1on1: your video into the main slot and back, only while both videos show; a tap before that
    // is dropped, not kept to swap by itself once the other video comes (decision 33). A group of
    // two: the other person first.
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

    // Every tickMs: settle aspects, step the scheduler every schedulerStepMs, lay out again.
    @Synchronized
    fun tick() {
        update(true, now() - lastStep >= cfg.schedulerStepMs)
    }

    // ---------- layout ----------

    // The ported aspect debounce and scheduler count whole milliseconds.
    private fun participants(now: Double): List<Participant> {
        val raw = members.associate { m -> m.id to if (m.cameraOn) frames[m.id] else null }
        aspects = settleAspects(aspects, raw, now.toLong(), cfg)
        return members.map { m ->
            val aspect = if (m.cameraOn) aspects.settled[m.id] else null
            Participant(id = m.id, isLocal = m.isLocal, videoOn = aspect != null, videoAspect = aspect, speaking = m.id in speakers, cameraOn = m.cameraOn)
        }
    }

    // Who the room-for-one view shows, from people in scheduler order (see ControllerState.focus).
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
        // The plan follows from its input alone, and not from who speaks or whose camera is on (the
        // scheduler has already ordered people by them), so a tick that changes nothing else ends here.
        val key = LaidOut(input.copy(participants = ordered.map { it.copy(speaking = false, cameraOn = null) }), hidden, focus)
        if (key == laidOut) return
        laidOut = key
        val plan = resolveLayout(input, cfg)
        val g = plan.grid
        if (g != null && g.page != page) page = g.page
        val pager = if (g != null && g.pages > 1) Pager(g.page, g.pages, g.later, saneRect(groupStage(win, content, fold, cfg))) else null
        lastInput = input
        val shape = Shape(plan, hidden, pager, focus)
        if (shape == this.shape) return
        this.shape = shape
        _state.value = ControllerState(plan = plan, animated = animated, controlsHidden = hidden, pager = pager, focus = focus)
    }
}
