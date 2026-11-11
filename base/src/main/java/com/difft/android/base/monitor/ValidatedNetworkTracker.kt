package com.difft.android.base.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton









@Singleton
class ValidatedNetworkTracker internal constructor(
    private val elapsedClock: () -> Long,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ SystemClock.elapsedRealtime() }) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    onValidity(network.hashCode(), networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                }

                override fun onLost(network: Network) = onLost(network.hashCode())
            })
        }
    }

    private val lock = Any()
    private var networkId: Int? = null
    private var validated: Boolean? = null
    private var onlineSince: Long? = null

    
    private val spans = ArrayDeque<Pair<Long, Long>>()

    internal fun onValidity(id: Int, isValidated: Boolean) = synchronized(lock) {
        networkId = id
        when {
            !isValidated -> closeSpan()
            
            
            validated != true -> onlineSince = elapsedClock()
        }
        validated = isValidated
    }

    internal fun onLost(id: Int) = synchronized(lock) {
        if (networkId != id) return@synchronized
        validated = false
        closeSpan()
    }

    private fun closeSpan() {
        val now = elapsedClock()
        onlineSince?.let { spans.addLast(it to now) }
        onlineSince = null
        while (spans.isNotEmpty() && spans.first().second < now - HISTORY_MS) spans.removeFirst()
    }

    



    fun onlineMsWithin(windowMs: Long): Long? = synchronized(lock) {
        if (validated == null) return@synchronized null
        val now = elapsedClock()
        val start = now - windowMs
        var total = 0L
        for ((spanStart, spanEnd) in spans) total += (spanEnd - maxOf(spanStart, start)).coerceAtLeast(0L)
        onlineSince?.let { total += (now - maxOf(it, start)).coerceAtLeast(0L) }
        total
    }

    companion object {
        
        const val HISTORY_MS = 24L * 60 * 60 * 1000
    }
}
