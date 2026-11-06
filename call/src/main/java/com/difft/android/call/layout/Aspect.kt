// Synced from call-layout bf6b84ecbe, ports/kotlin/src/main/kotlin/org/difft/calllayout/Aspect.kt (spec v25), by scripts/sync-apps.ts.
// Do not edit here: change call-layout and sync again.

package com.difft.android.call.layout

import kotlin.math.abs

// Debounces video aspect changes before they reach the layout, so a phone being rotated
// doesn't reshape cards on every intermediate frame. The first aspect of a video applies at
// once (it arrives together with the first frame); a later change applies after it has held
// for `aspectSettleMs`. Aspects within 2% count as the same. Ported from src/core/aspect.ts.

data class PendingAspect(val aspect: Double, val since: Long)

data class AspectState(
    val settled: Map<String, Double>,
    val pending: Map<String, PendingAspect>,
)

fun createAspectState(): AspectState = AspectState(emptyMap(), emptyMap())

private fun same(a: Double, b: Double) = abs(a / b - 1) <= 0.02

// `raw` holds the latest frame aspect per participant; null means no video.
fun settleAspects(prev: AspectState, raw: Map<String, Double?>, now: Long, cfg: LayoutConfig): AspectState {
    val settled = LinkedHashMap<String, Double>()
    val pending = LinkedHashMap<String, PendingAspect>()
    for ((id, aspect) in raw) {
        if (aspect == null || !(aspect > 0)) continue
        val current = prev.settled[id]
        if (current == null) {
            settled[id] = aspect
            continue
        }
        if (same(aspect, current)) {
            settled[id] = current
            continue
        }
        val waiting = prev.pending[id]
        if (waiting != null && same(aspect, waiting.aspect)) {
            if (now - waiting.since >= cfg.aspectSettleMs) {
                settled[id] = aspect
            } else {
                settled[id] = current
                pending[id] = waiting
            }
        } else {
            settled[id] = current
            pending[id] = PendingAspect(aspect, now)
        }
    }
    return AspectState(settled, pending)
}
