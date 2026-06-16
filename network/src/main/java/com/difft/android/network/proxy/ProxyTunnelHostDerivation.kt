package com.difft.android.network.proxy

/**
 * Pure derivation of the tunnel-host set. Visible for tests.
 *
 * Each input list is normalized (lowercase, [String.trim], [String.trimEnd] with
 * a trailing dot, drop blanks) and the three sources are unioned into an
 * immutable [Set]. The baseline is always present — even when both other
 * sources are empty, the result equals `baseline` (never empty when `baseline`
 * is non-empty).
 *
 * The output preserves insertion order (baseline first, then global self-cert
 * hosts, then call-service domains) so log emission and any debug iteration is
 * deterministic.
 */
internal fun computeTunnelHosts(
    globalSelfCertHosts: List<String>,
    callDomains: List<String>,
    baseline: Set<String>,
): Set<String> {
    val result = LinkedHashSet<String>(baseline.size + globalSelfCertHosts.size + callDomains.size)
    baseline.forEach { add(result, it) }
    globalSelfCertHosts.forEach { add(result, it) }
    callDomains.forEach { add(result, it) }
    return result.toSet()
}

private fun add(set: MutableSet<String>, raw: String?) {
    val n = raw?.trim()?.lowercase()?.trimEnd('.') ?: return
    if (n.isNotEmpty()) set.add(n)
}
