package com.difft.android.chat.jobmanager

import android.annotation.SuppressLint
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.util.WakeLockUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap









internal fun persistentJobId(constraints: List<Constraint>): Int = constraints
    .mapNotNull { it.getJobSchedulerKeyPart() }
    .sorted()
    .joinToString("-")
    .hashCode()











class JobSchedulerScheduler(
    private val application: Application
) : Scheduler {

    



    private val executor = Dispatchers.Default.limitedParallelism(1).asExecutor()

    









    private var armed = true

    override fun schedule(delay: Long, constraints: List<Constraint>) {
        
        executor.execute { scheduleNow(delay, constraints) }
    }

    








    private fun scheduleNow(delay: Long, constraints: List<Constraint>) {
        val jobScheduler = application.getSystemService(JobScheduler::class.java)

        val jobId = persistentJobId(constraints)

        
        
        
        runCatching {
            val pending = jobScheduler.getPendingJob(jobId) != null

            
            
            
            
            
            
            val replace = delay == 0L && constraints.any { !it.isMet() }

            if (pending && !replace) {
                L.i { "[JobScheduler] already pending id=$jobId" }
                return@runCatching
            }
            if (pending) {
                L.i { "[JobScheduler] replace pending id=$jobId reason=constraints-unmet delay=$delay" }
            }
            L.i { "[JobScheduler] enqueue id=$jobId delay=$delay" }

            val jobInfoBuilder = JobInfo.Builder(jobId, ComponentName(application, SystemService::class.java))
                .setMinimumLatency(delay)
                .setPersisted(true)
                .setBackoffCriteria(WAKE_BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR)

            for (constraint in constraints) {
                constraint.applyToJobInfo(jobInfoBuilder)
            }

            jobScheduler.schedule(jobInfoBuilder.build())
            armed = true
        }.onFailure {
            L.w { "[JobScheduler] schedule failed id=$jobId: ${it.stackTraceToString()}" }
        }
    }

    









    override fun cancelAllExcept(keep: Set<Int>) {
        executor.execute {
            if (!armed) return@execute
            runCatching {
                val jobScheduler = application.getSystemService(JobScheduler::class.java)
                var kept = false
                jobScheduler.allPendingJobs
                    .filter { it.service.className == SystemService::class.java.name }
                    .forEach {
                        if (it.id in keep) {
                            kept = true
                            return@forEach
                        }
                        jobScheduler.cancel(it.id)
                        L.i { "[JobScheduler] cancel id=${it.id}" }
                    }
                
                
                
                armed = kept
            }.onFailure {
                L.w { "[JobScheduler] cancelAllExcept failed: ${it.stackTraceToString()}" }
            }
        }
    }

    





    @SuppressLint("SpecifyJobSchedulerIdRange")
    class SystemService : JobService() {

        




        private val inFlight = ConcurrentHashMap<Int, JobParameters>()

        private val mainHandler = Handler(Looper.getMainLooper())

        override fun onStartJob(params: JobParameters): Boolean {
            
            inFlight[params.jobId] = params
            L.i { "[JobScheduler] onStartJob id=${params.jobId} inFlight=${inFlight.size}" }

            
            
            val fallbackToken = Any()
            mainHandler.postAtTime(
                { finishSafely(params, reschedule = true) },
                fallbackToken,
                SystemClock.uptimeMillis() + FINISH_FALLBACK_MS
            )

            val context = applicationContext
            wakeScope.launch {
                
                val wakeLock = runCatching { acquireWakeLock(context) }.getOrNull()
                finishSafely(params, reschedule = true)
                mainHandler.removeCallbacksAndMessages(fallbackToken)
                try {
                    runCatching { ApplicationDependencies.getJobManager().wakeUpFromSystem() }
                        .onFailure {
                            L.w { "[JobScheduler] wakeUp failed id=${params.jobId}: ${it.stackTraceToString()}" }
                        }
                } finally {
                    WakeLockUtil.release(wakeLock, WAKE_LOCK_TAG)
                }
            }

            return true
        }

        




        override fun onStopJob(params: JobParameters): Boolean {
            val unfinished = inFlight.remove(params.jobId, params)
            
            val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.stopReason.toString()
            } else {
                "unavailable"
            }
            L.w { "[JobScheduler] onStopJob id=${params.jobId} reason=$reason unfinished=$unfinished" }
            return true
        }

        @Keep
        override fun onNetworkChanged(params: JobParameters) {
            
            
            L.i { "[JobScheduler] onNetworkChanged id=${params.jobId}" }
        }

        private fun finishSafely(params: JobParameters, reschedule: Boolean) {
            
            if (!inFlight.remove(params.jobId, params)) return
            
            runCatching { jobFinished(params, reschedule) }
                .onSuccess { L.i { "[JobScheduler] finished id=${params.jobId} rescheduleRequested=$reschedule" } }
                .onFailure { L.w { "[JobScheduler] jobFinished failed id=${params.jobId}: ${it.stackTraceToString()}" } }
        }

        internal companion object {
            
            private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            
            private const val FINISH_FALLBACK_MS = 2_000L

            
            private const val WAKE_SIGNAL_TIMEOUT_MS = 30_000L

            private const val WAKE_LOCK_TAG = "JobSchedulerScheduler:wake"

            
            @VisibleForTesting
            @Volatile
            internal var acquireWakeLock: (Context) -> PowerManager.WakeLock? = { context ->
                WakeLockUtil.acquire(context, PowerManager.PARTIAL_WAKE_LOCK, WAKE_SIGNAL_TIMEOUT_MS, WAKE_LOCK_TAG)
            }
        }
    }

    private companion object {
        












        private const val WAKE_BACKOFF_MS = 300_000L
    }
}
