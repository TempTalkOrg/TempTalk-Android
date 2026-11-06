package com.difft.android.chat.jobs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.chat.jobmanager.JobStartupTask
import com.difft.android.chat.jobmanager.StoredJobs
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fails, once, every stored send job that carries no creator login (#1382 A04): jobs stored by a
 * version that did not tag them. Their login cannot be told apart, so tagging them now could send
 * an earlier login's job under the current one; failing them marks their messages failed, and the
 * user resends by hand. Messages still unsent at the upgrade therefore show as failed.
 *
 * Runs before the job manager dispatches anything. The completion flag is written only after the
 * sweep, so a sweep cut short reruns at the next start; failing an already failed job is a no-op.
 * The send-time check ([SendLogin]) refuses an untagged job on its own, whether or not this ran.
 */
@Singleton
class UntaggedSendJobSweep @Inject constructor(
    @param:AppStateDataStore private val appState: DataStore<Preferences>,
) : JobStartupTask {

    override suspend fun run(jobs: StoredJobs) {
        val swept = appState.data.first()[AppStateKeys.UNTAGGED_SEND_JOBS_SWEPT]
            ?: AppStateDefaults.UNTAGGED_SEND_JOBS_SWEPT
        if (swept) return
        val failed = jobs.failMatching { spec, creatorLogin ->
            spec.factoryKey in MessagingPauseJobHold.SEND_JOB_KEYS && creatorLogin == null
        }
        appState.edit { it[AppStateKeys.UNTAGGED_SEND_JOBS_SWEPT] = true }
        L.i { "[MigrationPause] event=untagged_sends_failed count=$failed" }
    }
}
