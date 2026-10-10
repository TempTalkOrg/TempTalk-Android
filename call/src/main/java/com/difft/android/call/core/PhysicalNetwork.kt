package com.difft.android.call.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

internal fun Context.physicalNetworkHandle(): Long {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return connectivityManager.allNetworks
        .filter { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@filter false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    )
        }
        .sortedBy { network -> physicalNetworkPriority(connectivityManager, network) }
        .firstOrNull()
        ?.networkHandle
        ?: 0
}

private fun physicalNetworkPriority(
    connectivityManager: ConnectivityManager,
    network: Network,
): Int {
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return Int.MAX_VALUE
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 3
    }
}
