package com.difft.android.chat.jobmanager

import android.app.job.JobInfo

interface Constraint {

    fun isMet(): Boolean

    fun getFactoryKey(): String

    fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder)

    




    fun getJobSchedulerKeyPart(): String? = null

    interface Factory<T : Constraint> {
        fun create(): T
    }
}
