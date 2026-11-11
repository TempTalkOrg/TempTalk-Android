package com.difft.android.network.proxy















internal fun computeTunnelHosts(
    chatDomains: List<String>,
    callDomains: List<String>,
): Set<String> {
    val result = LinkedHashSet<String>(chatDomains.size + callDomains.size)
    chatDomains.forEach { add(result, it) }
    callDomains.forEach { add(result, it) }
    return result.toSet()
}

private fun add(set: MutableSet<String>, raw: String?) {
    val n = raw?.trim()?.lowercase()?.trimEnd('.') ?: return
    if (n.isNotEmpty()) set.add(n)
}
