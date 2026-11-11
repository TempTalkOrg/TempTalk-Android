


package com.difft.android.call.layout









data class SchedulerState(
    val order: List<String>,
    val promotedAt: Map<String, Long>,
    val speakingSince: Map<String, Long>,
    val videoSince: Map<String, Long>,
    val lastSpokeAt: Map<String, Long>,
)

fun createScheduler(): SchedulerState = SchedulerState(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

fun stepScheduler(
    prev: SchedulerState,
    participants: List<Participant>,
    now: Long,
    visibleCount: Int,
    paused: Boolean,
    cfg: LayoutConfig,
): SchedulerState {
    val byId = participants.associateBy { it.id }
    val local = participants.firstOrNull { it.isLocal }
    fun camera(p: Participant) = p.cameraOn ?: p.videoOn

    var order = prev.order.filter { it in byId }.toMutableList()
    for (p in participants) if (p.id !in order) order.add(p.id)
    if (local != null) order = (listOf(local.id) + order.filter { it != local.id }).toMutableList()

    val promotedAt = LinkedHashMap<String, Long>()
    val speakingSince = LinkedHashMap<String, Long>()
    val videoSince = LinkedHashMap<String, Long>()
    val lastSpokeAt = LinkedHashMap<String, Long>()
    
    
    val talking = mutableSetOf<String>()
    for (p in participants) {
        prev.promotedAt[p.id]?.let { promotedAt[p.id] = it }
        prev.lastSpokeAt[p.id]?.let { lastSpokeAt[p.id] = it }
        if (p.speaking) lastSpokeAt[p.id] = now
        val last = lastSpokeAt[p.id]
        if (p.speaking || (last != null && now - last < cfg.speakingHangoverMs)) {
            talking.add(p.id)
            speakingSince[p.id] = prev.speakingSince[p.id] ?: now
        }
        if (camera(p)) videoSince[p.id] = prev.videoSince[p.id] ?: now
    }
    val next = { SchedulerState(order.toList(), promotedAt.toMap(), speakingSince, videoSince, lastSpokeAt) }
    if (paused || visibleCount >= order.size) return next()

    
    
    
    fun score(id: String, waiting: Boolean = false): Int {
        val p = byId[id] ?: return 0
        if (id in talking && (!waiting || now - speakingSince.getValue(id) >= cfg.promoteSpeakingMs)) return 3
        if (camera(p)) return 2
        val t = lastSpokeAt[id]
        return if (t != null && now - t <= cfg.recentSpeakMs) 1 else 0
    }

    fun eligible(id: String): Boolean {
        val p = byId[id] ?: return false
        if (id in talking && now - speakingSince.getValue(id) >= cfg.promoteSpeakingMs) return true
        return camera(p) && now - videoSince.getValue(id) >= cfg.promoteVideoMs
    }

    var cand = -1
    for (i in visibleCount until order.size) {
        if (!eligible(order[i])) continue
        if (cand < 0 || score(order[i], waiting = true) > score(order[cand], waiting = true)) cand = i
    }
    if (cand < 0) return next()

    
    var victim = -1
    for (i in visibleCount - 1 downTo 0) {
        val id = order[i]
        if (local != null && id == local.id) continue
        val at = promotedAt[id]
        if (at != null && now - at < cfg.holdMs) continue
        if (victim < 0 || score(id) < score(order[victim])) victim = i
    }
    if (victim < 0 || score(order[cand], waiting = true) <= score(order[victim])) return next()

    val promoted = order[cand]
    order[cand] = order[victim]
    order[victim] = promoted
    promotedAt[promoted] = now
    return next()
}



fun keepOnly(state: SchedulerState, ids: Set<String>): SchedulerState = SchedulerState(
    order = state.order.filter { it in ids },
    promotedAt = state.promotedAt.filterKeys { it in ids },
    speakingSince = state.speakingSince.filterKeys { it in ids },
    videoSince = state.videoSince.filterKeys { it in ids },
    lastSpokeAt = state.lastSpokeAt.filterKeys { it in ids },
)

fun applyOrder(participants: List<Participant>, state: SchedulerState): List<Participant> {
    val rank = state.order.withIndex().associate { (i, id) -> id to i }
    return participants.sortedBy { rank[it.id] ?: 1_000_000_000 }
}
