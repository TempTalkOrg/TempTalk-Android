package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationError
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.p2p.engine.model.TransferTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull







internal class SenderVerdictTracker(
    private val pendingStore: PendingVerdictStore,
    private val scope: CoroutineScope,
    private val cancelAskTimeoutMs: Long = CANCEL_ASK_TIMEOUT_MS,
    
    private val askReceiver: suspend (SenderSessionRef) -> String? = { s ->
        SenderHttpClient(s.ip, s.port, s.fingerprint).cancel(s.sessionId, s.sessionToken)
    },
) {
    
    @Volatile private var session: SenderSessionRef? = null

    fun reset() {
        session = null
    }

    fun onPrepared(ref: SenderSessionRef) {
        session = ref
    }

    





    fun onBodySent(persist: Boolean = true) {
        if (!persist) return
        val current = session?.takeIf { it.supportsStatus } ?: return
        scope.launch { pendingStore.save(PendingVerdict(current, System.currentTimeMillis())) }
    }

    



    fun stateFor(verdict: Verdict): TransferState = when (verdict) {
        Verdict.Succeeded -> TransferState.Sent
        is Verdict.Ended -> {
            L.w { "[MigrationV2][P2P][FGS] receiver reported terminal=${verdict.terminal}" }
            TransferState.Failed(reasonFor(verdict))
        }
        is Verdict.Unknown -> {
            L.w { "[MigrationV2][P2P][FGS] verdict unknown reason=${verdict.reason}" }
            TransferState.Unknown
        }
    }

    



    fun mustAskReceiver(current: TransferState): Boolean = session?.supportsStatus == true && current.isAwaitingVerdict()

    enum class CancelDecision {
        
        KEEP_WAITING,

        
        CANCELLED,

        
        UNKNOWN,
    }

    
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
        
        const val CANCEL_ASK_TIMEOUT_MS = 5_000L

        
        const val REASON_PEER_FAILED = "peer-failed"

        
        fun reasonFor(verdict: Verdict.Ended): String =
            if (verdict.terminal == TransferTerminal.CANCELLED) MigrationError.SourceUnavailable.code else REASON_PEER_FAILED
    }
}
