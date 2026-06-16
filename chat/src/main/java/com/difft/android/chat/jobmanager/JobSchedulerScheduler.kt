package com.difft.android.chat.jobmanager

import android.annotation.SuppressLint
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.base.concurrent.AppExecutors

class JobSchedulerScheduler(
    private val application: Application
) : Scheduler {

    override fun schedule(delay: Long, constraints: List<Constraint>) {
        AppExecutors.Default.execute {
            val jobScheduler = application.getSystemService(JobScheduler::class.java)

            val constraintNames = constraints
                .mapNotNull { it.getJobSchedulerKeyPart() }
                .sorted()
                .joinToString("-")

            val jobId = constraintNames.hashCode()

            if (jobScheduler.getPendingJob(jobId) != null) {
                return@execute
            }
            L.i { "JobScheduler enqueue of $constraintNames ($jobId)" }

            val jobInfoBuilder = JobInfo.Builder(jobId, ComponentName(application, SystemService::class.java))
                .setMinimumLatency(delay)
                .setPersisted(true)

            for (constraint in constraints) {
                constraint.applyToJobInfo(jobInfoBuilder)
            }

            jobScheduler.schedule(jobInfoBuilder.build())
        }
    }

    @SuppressLint("SpecifyJobSchedulerIdRange")
    class SystemService : JobService() {

        override fun onStartJob(params: JobParameters): Boolean {
            L.i { "Waking due to job: ${params.jobId}" }

            // 将所有操作移到后台线程，避免在主线程上阻塞
            // 这样可以防止 onNetworkChanged 等系统回调在主线程上触发ANR
            AppExecutors.Default.execute {
                try {
                    val jobManager = ApplicationDependencies.getJobManager()

                    jobManager.addOnEmptyQueueListener(object : JobManager.EmptyQueueListener {
                        override fun onQueueEmpty() {
                            jobManager.removeOnEmptyQueueListener(this)
                            jobFinished(params, false)
                        }
                    })

                    jobManager.wakeUp()
                } catch (e: Exception) {
                    L.e { "Error waking job manager: ${e.message}" }
                    jobFinished(params, false)
                }
            }

            return true
        }

        override fun onStopJob(params: JobParameters): Boolean = false

        override fun onNetworkChanged(params: JobParameters) {
            // 重写此方法并立即返回，避免默认实现在主线程上执行
            // 网络变化会由 onStartJob 在后台线程中处理
            L.d { "Network changed for job: ${params.jobId}" }
        }
    }
}
