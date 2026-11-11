package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.JobSpec







fun interface JobStartupTask {

    suspend fun run(jobs: StoredJobs)

    companion object {
        val NONE: JobStartupTask = JobStartupTask { }
    }
}


fun interface StoredJobs {

    




    fun failMatching(predicate: (spec: JobSpec, creatorLogin: String?) -> Boolean): Int
}
