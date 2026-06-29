package com.difft.android.network.proxy

/**
 * Pure derivation of the tunnel-host set. Visible for tests.
 *
 * The whitelist is the union of the proxy chat domains and the proxy call
 * domains (both from `proxy.tunnelDomains`, see
 * [com.difft.android.base.utils.IGlobalConfigsManager]). Each input list is
 * normalized (lowercase, [String.trim], [String.trimEnd] with a trailing dot,
 * drop blanks) and unioned into an immutable [Set] — the proxy path forces ALL
 * traffic onto these same domains, so the whitelist and the actual traffic share
 * one source of truth.
 *
 * The output preserves insertion order (chat domains first, then call domains)
 * so log emission and any debug iteration is deterministic.
 */
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
