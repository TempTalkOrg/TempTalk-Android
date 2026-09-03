package com.difft.android.call.network

/**
 * Weak-network verdict state machine.
 *
 * Polling-shaped, not timer-shaped: it owns no timer and no clock — the caller advances it with
 * [evaluate] on every quality event and every 500 ms tick, passing `now` in. That keeps the whole
 * hysteresis testable by handing it 2_900 / 3_000 instead of waiting three real seconds, and keeps
 * the shape identical to the iOS/Desktop units so the shared test cases index 1:1.
 *
 * `now` MUST come from a monotonic clock (`SystemClock.elapsedRealtime()`): a wall clock jumps
 * backwards on time sync (a hint would stick forever) and forwards on sleep/wake (hysteresis would
 * be skipped). Non-monotonic input is deliberately NOT clamped here — clamping would hide the
 * caller's bug and break the cross-platform semantics.
 *
 * NOT thread-safe by design. Every entry point must be called from one single dispatcher (the
 * call's event/tick coroutine); there is no lock so the hot 500 ms path stays allocation-free.
 */
class NetworkQualityTracker {

    private class Entry(
        var isLocal: Boolean,
        var raw: NetworkQualityLevel,
        var since: Long,
        var published: NetworkQualityLevel,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var suppressed = false

    /**
     * Feeds one mapped quality reading. Also the re-seed entry point — a mid-call mount and a
     * post-reconnect re-seed both come through here, there is no separate API.
     *
     * `since` is reset ONLY when the mapped tier actually changes. The SDK re-reports the same tier
     * repeatedly; resetting per event would keep the timer from ever filling up and the feature
     * would show nothing at all.
     *
     * @return true when the raw tier actually moved (i.e. `since` was reset) — the caller gates its
     *   raw-transition log on this instead of logging every SDK report.
     */
    fun onQualityChanged(
        identity: String,
        level: NetworkQualityLevel,
        isLocal: Boolean,
        now: Long,
    ): Boolean {
        val entry = entries[identity]
        if (entry == null) {
            // First reading: raw starts at the reading, published starts healthy, so even the very
            // first bad reading has to survive the 3 s worsening delay.
            entries[identity] = Entry(isLocal, level, now, NetworkQualityLevel.EXCELLENT)
            return true
        }
        entry.isLocal = isLocal
        if (entry.raw == level) return false
        entry.raw = level
        entry.since = now
        return true
    }

    /** Participant left: drop the entry immediately so a stale verdict can't linger on their tile. */
    fun onParticipantLeft(identity: String) {
        entries.remove(identity)
    }

    /**
     * Suppression rule 2 input (`room.state != CONNECTED` -> true).
     *
     * @return true when the flag actually changed. On a true->false transition every published
     *   verdict is dropped and every timer restarts at [now], so nothing is shown the moment the
     *   call reconnects; if the network really is still bad it lights up again 3 s later.
     *
     *   The caller MUST then re-seed and call [retainRemotes]: clearing only `published` would
     *   republish the pre-disconnect Poor onto a healthy link, and clearing `raw` too would
     *   permanently miss a weak network that never changes across the disconnect (quality events
     *   are edge-triggered).
     */
    fun onSuppressedChanged(isSuppressed: Boolean, now: Long): Boolean {
        if (suppressed == isSuppressed) return false
        suppressed = isSuppressed
        if (!isSuppressed) {
            entries.values.forEach { entry ->
                entry.published = NetworkQualityLevel.EXCELLENT
                entry.since = now
            }
        }
        return true
    }

    /**
     * Reverse cleanup after a re-seed: drops every entry that is neither local nor in
     * [presentRemoteIdentities].
     *
     * "Participant left" events alone are not enough — the SDK can clear the remote member table on
     * a full disconnect without emitting them one by one, and the Room object may be reused across
     * two consecutive calls.
     *
     * Idempotent, and MUST run AFTER seeding: running it first would prune the members this
     * re-seed is about to (re-)add.
     */
    fun retainRemotes(presentRemoteIdentities: Set<String>) {
        entries.entries.removeAll { (identity, entry) ->
            !entry.isLocal && identity !in presentRemoteIdentities
        }
    }

    /** Call teardown: wipe every entry and the suppression flag. */
    fun reset() {
        entries.clear()
        suppressed = false
    }

    /**
     * Advances the hysteresis. Worsening (severity up, `EXCELLENT -> GOOD` included) needs 3 s;
     * improving needs 5 s. A reversal before the delay fills cancels it implicitly, because `raw`
     * changing back to `published` short-circuits the loop — no explicit timer clearing needed.
     * The landing tier is the CURRENT raw value, so `BAD -> GOOD` lands on GOOD, not EXCELLENT.
     *
     * Runs regardless of suppression: [view] does the hiding, so timers keep advancing and the
     * post-reconnect re-arm stays uniform.
     *
     * @return true when at least one entry's published tier changed — the caller gates its
     *   transition log on this instead of logging 7200 ticks per hour.
     */
    fun evaluate(now: Long): Boolean {
        var changed = false
        for (entry in entries.values) {
            if (entry.raw == entry.published) continue
            val worsening = entry.raw.severity > entry.published.severity
            val delayMs = if (worsening) WORSEN_DELAY_MS else IMPROVE_DELAY_MS
            if (now - entry.since >= delayMs) {
                entry.published = entry.raw
                changed = true
            }
        }
        return changed
    }

    /**
     * Snapshot for the UI, with both suppression rules already applied:
     *  - suppressed -> nothing at all;
     *  - local published == BAD -> every remote entry is HIDDEN (rule 1, BAD only: when the local
     *    downlink degrades the SDK degrades every remote reading at once, and showing "everyone is
     *    bad" points at the wrong party). Hidden, not deleted — the remote entries reappear as soon
     *    as the local side recovers, without having to earn their 3 s again.
     */
    fun view(): NetworkQualityView {
        if (suppressed) return NetworkQualityView(suppressed = true)
        val local = entries.values.firstOrNull { it.isLocal }?.published
            ?: NetworkQualityLevel.EXCELLENT
        if (local == NetworkQualityLevel.BAD) return NetworkQualityView(local = NetworkQualityLevel.BAD)
        val remote = entries
            .filter { (_, entry) -> !entry.isLocal && entry.published != NetworkQualityLevel.EXCELLENT }
            .mapValues { (_, entry) -> entry.published }
        return NetworkQualityView(local = local, remote = remote)
    }

    companion object {
        /** Worsening (severity up) must persist this long before it is published. */
        const val WORSEN_DELAY_MS = 3_000L

        /** Improving is slower: a false weak-network alarm hurts more than recovering 2 s late. */
        const val IMPROVE_DELAY_MS = 5_000L
    }
}
