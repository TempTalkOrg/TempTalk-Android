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

/** A device-replacement export could not pause messaging, or lost its pause; [message] is the wire-style reason. */
class MigrationPauseException(reason: String) : IllegalStateException(reason)

/** Starts one [SenderPauseSession] per device-replacement export (#1382). */
@Singleton
class SenderMessagePause @Inject constructor(
    private val pause: MigrationMessagePause,
    private val gate: MessagingGate,
    private val loginScope: MigrationPauseLoginScope,
    private val retryDrain: MigrationRetryQueueDrain,
    private val pendingStore: PendingVerdictStore,
) {
    /**
     * @param persistReceipt false for a credential handoff, whose unknown outcome the handoff
     *   reconciler decides rather than `/status`.
     */
    fun newSession(owner: String, persistReceipt: Boolean = true): SenderPauseSession = SenderPauseSession(
        owner = owner,
        pause = pause,
        gate = gate,
        scope = loginScope::current,
        drainRetryQueues = retryDrain::drainAsOwner,
        receiptEdit = if (persistReceipt) pendingStore::saveEdit else null,
    )
}

/**
 * The old phone's message pause for one device-replacement export (#1382), owned by [owner].
 *
 * [enter] runs right before the snapshot: the pause is made durable, every send and receive
 * admitted before it is awaited, and the local retry queues are drained into the database, deferred
 * work included. The commit marker ([beforeFinalize]) is written right before the container's end
 * marker, in the same edit as the receipt the outcome can later be asked with, so a crash on either
 * side of it leaves a state cold start can resolve.
 */
class SenderPauseSession internal constructor(
    val owner: String,
    private val pause: MigrationMessagePause,
    private val gate: MessagingGate,
    private val scope: () -> MigrationPauseLoginScope.Scope?,
    private val drainRetryQueues: suspend (String) -> RetryQueueResidual,
    /** Builds the edit that stores the receipt; null when this export keeps none. */
    private val receiptEdit: ((PendingVerdict) -> PendingVerdictEdit)? = null,
    private val quiescenceTimeout: Duration = QUIESCENCE_TIMEOUT,
    private val clock: () -> Long = System::currentTimeMillis,
) : SendCommitHooks {

    /** The receiver's session for this export, known from `prepare-upload` on. */
    @Volatile
    var session: SenderSessionRef? = null
        private set

    /** The receiver's answer to the pipeline's own `/cancel`; null when none was sent or answered. */
    @Volatile
    var abortAnswer: String? = null
        private set

    /** Whether [enter] stored this export's record; a refused export has no pause to settle. */
    @Volatile
    var began: Boolean = false
        private set

    /** Set right before the container's end marker is handed to the wire; until then no receiver can commit. */
    @Volatile
    var endMarkerWritten: Boolean = false
        private set

    /** True while this export's own record awaits a decision: no definite terminal may be shown. */
    val awaitsDecision: Boolean
        get() = pause.record?.let { it.owner == owner && it.phase == MigrationPausePhase.AWAITING_DECISION } == true

    fun onSessionPrepared(ref: SenderSessionRef) {
        session = ref
    }

    /**
     * Pause, wait for admitted work, drain the retry queues, then wait again for the work the drain
     * handed to deferred consumers (retained tickets), all within one [quiescenceTimeout]. Throws
     * [MigrationPauseException] when the export must not go on, any stored record included.
     *
     * A `failed_message` row still there after the drain (or an unknown count) stops the export
     * before the snapshot with [REASON_RESIDUAL]: it was consumed from the server and would be lost
     * on the new device. Pending envelopes are not residual; they travel in the container.
     */
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

    /** The commit marker and, for a receiver that answers `/status`, the receipt, in one write. */
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

        /** A pause record is stored (an earlier export, resolved or not, or a corrupt one), or the write failed. */
        const val REASON_UNAVAILABLE = "migration-pause-unavailable"
        const val REASON_QUIESCENCE_TIMEOUT = "migration-pause-quiescence-timeout"
        const val REASON_COMMIT_FAILED = "migration-pause-commit-failed"

        /** `failed_message` rows remain after the pre-snapshot drain, or their count is unknown. */
        const val REASON_RESIDUAL = "migration-pause-residual"
    }
}
