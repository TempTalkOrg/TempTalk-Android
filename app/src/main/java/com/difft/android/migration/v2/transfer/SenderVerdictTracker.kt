package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationError
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.p2p.engine.model.TransferTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The sender role's memory of its receiver session beyond the upload connection, and the decisions
 * that follow from it: what to persist once the body is on the wire, what a verdict read from
 * `/status` makes of the transfer, and whether a user's cancel can still be honoured or must first
 * be put to the receiver. Owned by the transfer service; holds no Android types.
 */
internal class SenderVerdictTracker(
    private val pendingStore: PendingVerdictStore,
    private val scope: CoroutineScope,
    private val cancelAskTimeoutMs: Long = CANCEL_ASK_TIMEOUT_MS,
    /** `POST /cancel` to the receiver; answers with a [CancelState], or null when it gave none. */
    private val askReceiver: suspend (SenderSessionRef) -> String? = { s ->
        SenderHttpClient(s.ip, s.port, s.fingerprint).cancel(s.sessionId, s.sessionToken)
    },
) {
    /** The session the receiver opened for the current send; null before `prepare-upload` and between sends. */
    @Volatile private var session: SenderSessionRef? = null

    fun reset() {
        session = null
    }

    fun onPrepared(ref: SenderSessionRef) {
        session = ref
    }

    /** The last body byte left: from here the outcome must survive this process, so it goes to disk. */
    fun onBodySent() {
        val current = session?.takeIf { it.supportsStatus } ?: return
        scope.launch { pendingStore.save(PendingVerdict(current, System.currentTimeMillis())) }
    }

    /** The outcome is known for certain — confirmed, refused, failed or cancelled — so nothing is pending. */
    suspend fun settled() = pendingStore.clear()

    /** What a verdict from `/status` makes the transfer. Only an unknown one leaves the pending record in place. */
    suspend fun stateFor(verdict: Verdict): TransferState = when (verdict) {
        Verdict.Succeeded -> {
            pendingStore.clear()
            TransferState.Sent
        }
        is Verdict.Ended -> {
            L.w { "[MigrationV2][P2P][FGS] receiver reported terminal=${verdict.terminal}" }
            pendingStore.clear()
            TransferState.Failed(reasonFor(verdict))
        }
        is Verdict.Unknown -> {
            L.w { "[MigrationV2][P2P][FGS] verdict unknown reason=${verdict.reason}" }
            TransferState.Unknown
        }
    }

    /**
     * Whether a user's cancel must be put to the receiver before this side acts on it: only while
     * the body is in and the verdict outstanding, and only with a receiver that can answer.
     */
    fun mustAskReceiver(current: TransferState): Boolean = session?.supportsStatus == true && current.isAwaitingVerdict()

    enum class CancelDecision {
        /** Past the receiver's commit point: the import goes on, and so does the wait. */
        KEEP_WAITING,

        /** The receiver stopped or rolled back: this side is cancelled. */
        CANCELLED,

        /** No usable answer: the receiver may have finished, failed or vanished. The data stays. */
        UNKNOWN,
    }

    /** Asks the receiver what a cancel does to the data now, and says what this side should become. */
    suspend fun askCancel(): CancelDecision {
        val current = session ?: return CancelDecision.UNKNOWN
        val answer = withTimeoutOrNull(cancelAskTimeoutMs) { askReceiver(current) }
        val decision = when (answer) {
            CancelState.TOO_LATE -> CancelDecision.KEEP_WAITING
            CancelState.STOPPED, CancelState.ROLLED_BACK -> CancelDecision.CANCELLED
            else -> CancelDecision.UNKNOWN
        }
        L.i { "[MigrationV2][P2P][FGS] cancel asked: receiver=${answer ?: "no reply"} decision=$decision" }
        return decision
    }

    companion object {
        /** How long a cancel waits for the receiver to say what it did before the outcome is called unknown. */
        const val CANCEL_ASK_TIMEOUT_MS = 5_000L

        /** The receiver reported its import as failed; the reason is on its screen, not on the wire. */
        const val REASON_PEER_FAILED = "peer-failed"

        /** A receiver-reported end, in the vocabulary `classifyFailure` reads. */
        fun reasonFor(verdict: Verdict.Ended): String =
            if (verdict.terminal == TransferTerminal.CANCELLED) MigrationError.SourceUnavailable.code else REASON_PEER_FAILED
    }
}
