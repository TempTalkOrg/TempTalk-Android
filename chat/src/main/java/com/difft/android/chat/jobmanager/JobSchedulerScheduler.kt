package com.difft.android.chat.jobmanager

import android.annotation.SuppressLint
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.os.Build
import androidx.annotation.Keep
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The id the persisted wake-up for [constraints] is registered under: one system job per
 * constraint-key set, so every submission sharing a set shares a single wake-up.
 *
 * The same derivation decides which wake-up owns which specs — [JobController.pendingWakeUpIds]
 * re-derives it from a spec's stored constraints, so a job only claims the specs that map back
 * to it.
 */
internal fun persistentJobId(constraints: List<Constraint>): Int = constraints
    .mapNotNull { it.getJobSchedulerKeyPart() }
    .sorted()
    .joinToString("-")
    .hashCode()

/**
 * Registers the persisted wake-ups that bring the process back after the system kills it in the
 * background.
 *
 * Registrations and cancels both originate on the job manager's management dispatcher and run on
 * the FIFO [executor], so a registration can never be overtaken by a cancel submitted after it.
 * A registration that dedups against a running wake-up is safe for the same reason the wake-up is
 * kept alive at all: that job is always rescheduled, so it still covers process death, and the
 * in-process runner the wake-up just signalled executes the submitted work itself.
 */
