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
