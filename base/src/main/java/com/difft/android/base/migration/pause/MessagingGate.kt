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


sealed interface MessagingPauseState {
    
    data object Loading : MessagingPauseState

    
    data object Open : MessagingPauseState

    




    data class Paused(val record: MigrationPauseRecord?) : MessagingPauseState
}

enum class AdmissionKind { RECEIVE, SEND }











class MessagingTicket internal constructor(
    private val gate: MessagingGate,
    val kind: AdmissionKind,
    val source: String,
) : Closeable {
    private val closed = AtomicBoolean(false)

    
    val isClosed: Boolean get() = closed.get()

    






    fun retain(source: String): MessagingTicket? = gate.retain(this, source)

    override fun close() {
        if (closed.compareAndSet(false, true)) gate.release(kind)
    }
}









@Singleton
class MessagingGate @Inject constructor() {

    private val lock = Any()

    
    private var receiveInFlight = 0
    private var sendInFlight = 0

    private val stateFlow = MutableStateFlow<MessagingPauseState>(MessagingPauseState.Loading)

    
    private val inFlight = MutableStateFlow(0)

    
    val onChanged: StateFlow<MessagingPauseState> = stateFlow.asStateFlow()

    val state: MessagingPauseState get() = stateFlow.value

    val isOpen: Boolean get() = stateFlow.value == MessagingPauseState.Open

    
    fun tryAdmit(kind: AdmissionKind, source: String): MessagingTicket? = synchronized(lock) {
        if (stateFlow.value != MessagingPauseState.Open) return null
        acquireLocked(kind, source)
    }

    



    fun tryAdmitAsOwner(owner: String, kind: AdmissionKind, source: String): MessagingTicket? = synchronized(lock) {
        val record = (stateFlow.value as? MessagingPauseState.Paused)?.record
        if (record?.owner != owner || record.phase != MigrationPausePhase.EXPORTING) return null
        acquireLocked(kind, source)
    }

    







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
        
        val idle = inFlight.value == 0 || withTimeoutOrNull(timeout) { inFlight.first { it == 0 } } != null
        val elapsed = System.currentTimeMillis() - startedAt
        if (!idle) {
            L.w { "[MigrationPause] event=quiescence_timeout ms=$elapsed inflightAtClose=$atClose inflightNow=${inFlight.value}" }
            return false
        }
        
        val stillPaused = synchronized(lock) { (stateFlow.value as? MessagingPauseState.Paused)?.record?.owner == owner }
        if (!stillPaused) {
            L.w { "[MigrationPause] event=quiescence_rejected owner=${owner.take(8)} state=${describe(state)}" }
            return false
        }
        L.i { "[MigrationPause] event=quiescence_done ms=$elapsed inflightAtClose=$atClose" }
        return true
    }

    



    internal fun retain(parent: MessagingTicket, source: String): MessagingTicket? = synchronized(lock) {
        if (parent.isClosed) null else acquireLocked(parent.kind, source)
    }

    
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
