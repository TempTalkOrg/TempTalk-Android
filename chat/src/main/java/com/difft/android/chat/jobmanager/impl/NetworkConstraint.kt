package com.difft.android.chat.jobmanager.impl

import android.app.Application
import android.app.job.JobInfo
import android.content.Context
import com.difft.android.base.utils.NetworkUtils
import com.difft.android.chat.jobmanager.Constraint

class NetworkConstraint private constructor(private val application: Application) : Constraint {

    override fun isMet(): Boolean = isNetworkAvailable(application)

    override fun getFactoryKey(): String = KEY

    override fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder) {
        jobInfoBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
    }

    override fun getJobSchedulerKeyPart(): String = "NETWORK"

    class Factory(private val application: Application) : Constraint.Factory<NetworkConstraint> {
        override fun create(): NetworkConstraint = NetworkConstraint(application)
    }

    companion object {
        const val KEY: String = "NetworkConstraint"

        fun isNetworkAvailable(context: Context): Boolean = NetworkUtils.isNetworkAvailable(context)
    }
}
