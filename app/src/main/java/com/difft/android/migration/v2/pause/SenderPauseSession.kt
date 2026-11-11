package com.difft.android.migration.v2.pause

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPauseLoginScope
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.base.migration.pause.PauseTransition
import com.difft.android.base.migration.pause.PendingVerdictEdit
import com.difft.android.chat.messages.MigrationRetryQueueDrain
import com.difft.android.chat.messages.RetryQueueResidual
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SendCommitHooks
import com.difft.android.migration.v2.transfer.SenderSessionRef
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource


class MigrationPauseException(reason: String) : IllegalStateException(reason)


@Singleton
class SenderMessagePause @Inject constructor(
    private val pause: MigrationMessagePause,
    private val gate: MessagingGate,
    private val loginScope: MigrationPauseLoginScope,
    private val retryDrain: MigrationRetryQueueDrain,
    private val pendingStore: PendingVerdictStore,
) {
    



    fun newSession(owner: String, persistReceipt: Boolean = true): SenderPauseSession = SenderPauseSession(
        owner = owner,
        pause = pause,
        gate = gate,
        scope = loginScope::current,
        drainRetryQueues = retryDrain::drainAsOwner,
        receiptEdit = if (persistReceipt) pendingStore::saveEdit else null,
    )
}










class SenderPauseSession internal constructor(
    val owner: String,
    private val pause: MigrationMessagePause,
    private val gate: MessagingGate,
    private val scope: () -> MigrationPauseLoginScope.Scope?,
    private val drainRetryQueues: suspend (String) -> RetryQueueResidual,
    
    private val receiptEdit: ((PendingVerdict) -> PendingVerdictEdit)? = null,
    private val quiescenceTimeout: Duration = QUIESCENCE_TIMEOUT,
    private val clock: () -> Long = System::currentTimeMillis,
) : SendCommitHooks {

    
    @Volatile
    var session: SenderSessionRef? = null
        private set

    
    @Volatile
    var abortAnswer: String? = null
        private set

    
    @Volatile
    var began: Boolean = false
        private set

    
    @Volatile
    var endMarkerWritten: Boolean = false
        private set

    
    val awaitsDecision: Boolean
        get() = pause.record?.let { it.owner == owner && it.phase == MigrationPausePhase.AWAITING_DECISION } == true

    fun onSessionPrepared(ref: SenderSessionRef) {
        session = ref
    }

    








    suspend fun enter() {
        val login = scope() ?: throw MigrationPauseException(REASON_NO_LOGIN)
        if (!pause.begin(owner, login.accountScope, login.credentialHash)) {
            throw MigrationPauseException(REASON_UNAVAILABLE)
        }
        began = true
        val deadline = TimeSource.Monotonic.markNow() + quiescenceTimeout
        if (!gate.closeAndAwaitIdle(owner, quiescenceTimeout)) {
            throw MigrationPauseException(REASON_QUIESCENCE_TIMEOUT)
        }
        val residual = drainRetryQueues(owner)
        val remaining = (-deadline.elapsedNow()).coerceAtLeast(Duration.ZERO)
        if (!gate.closeAndAwaitIdle(owner, remaining)) {
            throw MigrationPauseException(REASON_QUIESCENCE_TIMEOUT)
        }
        if (residual.failed != 0) {
            L.w {
                "[MigrationPause] event=residual_abort owner=${owner.take(8)} " +
                    "failed=${residual.failed ?: "unknown"} pending=${residual.pending ?: "unknown"}"
            }
            throw MigrationPauseException(REASON_RESIDUAL)
        }
    }

    
    override suspend fun beforeFinalize() {
        val ref = session ?: throw MigrationPauseException(REASON_COMMIT_FAILED)
        val receipt = receiptEdit?.takeIf { ref.supportsStatus }?.invoke(PendingVerdict(ref, clock()))
        val transition = pause.committing(owner, ref.sessionId, receipt)
        if (transition != PauseTransition.APPLIED) {
            L.e { "[MigrationPause] event=commit_marker_failed owner=${owner.take(8)} result=${transition.name.lowercase()}" }
            throw MigrationPauseException(REASON_COMMIT_FAILED)
        }
    }

    override fun onEndMarker() {
        endMarkerWritten = true
    }

    override fun onAbortAnswer(answer: String?) {
        abortAnswer = answer
    }

    companion object {
        val QUIESCENCE_TIMEOUT: Duration = 30.seconds

        const val REASON_NO_LOGIN = "migration-pause-no-login"

        
        const val REASON_UNAVAILABLE = "migration-pause-unavailable"
        const val REASON_QUIESCENCE_TIMEOUT = "migration-pause-quiescence-timeout"
        const val REASON_COMMIT_FAILED = "migration-pause-commit-failed"

        
        const val REASON_RESIDUAL = "migration-pause-residual"
    }
}
