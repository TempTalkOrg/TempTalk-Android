package com.difft.android.network.speedtest

/** One host's locally observed availability: a mark, a healthy answer, or a reset. */
internal data class HostState(val available: Boolean, val generation: Long)

/**
 * Everything [DomainSpeedTestCoordinator]'s eligibility predicate reads, swapped atomically so a
 * probe publication never exposes a new ranking beside the previous probe's local state.
 * [ranked] is the last probe, best first; [local] holds the hosts changed since or after it.
 */
internal data class HostStore(
    val ranked: List<HostSpeedResult> = emptyList(),
    val local: Map<String, HostState> = emptyMap(),
) {
    /** True when the host is marked down locally, or the probe says so and nothing newer overrides it. */
    fun isDown(host: String): Boolean =
        local[host]?.let { !it.available } ?: ranked.any { it.host == host && !it.isAvailable }

    /**
     * Local state wins over the probe's verdict. [probeApplies] is false while the proxy is on:
     * probes run on the direct path, so their verdict says nothing about a tunnel host.
     */
    fun isEligible(host: String, probeApplies: Boolean): Boolean {
        val state = local[host]
        if (state != null) return state.available
        return !probeApplies || ranked.none { it.host == host && !it.isAvailable }
    }
}
