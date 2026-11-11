package com.difft.android.network.speedtest


internal data class HostState(val available: Boolean, val generation: Long)






internal data class HostStore(
    val ranked: List<HostSpeedResult> = emptyList(),
    val local: Map<String, HostState> = emptyMap(),
) {
    
    fun isDown(host: String): Boolean =
        local[host]?.let { !it.available } ?: ranked.any { it.host == host && !it.isAvailable }

    



    fun isEligible(host: String, probeApplies: Boolean): Boolean {
        val state = local[host]
        if (state != null) return state.available
        return !probeApplies || ranked.none { it.host == host && !it.isAvailable }
    }
}
