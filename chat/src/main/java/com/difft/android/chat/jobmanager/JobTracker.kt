package com.difft.android.chat.jobmanager

import com.difft.android.chat.util.LRUCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tracks the state of [Job]s and allows callers to listen to changes.
 */
class JobTracker {

    private val jobInfos: MutableMap<String, JobInfo> = LRUCache(1000)
    private val jobListeners: MutableList<ListenerInfo> = mutableListOf()
    private val listenerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Add a listener to subscribe to job state updates. Listeners will be invoked on an arbitrary
     * background thread. You must eventually call [removeListener] to avoid
     * memory leaks.
     */
    @Synchronized
    fun addListener(filter: JobFilter, listener: JobListener) {
        jobListeners.add(ListenerInfo(filter, listener))
    }

    /**
     * Unsubscribe the provided listener from all job updates.
     */
    @Synchronized
    fun removeListener(listener: JobListener) {
        jobListeners.removeAll { it.listener == listener }
    }

    /**
     * Returns the state of the first Job that matches the provided filter. Note that there will
     * always be races here, and the result you get back may not be valid anymore by the time you
     * get it. Use with caution.
     */
    @Synchronized
    fun getFirstMatchingJobState(filter: JobFilter): JobState? {
        return jobInfos.values.firstOrNull { filter.matches(it.job) }?.jobState
    }

    /**
     * Update the state of a job with the associated ID.
     */
    @Synchronized
    fun onStateChange(job: Job, state: JobState) {
        getOrCreateJobInfo(job).jobState = state

        val matchingListeners = jobListeners.filter { it.filter.matches(job) }
        matchingListeners.forEach { info ->
            listenerScope.launch { info.listener.onStateChanged(job, state) }
        }
    }

    /**
     * Returns whether or not any jobs referenced by the IDs in the provided collection have failed.
     * Keep in mind that this is not perfect -- our data is only kept in memory, and even then only
     * up to a certain limit.
     */
    @Synchronized
    fun haveAnyFailed(jobIds: Collection<String>): Boolean {
        return jobIds.any { jobInfos[it]?.jobState == JobState.FAILURE }
    }

    private fun getOrCreateJobInfo(job: Job): JobInfo = jobInfos.getOrPut(job.id) { JobInfo(job) }

    fun interface JobFilter {
        fun matches(job: Job): Boolean
    }

    fun interface JobListener {
        fun onStateChanged(job: Job, jobState: JobState)
    }

    enum class JobState(val isComplete: Boolean) {
        PENDING(false),
        RUNNING(false),
        SUCCESS(true),
        FAILURE(true),
        IGNORED(true)
    }

    private class ListenerInfo(
        val filter: JobFilter,
        val listener: JobListener
    )

    private class JobInfo(val job: Job) {
        var jobState: JobState? = null
    }
}
