package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.p2p.engine.model.StatusResponse
import com.difft.android.p2p.engine.model.TransferTerminal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay







data class SenderSessionRef(
    val ip: String,
    val port: Int,
    val fingerprint: String,
    val sessionId: String,
    val sessionToken: String? = null,
    val caps: List<String> = emptyList(),
) {
    
    val supportsStatus: Boolean get() = sessionToken != null && ReceiverCaps.STATUS_V1 in caps
}


sealed interface Verdict {
    
    data object Succeeded : Verdict

    
    data class Ended(val terminal: String) : Verdict

    
    data class Unknown(val reason: Reason) : Verdict {
        enum class Reason {
            
            TIMED_OUT,

            
            SESSION_UNKNOWN,

            
            UNREACHABLE,

            
            UNRECOGNIZED_TERMINAL,
        }
    }
}


class SendVerdictException(val verdict: Verdict) : Exception("verdict from status: $verdict")











class VerdictWatch(
    private val stallMs: Long = STALL_MS,
    private val totalMs: Long = TOTAL_MS,
    private val pollMs: Long = POLL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    




    suspend fun run(
        bodySentAt: Long,
        status: suspend () -> StatusResponse?,
        onStall: (stalled: Boolean) -> Unit = {},
    ): Verdict {
        var lastRevision: Long? = null
        var lastChangeAt = bodySentAt
        var stalled = false
        var interval = pollMs
        val maxInterval = stallMs / 2
        while (true) {
            delay(interval)
            val now = clock()
            val answer = try {
                Answer.Received(status())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[MigrationV2][P2P][Tx] status probe failed: ${e.message}" }
                Answer.Unreachable
            }
            if (answer is Answer.Received) {
                val response = answer.response ?: return Verdict.Unknown(Verdict.Unknown.Reason.SESSION_UNKNOWN)
                response.terminal?.let { return verdictOf(it) }
                if (response.progressRevision != lastRevision) {
                    lastRevision = response.progressRevision
                    lastChangeAt = now
                    if (stalled) {
                        L.i { "[MigrationV2][P2P][Tx] receiver progressing again stage=${response.stage}" }
                        stalled = false
                        interval = pollMs
                        onStall(false)
                    }
                }
            }
            if (now - bodySentAt >= totalMs) {
                L.w { "[MigrationV2][P2P][Tx] no verdict within totalMs=$totalMs — result unknown" }
                return Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT)
            }
            if (!stalled && now - lastChangeAt >= stallMs) {
                L.w { "[MigrationV2][P2P][Tx] receiver made no progress for stallMs=$stallMs" }
                stalled = true
                onStall(true)
            }
            if (stalled) interval = minOf(interval * 2, maxInterval)
        }
    }

    private sealed interface Answer {
        data class Received(val response: StatusResponse?) : Answer
        data object Unreachable : Answer
    }

    companion object {
        
        const val STALL_MS = 60_000L

        
        const val TOTAL_MS = 15L * 60 * 1000

        
        const val POLL_MS = 10_000L

        




        fun verdictOf(terminal: String): Verdict = when (terminal) {
            TransferTerminal.SUCCEEDED -> Verdict.Succeeded
            TransferTerminal.FAILED, TransferTerminal.CANCELLED -> Verdict.Ended(terminal)
            else -> {
                L.w { "[MigrationV2][P2P][Tx] event=verdict_terminal_unrecognized" }
                Verdict.Unknown(Verdict.Unknown.Reason.UNRECOGNIZED_TERMINAL)
            }
        }
    }
}
