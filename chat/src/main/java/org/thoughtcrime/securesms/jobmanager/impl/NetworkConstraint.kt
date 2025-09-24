package org.thoughtcrime.securesms.jobmanager.impl

import android.app.Application
import android.app.job.JobInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import org.thoughtcrime.securesms.jobmanager.Constraint

class NetworkConstraint private constructor(private val application: Application) : Constraint {
    override fun isMet(): Boolean {
        return isMet(application)
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

        fun isMet(context: Context): Boolean {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetworkInfo = connectivityManager.activeNetworkInfo

            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }

        fun isNetworkAvailable(context: Context): Boolean {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

                return when {
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                    else -> false
                }
            } else {
                val networkInfo = connectivityManager.activeNetworkInfo
                return networkInfo != null && networkInfo.isConnected
            }
        }
    }
}
