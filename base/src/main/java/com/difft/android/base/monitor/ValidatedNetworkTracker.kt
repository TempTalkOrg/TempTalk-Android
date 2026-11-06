package com.difft.android.base.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long the device has had a validated default network, as seen by this process, so that a
 * message's delay can be split into time online and time offline. Online spans are kept for the
 * last [HISTORY_MS]; a validated replacement network (Wi-Fi to cellular hand-over) continues the
 * span, an offline gap closes it and a later validation opens a new one. A network already
 * validated when tracking starts counts from the first reading, a lower bound: earlier offline
 * time stays excluded rather than assumed online.
 */
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

    /** Closed online spans (start, end) in elapsed time, oldest first, within [HISTORY_MS]. */
    private val spans = ArrayDeque<Pair<Long, Long>>()

    internal fun onValidity(id: Int, isValidated: Boolean) = synchronized(lock) {
        networkId = id
        when {
            !isValidated -> closeSpan()
            // First reading or validated after an offline reading: a fresh online span. A validated
            // replacement network continues the current span.
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

    /**
     * Milliseconds the device was online during the last [windowMs], as seen by this process; null
     * before the first network reading.
     */
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
        /** Longest delay the late-delivery rule still counts, so older spans are never needed. */
        const val HISTORY_MS = 24L * 60 * 60 * 1000
    }
}
