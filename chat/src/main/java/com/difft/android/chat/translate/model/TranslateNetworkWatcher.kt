package com.difft.android.chat.translate.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import java.util.concurrent.atomic.AtomicBoolean










internal class TranslateNetworkWatcher(
    context: Context,
    private val onNetworkUsable: (isVpn: Boolean) -> Unit,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val validated = AtomicBoolean(false)

    @Volatile
    private var currentNetwork: Network? = null

    
    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            currentNetwork = network
            validated.set(false)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, capabilities)
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            if (!validated.compareAndSet(false, true)) return
            val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            L.i { "[TranslateModel] network usable isVpn=$isVpn" }
            onNetworkUsable(isVpn)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            if (network == currentNetwork) {
                currentNetwork = null
                validated.set(false)
            }
        }
    }

    @Synchronized
    fun register() {
        val manager = connectivityManager ?: return
        if (registered) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                manager.registerNetworkCallback(request, networkCallback)
            }
            registered = true
        }.onFailure { L.w { "[TranslateModel] network watcher register failed: ${it.stackTraceToString()}" } }
    }

    @Synchronized
    fun unregister() {
        val manager = connectivityManager ?: return
        if (!registered) return
        registered = false
        validated.set(false)
        currentNetwork = null
        runCatching { manager.unregisterNetworkCallback(networkCallback) }
            .onFailure { L.w { "[TranslateModel] network watcher unregister failed" } }
    }
}
