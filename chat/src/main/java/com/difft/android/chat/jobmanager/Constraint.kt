package com.difft.android.chat.jobmanager

import android.app.job.JobInfo
import androidx.annotation.RequiresApi

interface Constraint {

    fun isMet(): Boolean

    fun getFactoryKey(): String

    @RequiresApi(26)
    fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder)

    /**
     * If you do something in [applyToJobInfo] you should return something here.
     *
     * It is sorted and concatenated with other constraints key parts to form a unique job id.
     */
    fun getJobSchedulerKeyPart(): String? = null

    interface Factory<T : Constraint> {
        fun create(): T
    }
}
