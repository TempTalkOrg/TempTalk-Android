package com.difft.android.setting.notificationtest

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.notification.NotificationTestRun
import com.difft.android.base.notification.NotificationTestRuns
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class NotificationTestLifetime internal constructor(
    private val repository: NotificationTestRepository,
    private val poster: NotificationTestPoster,
    private val runs: NotificationTestRuns,
    private val applicationScope: CoroutineScope,
) {
    @Inject
    constructor(repository: NotificationTestRepository, poster: NotificationTestPoster, runs: NotificationTestRuns) :
        this(repository, poster, runs, appScope)

    fun cancelAndJoin(job: Job?) {
        job?.cancel()
        applicationScope.launch { job?.join() }
    }

    suspend fun finish(run: NotificationTestRun, retainCallTicket: Boolean) =
        withContext(applicationScope.coroutineContext + NonCancellable) {
            try {
                poster.cancel(run.runId, run.kind)
                if (run.kind == NotificationTestKind.CALL && !retainCallTicket) repository.cancelCall(run.runId)
                true
            } catch (e: Exception) {
                L.w { "[NotificationTest] cleanup_failed kind=${run.kind.logName} error=${e.javaClass.simpleName}" }
                false
            } finally {
                runs.release(run)
            }
        }
}
