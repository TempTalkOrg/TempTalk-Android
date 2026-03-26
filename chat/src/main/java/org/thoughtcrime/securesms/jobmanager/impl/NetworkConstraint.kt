package org.thoughtcrime.securesms.jobmanager.impl

import android.app.Application
import android.app.job.JobInfo
import android.content.Context
import androidx.annotation.RequiresApi
import com.difft.android.base.utils.NetworkUtils
import org.thoughtcrime.securesms.jobmanager.Constraint

class NetworkConstraint private constructor(private val application: Application) : Constraint {
    override fun isMet(): Boolean {
        return isNetworkAvailable(application)
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    @RequiresApi(26)
    override fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder) {
        jobInfoBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
    }

    override fun getJobSchedulerKeyPart(): String? {
        return "NETWORK"
    }

    class Factory(private val application: Application) : Constraint.Factory<NetworkConstraint?> {
        override fun create(): NetworkConstraint {
            return NetworkConstraint(application)
        }
    }

    companion object {
        const val KEY: String = "NetworkConstraint"

        @JvmStatic
        fun isNetworkAvailable(context: Context): Boolean {
            return NetworkUtils.isNetworkAvailable(context)
        }
    }
}
