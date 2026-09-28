package com.difft.android.chat.translate.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default-network watcher for the translation-model download.
 *
 * Deliberately separate from `chat/.../websocket/monitor/NetworkMonitor`: that one's NetworkRequest
 * is WIFI/CELLULAR/ETHERNET only and is owned by the WebSocket reconnect path, so adding
 * TRANSPORT_VPN there would change reconnect behaviour for every user. The model download is the
 * only consumer that cares about a VPN specifically — on a network where Google is unreachable the
 * download only becomes possible once a VPN interface comes up, and a VPN coming up *replaces* the
 * default network, which is exactly what `registerDefaultNetworkCallback` reports.
 *
 * Shape follows `call/.../receiver/NetworkConnectionListener` (including the pre-28 fallback, since
 * minSdk is 26 and `registerDefaultNetworkCallback` is API 24 — kept identical for consistency with
 * the existing listener) with one addition: the validated flag is reset per network handle, so each
 * newly-available validated default network produces exactly one callback carrying its own VPN flag
 * instead of only the very first one after a loss.
 */
internal class TranslateNetworkWatcher(
    context: Context,
    private val onNetworkUsable: (isVpn: Boolean) -> Unit,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val validated = AtomicBoolean(false)

    @Volatile
    private var currentNetwork: Network? = null

    /** register/unregister are idempotent: a duplicate unregister throws IllegalArgumentException. */
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
