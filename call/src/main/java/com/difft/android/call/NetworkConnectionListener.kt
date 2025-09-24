package com.difft.android.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.NetUtil

class NetworkConnectionListener(private val context: Context, private val onNetworkLost: (() -> Boolean) -> Unit) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


    private val networkChangedCallback: ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
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

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            L.d { "[Call] BroadcastReceiver onReceive()." }
            onNetworkLost { !NetUtil.checkNet(context) }
        }
    }

    fun register() {
        if (Build.VERSION.SDK_INT >= 28) {
            connectivityManager.registerDefaultNetworkCallback(networkChangedCallback)
        } else {
            context.registerReceiver(connectionReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= 28) {
            connectivityManager.unregisterNetworkCallback(networkChangedCallback)
        } else {
            context.unregisterReceiver(connectionReceiver)
        }
    }
}