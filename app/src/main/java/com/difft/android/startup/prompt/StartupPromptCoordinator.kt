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

    
    val current: StateFlow<PromptTicket?> = _current.asStateFlow()

    
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

    
    private val inProcessPending = mutableMapOf<PromptId, Long>()

    
    private var inProcessRationaleBefore: Boolean? = null

    
    private var lastAction: PromptTicket? = null

    
    private val loggedSkips = mutableSetOf<String>()

    
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { sessions.collect { serialized { onSession(it) } } }
        scope.launch { providers.gates().collect { serialized { onGates(it) } } }
        scope.launch { providers.changes().collect { serialized { evaluateLocked() } } }
    }

    




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

    
    suspend fun onShown(ticket: PromptTicket) {
        perform { shownLocked(ticket) }
    }

    private suspend fun shownLocked(ticket: PromptTicket) {
        if (_current.value?.seq != ticket.seq || ticketShown) return
        ticketShown = true
        val now = clock()
        val records = store.update {
            it.edit(ticket.id) { r ->
                r.keyed(ticket).copy(
                    shownCount = r.shownCount + 1,
                    lastShownAt = now,
                    showingSince = now,
                )
            }
        }
        val record = records[ticket.id]
        val via = if (ticket.viaSystemRequest) "system" else "dialog"
        L.i { "[StartupPrompt] show id=${ticket.id} shown=${record?.shownCount} declines=${record?.declines} via=$via${ticket.backgroundTrigger?.let { " trigger=$it" }.orEmpty()}" }
    }

    





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
        
        
        presentable = false
        L.i { "[StartupPrompt] outcome id=${ticket.id} result=ACTION_TAKEN" }
        true
    } ?: false

    




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

    
    suspend fun onDeclined(ticket: PromptTicket) {
        perform {
            if (isStale(ticket, "DECLINED")) return@perform
            val now = clock()
            val records = persistOrLog {
                store.update { it.edit(ticket.id) { r -> PromptBackoffPolicy.declined(ticket.id, r.keyed(ticket), now, endsSwitchOff = true) } }
            }
            closeTicket(ticket, budget = true)
            logDeclined(ticket.id, records?.get(ticket.id), now)
        }
    }

    
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
        
        val carried = _current.value
        budgetUsed = carried != null
        if (carried == null) ticketShown = false
        val now = clock()
        firstPresentableAt = if (presentable) elapsed() else 0L
        var records = store.read()
        val leftover = records.filterValues { it.showingSince != 0L }.keys - setOfNotNull(carried?.id)
        if (leftover.isNotEmpty()) {
            
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
        
        inProcessPending.keys.removeAll { records[it]?.pendingActionAt == null || records[it]?.pendingActionAt == 0L }

        val ticket = _current.value
        if (ticket != null && gates.isNotEmpty()) {
            
            if (ticketShown) store.update { it.edit(ticket.id) { r -> r.copy(showingSince = 0) } }
            ticketShown = false
            _current.value = null
            budgetUsed = false
            logSkip(ticket.id.name, "GATE_ACTIVE")
            return
        }
        if (ticket != null) {
            if (answers.getValue(ticket.id).eligibility == Eligibility.NOT_ELIGIBLE) {
                
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
            val record = records[id] ?: PromptRecord()
            val answer = answers.getValue(id).forRecord(id, record, now)
            val eligible = answer.eligibility == Eligibility.ELIGIBLE
            if (record.pendingActionAt != 0L) {
                if (eligible) logSkip(id.name, "PENDING_ACTION")
                continue
            }
            if (PromptBackoffPolicy.isSnoozed(id, record, now)) {
                if (eligible) logSkip(id.name, "SNOOZED", "until=${PromptBackoffPolicy.snoozedUntil(record)}")
                continue
            }
            if (answer.eligibility == Eligibility.UNKNOWN && heldBy == null) heldBy = id
            if (!eligible) continue
            if (heldBy != null && holdOpen) {
                
                logSkip(id.name, "HOLD_UNKNOWN", "waitingFor=$heldBy")
                scheduleHoldCap(PromptBackoffPolicy.UNKNOWN_HOLD_MS - sincePresentable)
                return
            }
            if (late && dualPaneConversationOpen) {
                
                logSkip(id.name, "LATE_DUAL_PANE")
                return
            }
            val viaSystem = id == PromptId.NOTIFICATIONS &&
                NotificationRequestOutcome.useSystemRequest(answer.permissionRequestable, notificationRationale, record.systemDenials)
            ticketShown = false
            _current.value = PromptTicket(id, ++seq, answer.issue, viaSystem, answer.offerKey, answer.triggers.minOrNull())
            return
        }
    }

    
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

    
    private fun PromptRecord.keyed(ticket: PromptTicket): PromptRecord =
        if (ticket.offerKey != null) copy(offerKey = ticket.offerKey) else this

    private fun closeTicket(ticket: PromptTicket, budget: Boolean) {
        if (budget) budgetUsed = true
        if (_current.value?.seq == ticket.seq) {
            _current.value = null
            ticketShown = false
        }
    }

    



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

        









        fun reconcile(records: Map<PromptId, PromptRecord>, answers: Map<PromptId, ProviderAnswer>, now: Long): Reconciled {
            val events = mutableListOf<String>()
            val next = buildMap {
                for ((id, record) in records) {
                    val answer = answers[id] ?: ProviderAnswer.UNKNOWN
                    when (answer.eligibility) {
                        Eligibility.NOT_ELIGIBLE -> {
                            
                            val kept = if (id == PromptId.BACKGROUND_RESTRICTED && record.pendingActionAt != 0L) settleTrip(id, record, now) else record
                            if (PromptBackoffPolicy.keepsHistory(id) && PromptBackoffPolicy.hasHistory(kept)) {
                                if (record.pendingActionAt != 0L || record.showingSince != 0L) events += "id=$id result=RESOLVED kept=history"
                                put(id, kept.copy(pendingActionAt = 0, showingSince = 0))
                            } else {
                                events += "id=$id result=RESOLVED"
                            }
                        }
                        Eligibility.UNKNOWN -> if (record.pendingActionAt != 0L && now - record.pendingActionAt !in 0..PromptBackoffPolicy.OWN_TRIP_WINDOW_MS) {
                            
                            
                            val settled = settleTrip(id, record, now)
                            events += "id=$id result=${if (settled.declines > record.declines) "DECLINED" else "SETTLED"} cause=pendingExpired declines=${settled.declines}"
                            put(id, settled)
                        } else {
                            put(id, record)
                        }
                        Eligibility.ELIGIBLE -> {
                            var r = record
                            if (id == PromptId.OPTIONAL_UPDATE && r.offerKey != answer.offerKey) {
                                
                                events += "id=$id result=NEW_OFFER"
                                r = PromptRecord(offerKey = answer.offerKey)
                            }
                            if (r.pendingActionAt != 0L) {
                                val settled = settleTrip(id, r, now)
                                val result = if (settled.declines > r.declines) "DECLINED" else "SETTLED"
                                r = settled
                                events += "id=$id result=$result cause=returnUnfixed declines=${r.declines}"
                            }
                            put(id, PromptBackoffPolicy.clamp(r, now, id))
                        }
                    }
                }
            }
            return Reconciled(next, events)
        }

        



        private fun settleTrip(id: PromptId, record: PromptRecord, now: Long): PromptRecord =
            if (id == PromptId.BACKGROUND_RESTRICTED) {
                PromptBackoffPolicy.restrictedTrip(record.copy(pendingActionAt = 0), minOf(record.pendingActionAt, now))
            } else {
                PromptBackoffPolicy.declined(id, record, minOf(record.pendingActionAt, now))
            }
    }
}


internal fun Map<PromptId, PromptRecord>.edit(id: PromptId, change: (PromptRecord) -> PromptRecord): Map<PromptId, PromptRecord> =
    this + (id to change(this[id] ?: PromptRecord()))
