package com.difft.android.call.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.difft.android.base.log.lumberjack.L

class NetworkConnectionListener(context: Context, private val onNetworkLost: (() -> Boolean) -> Unit) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            L.d { "[Call] ConnectivityManager.NetworkCallback onAvailable()" }
            onNetworkLost { false }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            L.d { "[Call] ConnectivityManager.NetworkCallback onLost()" }
            onNetworkLost { true }
        }
    }

    fun register() {
        if (Build.VERSION.SDK_INT >= 28) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
