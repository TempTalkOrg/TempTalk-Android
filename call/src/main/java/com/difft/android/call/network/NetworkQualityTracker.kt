package com.difft.android.call.network




class NetworkQualityTracker {

    private class Entry(
        var isLocal: Boolean,
        var raw: NetworkQualityLevel,
        var since: Long,
        var published: NetworkQualityLevel,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var suppressed = false

    










    fun onQualityChanged(
        identity: String,
        level: NetworkQualityLevel,
        isLocal: Boolean,
        now: Long,
    ): Boolean {
        val entry = entries[identity]
        if (entry == null) {
            
            
            entries[identity] = Entry(isLocal, level, now, NetworkQualityLevel.EXCELLENT)
            return true
        }
        entry.isLocal = isLocal
        if (entry.raw == level) return false
        entry.raw = level
        entry.since = now
        return true
    }

    
    fun onParticipantLeft(identity: String) {
        entries.remove(identity)
    }

    











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

    










    fun retainRemotes(presentRemoteIdentities: Set<String>) {
        entries.entries.removeAll { (identity, entry) ->
            !entry.isLocal && identity !in presentRemoteIdentities
        }
    }

    
    fun reset() {
        entries.clear()
        suppressed = false
    }

    











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
        
        const val WORSEN_DELAY_MS = 3_000L

        
        const val IMPROVE_DELAY_MS = 5_000L
    }
}
