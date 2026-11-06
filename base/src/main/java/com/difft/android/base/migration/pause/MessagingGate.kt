package com.difft.android.base.migration.pause

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/** In-memory messaging state. Every gate treats [Loading] like [Paused]. */
sealed interface MessagingPauseState {
    /** The persisted pause has not been read yet; messaging stays closed. */
    data object Loading : MessagingPauseState

    /** Messaging is open: no pause record is stored. */
    data object Open : MessagingPauseState

    /**
     * Messaging is paused: exporting, committing, awaiting a decision on an unknown outcome, or
     * awaiting the old login's end. [record] is null when the stored record is corrupt (owner
     * unknown).
     */
    data class Paused(val record: MigrationPauseRecord?) : MessagingPauseState
}

enum class AdmissionKind { RECEIVE, SEND }

/**
 * Permission to finish one unit of receive or send work, including its persistence, ACK and
 * server-side delete. Closed by the code that performs the side effect, in a `finally`, and never
 * passed across a `launch` except through [retain]. [close] is idempotent.
 *
 * Deferred persistence (a queued item, a launched write) goes through [retain]: the producer
 * retains a ticket while its own is still open and hands it with the item; the consumer closes it
 * once the item is persisted or dropped. A dropped item must be one the server still holds, or its
 * loss is a residual of the pause.
 */
class MessagingTicket internal constructor(
    private val gate: MessagingGate,
    val kind: AdmissionKind,
    val source: String,
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** True once [close] has run; a closed ticket no longer admits anything. */
    val isClosed: Boolean get() = closed.get()

    /**
     * A ticket of the same kind for one unit of this ticket's deferred work, counted like any other
     * ticket, so [MessagingGate.closeAndAwaitIdle] also waits for the unit. Issued whatever the pause
     * state, because the unit was admitted with this ticket.
     *
     * @return null when this ticket is already closed: the caller then holds no admission.
     */
    fun retain(source: String): MessagingTicket? = gate.retain(this, source)

    override fun close() {
        if (closed.compareAndSet(false, true)) gate.release(kind)
    }
}

/**
 * The single admission protocol of the migration message pause (#1382): the pause state, the
 * in-flight counts and the idle signal share one lock. A caller either gets its ticket before the
 * pause takes effect (and [closeAndAwaitIdle] waits for it) or gets null after it.
 *
 * The state is written only by [MigrationMessagePause], which commits the record to disk first and
 * then publishes it here under the same lock, so a pause that is persisted is also closed here.
 */
@Singleton
class MessagingGate @Inject constructor() {

    private val lock = Any()

    // Guarded by lock.
    private var receiveInFlight = 0
    private var sendInFlight = 0

    private val stateFlow = MutableStateFlow<MessagingPauseState>(MessagingPauseState.Loading)

    // Total tickets outstanding; written under lock, awaited outside it.
    private val inFlight = MutableStateFlow(0)

    /** Current state; emits on every change, including phase changes of a paused record. */
    val onChanged: StateFlow<MessagingPauseState> = stateFlow.asStateFlow()

    val state: MessagingPauseState get() = stateFlow.value

    val isOpen: Boolean get() = stateFlow.value == MessagingPauseState.Open

    /** A ticket when messaging is [MessagingPauseState.Open], otherwise null; the caller then does nothing. */
    fun tryAdmit(kind: AdmissionKind, source: String): MessagingTicket? = synchronized(lock) {
        if (stateFlow.value != MessagingPauseState.Open) return null
        acquireLocked(kind, source)
    }

    /**
     * A ticket for the pause owner's pre-snapshot retry-queue drain: issued only while [owner]'s
     * pause is [MigrationPausePhase.EXPORTING], and counted like any other ticket.
     */
    fun tryAdmitAsOwner(owner: String, kind: AdmissionKind, source: String): MessagingTicket? = synchronized(lock) {
        val record = (stateFlow.value as? MessagingPauseState.Paused)?.record
        if (record?.owner != owner || record.phase != MigrationPausePhase.EXPORTING) return null
        acquireLocked(kind, source)
    }

    /**
     * Waits until every ticket issued before [owner]'s pause took effect is closed. The pause
     * itself is set by [MigrationMessagePause.begin], under this lock, before this is called.
     * The owner may call it again, after its retry-queue drain, to also await the tickets that
     * drain issued or retained.
     *
     * @return false when [owner]'s pause is not in effect, or when [timeout] passes first.
     */
    suspend fun closeAndAwaitIdle(owner: String, timeout: Duration): Boolean {
        val startedAt = System.currentTimeMillis()
        val atClose = synchronized(lock) {
            val record = (stateFlow.value as? MessagingPauseState.Paused)?.record
            if (record?.owner != owner) null else "receive=$receiveInFlight,send=$sendInFlight"
        }
        if (atClose == null) {
            L.w { "[MigrationPause] event=quiescence_rejected owner=${owner.take(8)} state=${describe(state)}" }
            return false
        }
        // Checked first: a spent budget (zero timeout) still passes when nothing is in flight.
        val idle = inFlight.value == 0 || withTimeoutOrNull(timeout) { inFlight.first { it == 0 } } != null
        val elapsed = System.currentTimeMillis() - startedAt
        if (!idle) {
            L.w { "[MigrationPause] event=quiescence_timeout ms=$elapsed inflightAtClose=$atClose inflightNow=${inFlight.value}" }
            return false
        }
        // A pause cleared during the wait (logout, login change) may have admitted new work.
        val stillPaused = synchronized(lock) { (stateFlow.value as? MessagingPauseState.Paused)?.record?.owner == owner }
        if (!stillPaused) {
            L.w { "[MigrationPause] event=quiescence_rejected owner=${owner.take(8)} state=${describe(state)}" }
            return false
        }
        L.i { "[MigrationPause] event=quiescence_done ms=$elapsed inflightAtClose=$atClose" }
        return true
    }

    /**
     * Under the lock, so a parent that is open here is released only after the child is counted
     * and the in-flight count never passes through zero between the two.
     */
    internal fun retain(parent: MessagingTicket, source: String): MessagingTicket? = synchronized(lock) {
        if (parent.isClosed) null else acquireLocked(parent.kind, source)
    }

    /** Called by [MigrationMessagePause] after the matching record is durable. */
    internal fun publish(next: MessagingPauseState) {
        synchronized(lock) { stateFlow.value = next }
    }

    internal fun release(kind: AdmissionKind) {
        synchronized(lock) {
            when (kind) {
                AdmissionKind.RECEIVE -> receiveInFlight--
                AdmissionKind.SEND -> sendInFlight--
            }
            inFlight.value = receiveInFlight + sendInFlight
        }
    }

    private fun acquireLocked(kind: AdmissionKind, source: String): MessagingTicket {
        when (kind) {
            AdmissionKind.RECEIVE -> receiveInFlight++
            AdmissionKind.SEND -> sendInFlight++
        }
        inFlight.value = receiveInFlight + sendInFlight
        return MessagingTicket(this, kind, source)
    }

    internal companion object {
        fun describe(state: MessagingPauseState): String = when (state) {
            MessagingPauseState.Loading -> "loading"
            MessagingPauseState.Open -> "open"
            is MessagingPauseState.Paused -> state.record?.phase?.name?.lowercase() ?: "corrupt"
        }
    }
}
