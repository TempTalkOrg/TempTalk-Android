package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.websocket.api.push.exceptions.NoValidRecipientKeysException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import com.difft.android.chat.jobmanager.impl.BackoffUtil
import java.io.IOException
import java.util.concurrent.TimeUnit

abstract class PushSendJob(
    parameters: Parameters,
    private val messagingGate: MessagingGate,
) : com.difft.android.chat.jobs.BaseJob(parameters) {

    /** Set by [admitSend] when this run was refused; read once [run] returns. */
    @Volatile
    private var refusal: SendRefusal? = null

    /**
     * A refused admission decides the run's result, whatever [onPushSend] returned. A job that
     * does not belong to the signed-in login is refused before [onPushSend] starts, so it does no
     * network work at all, an attachment upload included (#1382 A04).
     */
    final override suspend fun run(): Result {
        refusal = null
        // The refusal path takes the same ticket as a send, so a failure's writes are covered too.
        if (sendLogin() != SendLogin.CURRENT) admitSend()
        val result = if (refusal == null) super.run() else null
        return when (refusal) {
            SendRefusal.DEFERRED -> Result.deferred()
            SendRefusal.OTHER_LOGIN -> Result.failure()
            null -> checkNotNull(result)
        }
    }

    /**
     * Admits this run's send while messaging is open (#1382) and the job belongs to the
     * signed-in login (A04). Call it immediately before the first network send and pass the
     * ticket to the sender. The ticket is closed by the job runner after the run's result is
     * recorded, so it also covers the message-state writes that follow the send, or the failure.
     *
     * Null means the send is refused: return from [onPushSend] at once, without throwing, so no
     * catch maps it to a retry or a failure. When messaging is paused or still loading, or no
     * login is signed in, the run ends as [Result.deferred]: the job is re-queued without
     * consuming an attempt and stays held. When the job was created by another login, or carries
     * no creator login, the run ends as [Result.failure], so [onFailure] marks its message failed.
     */
    protected fun admitSend(): MessagingTicket? {
        val ticket = messagingGate.tryAdmit(AdmissionKind.SEND, getFactoryKey())
        if (ticket == null) {
            refusal = SendRefusal.DEFERRED
            L.i { "[MigrationPause] event=send_deferred jobKey=${getFactoryKey()} jobId=$id attempt=$runAttempt" }
            return null
        }
        closeWhenRunEnds(ticket)
        when (sendLogin()) {
            SendLogin.CURRENT -> return ticket
            SendLogin.NONE -> {
                refusal = SendRefusal.DEFERRED
                L.i { "[MigrationPause] event=send_deferred jobKey=${getFactoryKey()} jobId=$id reason=no_login" }
            }
            SendLogin.OTHER -> {
                refusal = SendRefusal.OTHER_LOGIN
                logSendSessionMismatch()
            }
        }
        return null
    }

    private enum class SendRefusal { DEFERRED, OTHER_LOGIN }

    final override suspend fun onRun() {
        L.d { "[PushSendJob] send attempt=$runAttempt" }
        onPushSend()
        L.d { "[PushSendJob] send completed" }
    }

    override fun onRetry() {
        L.i { "[PushSendJob] retry attempt=$runAttempt" }
    }

    override fun onShouldRetry(exception: Exception): Boolean {
        if (exception is ServerRejectedException) {
            return false
        }
        // issue #970 ②: target confirmed to have no valid keys (group invalid / account
        // deregistered / server confirms empty) = permanent, stop retrying.
        // NoValidRecipientKeysException is intentionally not an IOException subtype, so the
        // trailing `is IOException` branch does not catch it.
        if (exception is NoValidRecipientKeysException) {
            return false
        }
        return exception is IOException
    }

    override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long {
        if (exception is ProofRequiredException) {
            val backoff = exception.retryAfterSeconds
            warn(TAG, "[Proof Required] Retry-After is $backoff seconds.")
            if (backoff >= 0) {
                return TimeUnit.SECONDS.toMillis(backoff)
            }
        } else if (exception is NonSuccessfulResponseCodeException) {
            if (exception.is5xx) {
                return BackoffUtil.exponentialBackoff(pastAttemptCount, 6000)
            }
        }
        return super.getNextRunAttemptBackoff(pastAttemptCount, exception)
    }

    protected abstract suspend fun onPushSend()

    companion object {
        private val TAG = L.tag(PushSendJob::class.java)
    }
}
