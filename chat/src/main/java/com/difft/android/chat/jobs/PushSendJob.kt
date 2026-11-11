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

    
    @Volatile
    private var refusal: SendRefusal? = null

    




    final override suspend fun run(): Result {
        refusal = null
        
        if (sendLogin() != SendLogin.CURRENT) admitSend()
        val result = if (refusal == null) super.run() else null
        return when (refusal) {
            SendRefusal.DEFERRED -> Result.deferred()
            SendRefusal.OTHER_LOGIN -> Result.failure()
            null -> checkNotNull(result)
        }
    }

    











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
