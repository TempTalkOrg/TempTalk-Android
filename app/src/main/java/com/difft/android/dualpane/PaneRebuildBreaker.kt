package com.difft.android.dualpane

internal enum class PaneRebuildVerdict {
    
    PROCEED,

    
    TRIP,

    
    SUPPRESSED,
}











internal class PaneRebuildBreaker(
    private val maxRebuilds: Int = DEFAULT_MAX_REBUILDS,
    private val maxTotalRebuilds: Int = maxRebuilds * TOTAL_BUDGET_FACTOR,
) {

    private var signature = NO_SIGNATURE
    private var rebuilds = 0
    private var totalRebuilds = 0

    fun onAgreement() {
        signature = NO_SIGNATURE
        rebuilds = 0
        totalRebuilds = 0
    }

    
    fun onRebuild(gate: Boolean, tree: Boolean, chromeStale: Boolean, staleLayout: Boolean = false): PaneRebuildVerdict {
        
        
        if (rebuilds > maxRebuilds || totalRebuilds > maxTotalRebuilds) {
            return PaneRebuildVerdict.SUPPRESSED
        }

        val current = signatureOf(gate, tree, chromeStale, staleLayout)
        if (current != signature) {
            signature = current
            rebuilds = 1
        } else {
            rebuilds++
        }
        totalRebuilds++

        return if (rebuilds <= maxRebuilds && totalRebuilds <= maxTotalRebuilds) {
            PaneRebuildVerdict.PROCEED
        } else {
            PaneRebuildVerdict.TRIP
        }
    }

    private fun signatureOf(gate: Boolean, tree: Boolean, chromeStale: Boolean, staleLayout: Boolean): Int =
        (if (gate) 1 else 0) or (if (tree) 2 else 0) or (if (chromeStale) 4 else 0) or (if (staleLayout) 8 else 0)

    private companion object {
        const val DEFAULT_MAX_REBUILDS = 3
        const val TOTAL_BUDGET_FACTOR = 4
        const val NO_SIGNATURE = -1
    }
}
