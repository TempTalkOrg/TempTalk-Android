package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.HeldSendJobs
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.JobExecutionHold
import com.difft.android.chat.jobmanager.JobLoginTag
import com.difft.android.chat.jobmanager.JobManager
import com.difft.android.chat.jobmanager.persistence.JobSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parks every job that sends to the server while messaging is not open, i.e. paused for a
 * phone-to-phone migration or still loading, and while no login is signed in (#1382). Held jobs and
 * their local rows stay in place and run once messaging opens for the same login. A job that was
 * picked before the pause and reaches its send after it is caught by the job's own admission
 * ([PushSendJob.admitSend]) and deferred.
 *
 * Also the [JobLoginTag] every job is stamped with: the hash of the signed-in credential (A04),
 * the same hash the pause's login scope uses. A send job only sends for the login that created
 * it ([SendLogin]); a job of another login, or an untagged one, fails and its message shows as
 * failed. Untagged jobs are those stored before this version, or added while signed out:
 * [UntaggedSendJobSweep] fails the former once at the first start, so messages still unsent at the
 * upgrade show as failed and are resent by hand. A job added before the login loaded is tagged
 * with the login read at that load ([CredentialChangeSignal.firstLoad]).
 */
@Singleton
class MessagingPauseJobHold internal constructor(
    private val messagingGate: MessagingGate,
    private val currentLogin: StateFlow<String?>,
    private val firstLoad: Deferred<String?> = CompletableDeferred(currentLogin.value),
) : JobExecutionHold, JobLoginTag {

    @Inject
    constructor(messagingGate: MessagingGate, credentialSignal: CredentialChangeSignal) :
        this(messagingGate, credentialSignal.credentialHash, credentialSignal.firstLoad)

    override fun current(): String? = currentLogin.value

    override fun loaded(): Boolean = firstLoad.isCompleted

    override suspend fun awaitFirstLoad(): String? = firstLoad.await()

    override fun holds(jobSpec: JobSpec): Boolean =
        jobSpec.factoryKey in SEND_JOB_KEYS && (!messagingGate.isOpen || currentLogin.value == null)

    override val released: Flow<Unit> = merge(
        messagingGate.onChanged.filter { it == MessagingPauseState.Open }.map { },
        // A login signing in, the first one this collector sees included: jobs held for no login may
        // have been scanned before it subscribed, so a spare wake is cheaper than a missed one.
        currentLogin.map { it != null }.distinctUntilChanged().filter { it }.map { },
    )

    /**
     * Drops the held sends of a login that ended; see [HeldSendJobs]. Cleanup only: a send that
     * outlives it (the drop timed out, or a job was added meanwhile) is still refused at its send,
     * because its creator login is no longer the signed-in one.
     */
    @Singleton
    class Drop internal constructor(private val jobManager: () -> JobManager) : HeldSendJobs {

        @Inject
        constructor() : this(ApplicationDependencies::getJobManager)

        /** Bounded and never throws: a logout or login check must not hang or fail on it. */
        override suspend fun dropAll(reason: String, keepLogin: String?) {
            try {
                val dropped = withTimeoutOrNull(DROP_TIMEOUT_MS) {
                    jobManager().cancelMatching { spec, creatorLogin -> spec.factoryKey in SEND_JOB_KEYS && !kept(creatorLogin, keepLogin) }
                }
                if (dropped == null) {
                    L.w { "[MigrationPause] event=held_sends_drop_timeout reason=$reason" }
                } else {
                    L.i { "[MigrationPause] event=held_sends_dropped reason=$reason count=$dropped keptLogin=${keepLogin != null}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                L.e { "[MigrationPause] event=held_sends_drop_failed reason=$reason ${e.stackTraceToString()}" }
            }
        }

        private companion object {
            const val DROP_TIMEOUT_MS = 5_000L

            /** Only the kept login's own jobs survive; untagged ones never do. */
            fun kept(creatorLogin: String?, keepLogin: String?): Boolean = keepLogin != null && creatorLogin == keepLogin
        }
    }

    companion object {
        /** Every registered job that writes to the server; [DownloadAttachmentJob] only reads. */
        val SEND_JOB_KEYS: Set<String> = setOf(
            PushTextSendJob.KEY,
            PushReactionSendJob.KEY,
            PushReadReceiptSendJob.KEY,
            PushMarkAsUnreadSendJob.KEY,
            PushGroupKeySendJob.KEY,
            PushForwardNoticeSendJob.KEY,
            PushActivityNoticeSendJob.KEY,
            PushScreenShotSendJob.KEY,
            SettingSyncJob.KEY,
        )
    }
}
