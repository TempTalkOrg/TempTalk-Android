package org.thoughtcrime.securesms.websocket.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.os.Build

class NetworkMonitor(private val context: Context, private val onNetworkAvailable: () -> Unit) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // For API 24 and above
    private val networkCallback: ConnectivityManager.NetworkCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    // Network is available, trigger WebSocket reconnect
                    onNetworkAvailable()
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    // Network lost, WebSocket connection might fail
                }
            }
        } else {
            null
        }

    // For API 21–23: Use BroadcastReceiver
    private val networkReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isNetworkAvailableLegacy(context)) {
                // Network is available, trigger WebSocket reconnect
                onNetworkAvailable()
            }
        }
    }

    // Register network callback or receiver based on SDK version
    fun register() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // For API 24 and above
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } else {
            // For API 21–23
            val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            context.registerReceiver(networkReceiver, intentFilter)
        }
    }

    // Unregister network callback or receiver
    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // For API 24 and above
            connectivityManager.unregisterNetworkCallback(networkCallback!!)
        } else {
            // For API 21–23
            context.unregisterReceiver(networkReceiver)
        }
    }

    // Helper function to check network availability for API 21–23
    private fun isNetworkAvailableLegacy(context: Context?): Boolean {
        val connectivityManager =
            context?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }
}