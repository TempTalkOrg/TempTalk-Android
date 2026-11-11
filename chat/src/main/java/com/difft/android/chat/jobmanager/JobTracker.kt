package com.difft.android.chat.jobmanager

import com.difft.android.chat.util.LRUCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch




class JobTracker {

    private val jobInfos: MutableMap<String, JobInfo> = LRUCache(1000)
    private val jobListeners: MutableList<ListenerInfo> = mutableListOf()
    private val listenerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    




    @Synchronized
    fun addListener(filter: JobFilter, listener: JobListener) {
        jobListeners.add(ListenerInfo(filter, listener))
    }

    


    @Synchronized
    fun removeListener(listener: JobListener) {
        jobListeners.removeAll { it.listener == listener }
    }

    




    @Synchronized
    fun getFirstMatchingJobState(filter: JobFilter): JobState? {
        return jobInfos.values.firstOrNull { filter.matches(it.job) }?.jobState
    }

    


    @Synchronized
    fun onStateChange(job: Job, state: JobState) {
        getOrCreateJobInfo(job).jobState = state

        val matchingListeners = jobListeners.filter { it.filter.matches(job) }
        matchingListeners.forEach { info ->
            listenerScope.launch { info.listener.onStateChanged(job, state) }
        }
    }

    




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
