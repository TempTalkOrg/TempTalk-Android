package com.difft.android.chat.dependencies

import android.app.Application
import com.difft.android.chat.jobmanager.JobManager
import com.difft.android.chat.jobmanager.impl.FactoryJobPredicate
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobs.DownloadAttachmentJob
import com.difft.android.chat.jobs.FastJobStorage
import com.difft.android.chat.jobs.JobManagerFactories
import com.difft.android.chat.jobs.MessagingPauseJobHold
import com.difft.android.chat.jobs.UntaggedSendJobSweep
import com.difft.android.chat.jobs.WcdbJobStorage
import com.difft.android.chat.video.exo.SimpleExoPlayerPool
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Implementation of [ApplicationDependencies.Provider] that provides real app dependencies.
 */
class ApplicationDependencyProvider(
    private val context: Application
) : ApplicationDependencies.Provider {

    /**
     * Hilt EntryPoint used to obtain the [WcdbJobStorage] singleton from a
     * non-Hilt construction site. [ApplicationDependencyProvider] is built by
     * hand from `TempTalkApplication.onCreate` before Hilt injection reaches
     * this class, so we bridge via `EntryPointAccessors` — same pattern already
     * used for `MessageStore` / `EnvironmentHelper` elsewhere in the codebase
     * (design §4.2.3, D16).
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DepsEntryPoint {
        fun wcdbJobStorage(): WcdbJobStorage
        fun messagingPauseJobHold(): MessagingPauseJobHold
        fun untaggedSendJobSweep(): UntaggedSendJobSweep
    }

    override fun provideJobManager(): JobManager {
        val entryPoint = EntryPointAccessors.fromApplication(context, DepsEntryPoint::class.java)
        val wcdbJobStorage = entryPoint.wcdbJobStorage()
        val config = JobManager.Configuration.Builder()
            // NOTE: ChatHiltProvidesModule.provideJobDataSerializer() provides a Hilt-bound twin
            // of this serializer for ReactionSendCoordinator. Keep in sync if this line changes.
            .setDataSerializer(JsonDataSerializer())
            .setJobFactories(JobManagerFactories.getJobFactories(context))
            .setConstraintFactories(JobManagerFactories.getConstraintFactories(context))
            .setConstraintObservers(JobManagerFactories.getConstraintObservers(context))
            .setJobStorage(FastJobStorage(wcdbJobStorage))
            .addReservedJobRunner(FactoryJobPredicate(DownloadAttachmentJob.KEY))
            // Sends stay queued while messaging is paused for a migration (#1382).
            .setExecutionHold(entryPoint.messagingPauseJobHold())
            // Every job belongs to the login that added it; a send only goes out for that login,
            // and send jobs stored untagged are failed once before anything runs (#1382 A04).
            .setLoginTag(entryPoint.messagingPauseJobHold())
            .setStartupTask(entryPoint.untaggedSendJobSweep())
            .build()
        return JobManager(context, config)
    }

    override fun provideExoPlayerPool(): SimpleExoPlayerPool {
        return SimpleExoPlayerPool(context)
    }
}
