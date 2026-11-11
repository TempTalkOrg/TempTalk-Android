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




class ApplicationDependencyProvider(
    private val context: Application
) : ApplicationDependencies.Provider {

    



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
            
            
            .setDataSerializer(JsonDataSerializer())
            .setJobFactories(JobManagerFactories.getJobFactories(context))
            .setConstraintFactories(JobManagerFactories.getConstraintFactories(context))
            .setConstraintObservers(JobManagerFactories.getConstraintObservers(context))
            .setJobStorage(FastJobStorage(wcdbJobStorage))
            .addReservedJobRunner(FactoryJobPredicate(DownloadAttachmentJob.KEY))
            
            .setExecutionHold(entryPoint.messagingPauseJobHold())
            
            
            .setLoginTag(entryPoint.messagingPauseJobHold())
            .setStartupTask(entryPoint.untaggedSendJobSweep())
            .build()
        return JobManager(context, config)
    }

    override fun provideExoPlayerPool(): SimpleExoPlayerPool {
        return SimpleExoPlayerPool(context)
    }
}
