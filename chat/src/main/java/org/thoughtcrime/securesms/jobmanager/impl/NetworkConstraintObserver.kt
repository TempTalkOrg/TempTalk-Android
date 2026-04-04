package org.thoughtcrime.securesms.jobmanager.impl

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.thoughtcrime.securesms.jobmanager.ConstraintObserver

class NetworkConstraintObserver(private val application: Application) : ConstraintObserver {

    override fun register(notifier: ConstraintObserver.Notifier) {
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifier.onConstraintMet(REASON)
            }
        })
    }

    companion object {
        private const val REASON = "NetworkConstraint"
    }
}
