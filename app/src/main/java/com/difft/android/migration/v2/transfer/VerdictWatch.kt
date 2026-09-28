package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.p2p.engine.model.StatusResponse
import com.difft.android.p2p.engine.model.TransferTerminal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * What the sender must remember about the session it opened on a receiver: where the receiver is,
 * the session id and the session token `prepare-upload` issued, and what the receiver said it can
 * do. Enough to ask `/status` or `/cancel` on a connection of its own, now or after the upload
 * connection is gone. The token is a short-lived, receiver-local credential and is never logged.
 */
data class SenderSessionRef(
    val ip: String,
    val port: Int,
    val fingerprint: String,
    val sessionId: String,
    val sessionToken: String? = null,
    val caps: List<String> = emptyList(),
) {
    /** The receiver answers `/status` and issued a token to ask with. */
    val supportsStatus: Boolean get() = sessionToken != null && ReceiverCaps.STATUS_V1 in caps
}

/** How the receiver said the session ended, or that it could not be made to say. */
sealed interface Verdict {
    /** Verified, imported and promoted: this device must not offer to start over. */
    data object Succeeded : Verdict

    /** [terminal] is `failed` or `cancelled`: nothing landed, starting over is safe. */
    data class Ended(val terminal: String) : Verdict

    /** No answer that decides anything. The data stays; the user confirms on the new device. */
    data class Unknown(val reason: Reason) : Verdict {
        enum class Reason {
            /** The total wait elapsed without a terminal. */
            TIMED_OUT,

            /** The receiver answered 404: it does not know the session, or does not recognise the token. */
            SESSION_UNKNOWN,

            /** The receiver could not be reached at all. */
            UNREACHABLE,
        }
    }
}

/** The `/status` route decided the upload's outcome instead of its own response. Never triggers a `/cancel`. */
class SendVerdictException(val verdict: Verdict) : Exception("verdict from status: $verdict")

/**
 * The sender's side of the verdict wait: once the body is on the wire, poll the receiver's `/status`
 * on a connection of its own until it names a terminal. Only `progressRevision` and `terminal`
 * decide anything; the stage is for display and is never read here.
 *
 * Stall and total timeouts are measured against a clock rather than a poll count, so a process
 * suspended between polls does not extend them. A stall slows the polling — never past half the
 * stall threshold, or one poll could step over it — but sends no `/cancel`, because the receiver may
 * be about to finish. Reaching the total limit ends the wait as [Verdict.Unknown], not a failure.
 */
class VerdictWatch(
    private val stallMs: Long = STALL_MS,
    private val totalMs: Long = TOTAL_MS,
    private val pollMs: Long = POLL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Polls [status] until a verdict. [status] returns null when the receiver answered 404 and
     * throws when it could not be reached; [onStall] is told when the receiver stops making progress
     * and again when it resumes.
     */
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
        /** No movement of `progressRevision` for this long reads as a stall. */
        const val STALL_MS = 60_000L

        /** From the last body byte to giving up on a verdict. */
        const val TOTAL_MS = 15L * 60 * 1000

        /** Steady-state polling interval. */
        const val POLL_MS = 10_000L

        fun verdictOf(terminal: String): Verdict =
            if (terminal == TransferTerminal.SUCCEEDED) Verdict.Succeeded else Verdict.Ended(terminal)
    }
}
