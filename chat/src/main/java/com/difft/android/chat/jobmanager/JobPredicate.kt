package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.JobSpec

fun interface JobPredicate {
    fun shouldRun(jobSpec: JobSpec): Boolean

    companion object {
        @JvmField
        val NONE: JobPredicate = JobPredicate { true }
    }
}