class JobSchedulerScheduler(
    private val application: Application
) : Scheduler {

    /**
     * Serial so that the binder work of this scheduler runs one task at a time, in the order the
     * tasks were submitted.
     */
    private val executor = Dispatchers.Default.limitedParallelism(1).asExecutor()

    /**
     * Whether a persisted job may exist for [cancelAllExcept] to clear. Confined to [executor].
     *
     * Initialised `true` so a persisted job left behind by a previous process life is still
     * cleared on the first drain. Set `true` again after a successful `schedule`, and `false`
     * once an enumeration has cleared everything it found — which lets the common repeat drain
     * skip the `getSystemService` + `allPendingJobs` binder round trip entirely. An enumeration
     * that kept a job leaves the flag armed, since that job still has to be cancellable on a
     * later idle.
     */
    private var armed = true

    override fun schedule(delay: Long, constraints: List<Constraint>) {
        // Binder work stays off the caller's dispatcher.
        executor.execute { scheduleNow(delay, constraints) }
    }

    /**
     * `getPendingJob` -> `schedule` is a check-then-act, and every caller that shares a
     * constraint-key set shares the derived [jobId]. Running on the serial [executor] makes the
     * pair atomic in-process, which is sufficient: only this process registers these ids.
     *
     * The same serialization also preserves program order between [schedule] and [cancelAllExcept]
     * tasks enqueued from the management dispatcher, so a registration submitted before the idle
     * check can never be overtaken by the cancel that check triggers.
     */
    private fun scheduleNow(delay: Long, constraints: List<Constraint>) {
        val jobScheduler = application.getSystemService(JobScheduler::class.java)

        val jobId = persistentJobId(constraints)

        // Both binder calls are guarded: schedule throws IllegalStateException once the per-app
        // persisted-job cap is reached, and this body runs on a raw executor, so an escaping
        // throw would take the process down.
        runCatching {
            val pending = jobScheduler.getPendingJob(jobId) != null

            // The single reason to re-register over a job the framework still lists: an earlier
            // registration may have left a long-latency job pending under this id, so deduping a
            // delay-0 registration whose constraints are unmet would postpone its wake from
            // "constraint met" to that deadline. A job that cannot fire yet costs no extra wake.
            // An EMPTY constraint list keeps the dedup: with nothing to wait for, the in-process
            // runner already executes the work and the pending job only has to cover process death.
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

    /**
     * Cancels only the wake-ups registered against our own [SystemService] — never another
     * component's jobs, such as WorkManager's.
     *
     * A job that is currently bound is cancelled too; the framework ignores the `onStopJob`
     * return value of a job it cancelled, so no reschedule is resurrected by it.
     *
     * Idle transitions repeat, so a drain that cleared everything last time and had nothing
     * registered since returns without touching the binder at all.
     */
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
                // Only a completed enumeration proves what is left, and a job it deliberately kept
                // still has to be cancellable on a later idle; a failure keeps the flag armed so
                // the next drain retries.
                armed = kept
            }.onFailure {
                L.w { "[JobScheduler] cancelAllExcept failed: ${it.stackTraceToString()}" }
            }
        }
    }

    /**
     * The persisted job is a wake-up signal, not an execution container: it pokes the job
     * runner and releases the binding within milliseconds. Staying bound for the whole queue
     * drain is what exposes a background process to `onStopJob` / the 10-minute cap while the
     * OEM has it frozen, which the framework reports as an ANR against us.
     *
     * Every wake therefore asks the framework to keep the job (linear 5-minute backoff), and the
     * idle transition that follows cancels the groups whose storage has drained — see
     * [JobController.cancelIdleWakeUps]. So the system contacts the process only while that group
     * still has work, and no application-side decision races the asynchronous `jobFinished`.
     */
    @SuppressLint("SpecifyJobSchedulerIdRange")
    class SystemService : JobService() {

        /**
         * The run that currently owns each jobId. One jobId exists per constraint-key set (the
         * constraint-less id and the network id are both live today), and the framework binds
         * them to the SAME service instance. A single instance-wide flag would let the first
         * completion swallow the second job's `jobFinished` — leaving it bound — and make its
         * `onStopJob` report "finished", losing the reschedule for that group.
         *
         * The value is the run's own [JobParameters] and every consumer removes conditionally on
         * it. If the management dispatcher wedges past the framework's 10-minute cap the run is
         * stopped and the same id is re-run on this still-bound instance; the earlier run's late
         * completion must not consume the later run's entry and leave that run un-finished.
         */
        private val inFlight = ConcurrentHashMap<Int, JobParameters>()

        override fun onStartJob(params: JobParameters): Boolean {
            // Registered on the framework thread, before the executor hop, so an onStopJob that
            // arrives immediately can never observe a missing entry.
            inFlight[params.jobId] = params
            L.i { "[JobScheduler] onStartJob id=${params.jobId} inFlight=${inFlight.size}" }

            completionScope.launch {
                runCatching { ApplicationDependencies.getJobManager().wakeUpFromSystem() }
                    .onFailure {
                        L.w { "[JobScheduler] wakeUp failed id=${params.jobId}: ${it.stackTraceToString()}" }
                    }
                finishSafely(params, reschedule = true)
            }

            return true
        }

        /** `true` means "not finished, please reschedule" — evaluated per run, not per jobId. */
        override fun onStopJob(params: JobParameters): Boolean {
            val unfinished = inFlight.remove(params.jobId, params)
            // getStopReason is only public from API 31; older releases give no reason at all.
            val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.stopReason.toString()
            } else {
                "unavailable"
            }
            L.w { "[JobScheduler] onStopJob id=${params.jobId} reason=$reason unfinished=$unfinished" }
            return unfinished
        }

        @Keep
        override fun onNetworkChanged(params: JobParameters) {
            // Deliberately does not call super: the default implementation runs work on the
            // main thread. Network changes reach the queue through the wake-up path instead.
            L.i { "[JobScheduler] onNetworkChanged id=${params.jobId}" }
        }

        private fun finishSafely(params: JobParameters, reschedule: Boolean) {
            // Atomic hand-off: exactly one of this path and onStopJob may complete a run, and only
            // while that run still owns the id.
            if (!inFlight.remove(params.jobId, params)) return
            // jobFinished throws if the job was already completed or the service was torn down.
            runCatching { jobFinished(params, reschedule) }
                .onSuccess { L.i { "[JobScheduler] finished id=${params.jobId} rescheduleRequested=$reschedule" } }
                .onFailure { L.w { "[JobScheduler] jobFinished failed id=${params.jobId}: ${it.stackTraceToString()}" } }
        }

        private companion object {
            /**
             * Process lifetime, never cancelled. Every started job ends via `jobFinished` or
             * `onStopJob` before the framework unbinds this service, and a completion that lands
             * after a teardown is already a no-op through the per-run hand-off in [finishSafely]
             * plus the `runCatching` around `jobFinished`. A service-scoped job would instead risk
             * cancelling the coroutine before the wake-up ever reached storage.
             */
            private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
    }

    private companion object {
        /**
         * Backoff the framework waits out before re-running a job we finished with
         * `wantsReschedule = true`; each such finish increments the framework's failure count, so
         * the linear policy spaces the re-runs 5, 10, 15 … minutes apart (framework cap: 5 hours)
         * and the count resets whenever we re-register the id. Linear rather than exponential so a
         * long-stuck queue costs progressively fewer wakes instead of collapsing to none. Five
         * minutes rate-limits the system-to-process IPC into a background process that an
         * aggressive ROM may have frozen.
         *
         * A system stop (`onStopJob` returning `true`) is counted as one third of a failure from
         * API 33 on, so the first two stops re-run without any added delay; below 33 every stop
         * takes the full backoff.
         */
        private const val WAKE_BACKOFF_MS = 300_000L
    }
}
