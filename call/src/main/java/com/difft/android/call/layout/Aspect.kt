


package com.difft.android.call.layout

import kotlin.math.abs






data class PendingAspect(val aspect: Double, val since: Long)

data class AspectState(
    val settled: Map<String, Double>,
    val pending: Map<String, PendingAspect>,
)

fun createAspectState(): AspectState = AspectState(emptyMap(), emptyMap())

private fun same(a: Double, b: Double) = abs(a / b - 1) <= 0.02


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
