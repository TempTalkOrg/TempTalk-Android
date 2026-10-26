package com.difft.android.startup.prompt

import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.time.ServerTimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the one cancelable startup prompt a foreground session may show (#1355).
 *
 * - Priority is [PromptId] order; the first ELIGIBLE, not snoozed entry wins. A lower entry is not
 *   chosen while a higher one is UNKNOWN, for at most 15 s after the host first became presentable;
 *   after that UNKNOWN entries are skipped. Only NOT_ELIGIBLE removes a record.
 * - One prompt per session: Declined and ActionTaken use the budget, Preempted does not. A prompt
 *   still unanswered when the app goes to the background stays current and records nothing; it is
 *   the prompt of the next session too, until it is answered, preempted or resolved.
 * - Any active [GateId] blocks choosing and preempts a prompt on screen.
 * - A session that starts within 30 minutes of a primary action (our own trip to Settings, even
 *   across process death) starts with its budget used.
 *
 * Back-off timestamps use the trusted clock ([clock]); in-session durations (late window, hold cap)
 * use [elapsed], which never jumps when the trusted clock anchors.
 *
 * The host renders [current] and reports outcomes. Every state change runs under one [Mutex] in the
 * coordinator's own scope, so a host that goes away mid-write never cancels a persisted outcome.
 */
@Singleton
class StartupPromptCoordinator internal constructor(
    private val store: StartupPromptStore,
    private val providers: StartupPromptProviders,
    private val sessions: StateFlow<PromptSession>,
    private val clock: () -> Long,
    private val elapsed: () -> Long,
    private val scope: CoroutineScope,
) {

    @Inject
    constructor(store: StartupPromptStore, providers: StartupPromptProviders, tracker: PromptSessionTracker) :
        this(store, providers, tracker.session, ServerTimeProvider::nowMillis, SystemClock::elapsedRealtime, appScope)

    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    private val _current = MutableStateFlow<PromptTicket?>(null)

    /** The prompt to render now, or null. */
    val current: StateFlow<PromptTicket?> = _current.asStateFlow()

    // Guarded by mutex.
    private var sessionId = 0L
    private var sessionActive = false
    private var budgetUsed = false
    private var ticketShown = false
    private var presentable = false
    private var firstPresentableAt = 0L
    private var dualPaneConversationOpen = false
    private var notificationRationale = false
    private var gates: Set<GateId> = emptySet()
    private var seq = 0L
    private var holdCapJob: Job? = null

    /** Primary actions of this process, so the own-trip rule holds even when their write failed. */
    private val inProcessPending = mutableMapOf<PromptId, Long>()

    /** Rationale before the outstanding system request, the record's copy as fallback. */
    private var inProcessRationaleBefore: Boolean? = null

    /** The ticket whose primary action was recorded last, so an abort can undo exactly that one. */
    private var lastAction: PromptTicket? = null

    /** (id, reason) pairs already logged as skipped in this session. */
    private val loggedSkips = mutableSetOf<String>()

    /** Idempotent; the host calls it when it attaches. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { sessions.collect { serialized { onSession(it) } } }
        scope.launch { providers.gates().collect { serialized { onGates(it) } } }
        scope.launch { providers.changes().collect { serialized { evaluateLocked() } } }
    }

    /**
     * Host state: presentable (resumed, no app-lock decision pending, settled), dual-pane detail state,
     * and whether the system would still show its notification-permission dialog (the Activity's
     * current `shouldShowRequestPermissionRationale`).
     */
    fun setHostState(presentable: Boolean, dualPaneConversationOpen: Boolean, notificationRationale: Boolean = false) {
        scope.launch {
            serialized {
                this@StartupPromptCoordinator.presentable = presentable
                this@StartupPromptCoordinator.dualPaneConversationOpen = dualPaneConversationOpen
                this@StartupPromptCoordinator.notificationRationale = notificationRationale
                if (presentable && sessionActive && firstPresentableAt == 0L) firstPresentableAt = elapsed()
                if (presentable) evaluateLocked()
            }
        }
    }

    /** The host mounted [ticket]; a re-render after recreation records nothing. */
    suspend fun onShown(ticket: PromptTicket) {
        perform { shownLocked(ticket) }
    }

    private suspend fun shownLocked(ticket: PromptTicket) {
        if (_current.value?.seq != ticket.seq || ticketShown) return
        ticketShown = true
        val now = clock()
        val records = store.update {
            it.edit(ticket.id) { r -> r.keyed(ticket).copy(shownCount = r.shownCount + 1, lastShownAt = now, showingSince = now) }
        }
        val record = records[ticket.id]
        val via = if (ticket.viaSystemRequest) "system" else "dialog"
        L.i { "[StartupPrompt] show id=${ticket.id} shown=${record?.shownCount} declines=${record?.declines} via=$via" }
    }

    /**
     * Primary action, including the system permission request ([rationaleBefore] is the rationale
     * read just before it, kept on the record so a recreation or process death in the system dialog
     * keeps it). Returns once the outcome is written, or kept in memory for this process if the write
     * fails, so the host acts only afterwards; false for a stale ticket.
     */
    suspend fun onActionTaken(ticket: PromptTicket, rationaleBefore: Boolean = false): Boolean = perform {
        if (isStale(ticket, "ACTION_TAKEN")) return@perform false
        val now = clock()
        inProcessPending[ticket.id] = now
        if (ticket.viaSystemRequest) inProcessRationaleBefore = rationaleBefore
        lastAction = ticket
        persistOrLog {
            store.update {
                it.edit(ticket.id) { r -> r.keyed(ticket).copy(pendingActionAt = now, showingSince = 0, rationaleBefore = rationaleBefore) }
            }
        }
        closeTicket(ticket, budget = true)
        // The host is leaving (or handing off); it reports again once it is back and settled, so a
        // pending action is never reconciled before the user could act on it.
        presentable = false
        L.i { "[StartupPrompt] outcome id=${ticket.id} result=ACTION_TAKEN" }
        true
    } ?: false

    /**
     * The host recorded [ticket]'s primary action but never performed it (it was destroyed in
     * between). Undoes the pending trip, returns the budget and makes the prompt current again, so the
     * recreated host shows it. Anything else (an older ticket, a result already seen) is ignored.
     */
    suspend fun onActionAborted(ticket: PromptTicket) {
        perform {
            if (lastAction?.seq != ticket.seq) return@perform
            lastAction = null
            inProcessPending.remove(ticket.id)
            if (ticket.viaSystemRequest) inProcessRationaleBefore = null
            persistOrLog { store.update { it.edit(ticket.id) { r -> r.copy(pendingActionAt = 0, rationaleBefore = false) } } }
            budgetUsed = false
            if (sessionActive && gates.isEmpty() && _current.value == null) {
                ticketShown = false
                _current.value = ticket.copy(seq = ++seq)
            }
            L.i { "[StartupPrompt] outcome id=${ticket.id} result=ACTION_ABORTED" }
        }
    }

    /** "Not now", outside tap or back. A stale ticket (already answered or replaced) records nothing. */
    suspend fun onDeclined(ticket: PromptTicket) {
        perform {
            if (isStale(ticket, "DECLINED")) return@perform
            val now = clock()
            val records = persistOrLog {
                store.update { it.edit(ticket.id) { r -> PromptBackoffPolicy.declined(r.keyed(ticket), now) } }
            }
            closeTicket(ticket, budget = true)
            logDeclined(ticket.id, records?.get(ticket.id), now)
        }
    }

    /** Result of the system POST_NOTIFICATIONS request; the rationale before it comes from the record. */
    suspend fun onNotificationRequestResult(granted: Boolean, rationaleAfter: Boolean) {
        perform { notificationResultLocked(granted, rationaleAfter) }
    }

    private suspend fun notificationResultLocked(granted: Boolean, rationaleAfter: Boolean) {
        val id = PromptId.NOTIFICATIONS
        val now = clock()
        val record = store.read()[id] ?: PromptRecord()
        val rationaleBefore = inProcessRationaleBefore ?: record.rationaleBefore
        inProcessRationaleBefore = null
        lastAction = null
        inProcessPending.remove(id)
        when (val outcome = NotificationRequestOutcome.map(granted, rationaleBefore, rationaleAfter)) {
            NotificationRequestOutcome.Granted -> {
                val stillEligible = providers.evaluate(id).eligibility == Eligibility.ELIGIBLE
                store.update { map ->
                    if (stillEligible) map.edit(id) { it.copy(pendingActionAt = 0, rationaleBefore = false) } else map - id
                }
                L.i { "[StartupPrompt] outcome id=$id result=GRANTED resolved=${!stillEligible}" }
            }
            is NotificationRequestOutcome.Declined -> {
                val extra = if (outcome.countsAsSystemDenial) 1 else 0
                val records = store.update { map ->
                    map.edit(id) { r ->
                        PromptBackoffPolicy.declined(r, now).copy(systemDenials = r.systemDenials + extra, rationaleBefore = false)
                    }
                }
                logDeclined(id, records[id], now)
            }
            NotificationRequestOutcome.ShowAppDialog -> {
                // The system did not ask: its route is exhausted from now on.
                store.update { map ->
                    map.edit(id) {
                        it.copy(
                            pendingActionAt = 0,
                            rationaleBefore = false,
                            systemDenials = maxOf(it.systemDenials, NotificationRequestOutcome.PERMANENT_AFTER_DENIALS),
                        )
                    }
                }
                if (sessionActive && gates.isEmpty()) {
                    ticketShown = false
                    _current.value = PromptTicket(id, ++seq, issue = ReadinessIssue.APP_NOTIFICATIONS_OFF)
                    L.i { "[StartupPrompt] outcome id=$id result=SYSTEM_SILENT next=dialog" }
                } else if (sessionActive) {
                    // A gate came up during the system dialog: the app dialog is preempted, the
                    // session keeps its budget and the next evaluation after the gate chooses again.
                    budgetUsed = false
                    L.i { "[StartupPrompt] outcome id=$id result=SYSTEM_SILENT next=preempted gate=${gates.joinToString(",")}" }
                }
            }
        }
    }

    private suspend fun onSession(session: PromptSession) {
        if (sessionActive && (session.id != sessionId || !session.active)) endSessionLocked()
        if (session.active && !sessionActive) startSessionLocked(session.id)
    }

    private suspend fun startSessionLocked(id: Long) {
        sessionId = id
        sessionActive = true
        loggedSkips.clear()
        // A prompt left unanswered in the background is still this session's prompt.
        val carried = _current.value
        budgetUsed = carried != null
        if (carried == null) ticketShown = false
        val now = clock()
        firstPresentableAt = if (presentable) elapsed() else 0L
        var records = store.read()
        val leftover = records.filterValues { it.showingSince != 0L }.keys - setOfNotNull(carried?.id)
        if (leftover.isNotEmpty()) {
            // The process died while these were on screen: nothing was answered, so they are simply due again.
            records = store.update { map -> map.mapValues { (k, r) -> if (k in leftover) r.copy(showingSince = 0) else r } }
            L.i { "[StartupPrompt] showing cleared id=${leftover.joinToString(",")} cause=processDeath" }
        }
        inProcessPending.values.removeAll { now - it !in 0..PromptBackoffPolicy.OWN_TRIP_WINDOW_MS }
        val ownTrip = inProcessPending.isNotEmpty() ||
            records.values.any { it.pendingActionAt != 0L && now - it.pendingActionAt in 0..PromptBackoffPolicy.OWN_TRIP_WINDOW_MS }
        if (ownTrip) budgetUsed = true
        val reason = when {
            carried != null -> "carried"
            ownTrip -> "ownTrip"
            else -> "foreground"
        }
        L.i { "[StartupPrompt] session start id=$id reason=$reason${carried?.let { " carried=${it.id}" }.orEmpty()}" }
        providers.onSessionStart()
        evaluateLocked()
    }

    private fun endSessionLocked() {
        // An unanswered prompt stays current and on screen for the next session; nothing is recorded.
        val carried = _current.value
        sessionActive = false
        holdCapJob?.cancel()
        holdCapJob = null
        L.i { "[StartupPrompt] session end id=$sessionId${carried?.let { " carried=${it.id}" }.orEmpty()}" }
    }

    private suspend fun onGates(active: Set<GateId>) {
        val previous = gates
        gates = active
        val ticket = _current.value
        if (active.isNotEmpty() && ticket != null) {
            if (ticketShown) store.update { it.edit(ticket.id) { r -> r.copy(showingSince = 0) } }
            _current.value = null
            ticketShown = false
            // Preempted: the unanswered prompt uses no budget and may come back once the gate clears.
            budgetUsed = false
            L.i { "[StartupPrompt] gate active=${active.joinToString(",")} preempt=${ticket.id}" }
        } else if (active != previous && active.isNotEmpty()) {
            L.i { "[StartupPrompt] gate active=${active.joinToString(",")}" }
        }
        if (active.isEmpty()) evaluateLocked()
    }

    private suspend fun evaluateLocked() {
        if (!sessionActive) return
        if (!presentable) {
            logSkip("ALL", "NOT_PRESENTABLE")
            return
        }
        val now = clock()
        val answers = PromptId.entries.associateWith { providers.evaluate(it) }
        val read = store.read()
        val reconciled = reconcile(read, answers, now)
        val records = if (reconciled.records != read) store.update { reconcile(it, answers, now).records } else read
        reconciled.events.forEach { event -> L.i { "[StartupPrompt] outcome $event" } }
        // Back on the host: an in-memory own trip has done its job once its record is settled (or was never written).
        inProcessPending.keys.removeAll { records[it]?.pendingActionAt == null || records[it]?.pendingActionAt == 0L }

        val ticket = _current.value
        if (ticket != null && gates.isNotEmpty()) {
            // Never keep a cancelable prompt under a gate (normally onGates already removed it).
            if (ticketShown) store.update { it.edit(ticket.id) { r -> r.copy(showingSince = 0) } }
            ticketShown = false
            _current.value = null
            budgetUsed = false
            logSkip(ticket.id.name, "GATE_ACTIVE")
            return
        }
        if (ticket != null) {
            if (answers.getValue(ticket.id).eligibility == Eligibility.NOT_ELIGIBLE) {
                // Fixed while on screen: the host removes it; a rendered prompt used the budget.
                if (ticketShown) budgetUsed = true
                ticketShown = false
                _current.value = null
            }
            return
        }
        if (budgetUsed || gates.isNotEmpty()) {
            val reason = if (budgetUsed) "BUDGET_USED" else "GATE_ACTIVE"
            answers.filterValues { it.eligibility == Eligibility.ELIGIBLE }.keys.forEach { logSkip(it.name, reason) }
            return
        }
        choose(answers, records, now)
    }

    private fun choose(answers: Map<PromptId, ProviderAnswer>, records: Map<PromptId, PromptRecord>, now: Long) {
        val sincePresentable = if (firstPresentableAt != 0L) elapsed() - firstPresentableAt else 0L
        val late = sincePresentable > PromptBackoffPolicy.LATE_PROMPT_MS
        val holdOpen = sincePresentable < PromptBackoffPolicy.UNKNOWN_HOLD_MS
        var heldBy: PromptId? = null
        for (id in PromptId.entries) {
            val answer = answers.getValue(id)
            val record = records[id] ?: PromptRecord()
            val eligible = answer.eligibility == Eligibility.ELIGIBLE
            if (record.pendingActionAt != 0L) {
                if (eligible) logSkip(id.name, "PENDING_ACTION")
                continue
            }
            if (PromptBackoffPolicy.isSnoozed(record, now)) {
                if (eligible) logSkip(id.name, "SNOOZED", "until=${record.snoozeUntil}")
                continue
            }
            if (answer.eligibility == Eligibility.UNKNOWN && heldBy == null) heldBy = id
            if (!eligible) continue
            if (heldBy != null && holdOpen) {
                // A higher entry may still turn eligible; wait for it, at most until the hold cap.
                logSkip(id.name, "HOLD_UNKNOWN", "waitingFor=$heldBy")
                scheduleHoldCap(PromptBackoffPolicy.UNKNOWN_HOLD_MS - sincePresentable)
                return
            }
            if (late && dualPaneConversationOpen) {
                // A late result must not interrupt an open conversation; it waits for the pane to close.
                logSkip(id.name, "LATE_DUAL_PANE")
                return
            }
            val viaSystem = id == PromptId.NOTIFICATIONS &&
                NotificationRequestOutcome.useSystemRequest(answer.permissionRequestable, notificationRationale, record.systemDenials)
            ticketShown = false
            _current.value = PromptTicket(id, ++seq, answer.issue, viaSystem, answer.offerKey)
            return
        }
    }

    /** One line per (id, reason) per session: why an eligible prompt was not shown. */
    private fun logSkip(id: String, reason: String, detail: String = "") {
        if (!loggedSkips.add("$id:$reason")) return
        val suffix = if (detail.isEmpty()) "" else " $detail"
        L.i { "[StartupPrompt] skip id=$id reason=$reason$suffix" }
    }

    private fun scheduleHoldCap(remainingMs: Long) {
        if (holdCapJob?.isActive == true) return
        holdCapJob = scope.launch {
            delay(remainingMs)
            serialized { evaluateLocked() }
        }
    }

    private fun isStale(ticket: PromptTicket, outcome: String): Boolean {
        if (_current.value?.seq == ticket.seq) return false
        L.w { "[StartupPrompt] outcome ignored id=${ticket.id} result=$outcome cause=staleTicket" }
        return true
    }

    /** Records the offer a prompt was shown for from its first write, so a later offer never inherits it. */
    private fun PromptRecord.keyed(ticket: PromptTicket): PromptRecord =
        if (ticket.offerKey != null) copy(offerKey = ticket.offerKey) else this

    private fun closeTicket(ticket: PromptTicket, budget: Boolean) {
        if (budget) budgetUsed = true
        if (_current.value?.seq == ticket.seq) {
            _current.value = null
            ticketShown = false
        }
    }

    /**
     * A user answer always closes the prompt and uses the budget, even when the state cannot be
     * written, so a storage failure can never bring the same (possibly non-cancelable) dialog back.
     */
    private suspend fun <T> persistOrLog(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.e { "[StartupPrompt] answer not persisted error=${e.stackTraceToString()}" }
        null
    }

    private fun logDeclined(id: PromptId, record: PromptRecord?, now: Long) {
        val nextDays = record?.let { (it.snoozeUntil - now) / DAY_MS } ?: 0
        L.i { "[StartupPrompt] outcome id=$id result=DECLINED declines=${record?.declines} next=${nextDays}d" }
    }

    private suspend fun <T> perform(block: suspend () -> T): T? = scope.async { serialized(block) }.await()

    private suspend fun <T> serialized(block: suspend () -> T): T? = mutex.withLock {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[StartupPrompt] state update failed error=${e.stackTraceToString()}" }
            null
        }
    }

    internal data class Reconciled(val records: Map<PromptId, PromptRecord>, val events: List<String>)

    internal companion object {
        private const val DAY_MS = 86_400_000L

        /**
         * Pure record maintenance for one evaluation: a confirmed NOT_ELIGIBLE removes the record
         * (a later revocation starts at step 0); a pending primary action that is still ELIGIBLE
         * counts as one decline (§9 D2), and so does one still UNKNOWN after the 30-minute own-trip
         * window; a new update offer resets its record; a snooze longer than
         * its step is clamped. UNKNOWN never changes a record.
         */
        fun reconcile(records: Map<PromptId, PromptRecord>, answers: Map<PromptId, ProviderAnswer>, now: Long): Reconciled {
            val events = mutableListOf<String>()
            val next = buildMap {
                for ((id, record) in records) {
                    val answer = answers[id] ?: ProviderAnswer.UNKNOWN
                    when (answer.eligibility) {
                        Eligibility.NOT_ELIGIBLE -> events += "id=$id result=RESOLVED"
                        Eligibility.UNKNOWN -> if (record.pendingActionAt != 0L && now - record.pendingActionAt !in 0..PromptBackoffPolicy.OWN_TRIP_WINDOW_MS) {
                            // The result could not be read for longer than an own trip lasts: count the
                            // trip as one decline so the record never waits forever.
                            val declined = PromptBackoffPolicy.declined(record, minOf(record.pendingActionAt, now))
                            events += "id=$id result=DECLINED cause=pendingExpired declines=${declined.declines}"
                            put(id, declined)
                        } else {
                            put(id, record)
                        }
                        Eligibility.ELIGIBLE -> {
                            var r = record
                            if (id == PromptId.OPTIONAL_UPDATE && r.offerKey != answer.offerKey) {
                                // Records carry their offer from the first write; any other offer starts over.
                                events += "id=$id result=NEW_OFFER"
                                r = PromptRecord(offerKey = answer.offerKey)
                            }
                            if (r.pendingActionAt != 0L) {
                                // The decline happened at the trip, not when the result was read.
                                r = PromptBackoffPolicy.declined(r, minOf(r.pendingActionAt, now))
                                events += "id=$id result=DECLINED cause=returnUnfixed declines=${r.declines}"
                            }
                            put(id, PromptBackoffPolicy.clamp(r, now))
                        }
                    }
                }
            }
            return Reconciled(next, events)
        }
    }
}

/** Applies [change] to the record of [id], creating a default one first. */
internal fun Map<PromptId, PromptRecord>.edit(id: PromptId, change: (PromptRecord) -> PromptRecord): Map<PromptId, PromptRecord> =
    this + (id to change(this[id] ?: PromptRecord()))
