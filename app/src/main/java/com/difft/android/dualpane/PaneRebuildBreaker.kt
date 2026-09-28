package com.difft.android.dualpane

internal enum class PaneRebuildVerdict {
    /** Rebuild budget available — issue the relaunch. */
    PROCEED,

    /** Budget just ran out — freeze the tree and report once. */
    TRIP,

    /** Breaker already open — stay silent until an agreement re-arms it. */
    SUPPRESSED,
}

/**
 * Bounds the `recreate()` chain a gate/tree disagreement may cause.
 *
 * A healthy rebuild settles on the first pass of the new instance (gate == tree). If it does
 * not — a `@bool` / `@dimen` resource overlay, or drift between `layout-w673dp-h480dp/` and
 * `values-w673dp-h480dp/` — every reconcile would relaunch again at the throttle rate for the
 * life of the process. The breaker allows [maxRebuilds] for one unchanged disagreement and
 * [maxTotalRebuilds] across ALL disagreements since the last agreement, so an alternating
 * disagreement is bounded too. Only an agreement re-arms it.
 */
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

    fun onRebuild(gate: Boolean, tree: Boolean, chromeStale: Boolean): PaneRebuildVerdict {
        // Open stays open until an agreement: checked BEFORE the signature compare so that a
        // signature change cannot re-arm an open breaker (that is what makes the total ceiling bind).
        if (rebuilds > maxRebuilds || totalRebuilds > maxTotalRebuilds) {
            return PaneRebuildVerdict.SUPPRESSED
        }

        val current = signatureOf(gate, tree, chromeStale)
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

    private fun signatureOf(gate: Boolean, tree: Boolean, chromeStale: Boolean): Int =
        (if (gate) 1 else 0) or (if (tree) 2 else 0) or (if (chromeStale) 4 else 0)

    private companion object {
        const val DEFAULT_MAX_REBUILDS = 3
        const val TOTAL_BUDGET_FACTOR = 4
        const val NO_SIGNATURE = -1
    }
}
