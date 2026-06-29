package com.difft.android.call.connect

import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.call.UrlInfo
import com.difft.android.network.proxy.ProxyConfigProvider

/**
 * Connection strategy:
 * - QUIC normally uses IP direct connections (from [UrlInfo.addrs]). EXCEPTION: under a
 *   self-hosted proxy QUIC relay ([ProxyConfigProvider.isProxyForCallQuicEnabled], MASQUE-lite §9.6)
 *   QUIC uses the DOMAIN instead — the relay forwards by the tunnel's CONNECT target host and
 *   enforces a domain-suffix allowlist, so an IP literal would be denied.
 * - WSS (WebSocket) is only used with the domain (from [UrlInfo.domain]); WSS over IP is not allowed.
 *
 * Attempt order:
 * - QUIC enabled: primary.addrs (QUIC) → primary.domain (WSS) → each fallback in the same order
 * - QUIC enabled + proxy QUIC relay: primary.domain (QUIC) → primary.domain (WSS) → each fallback domain (QUIC→WSS)
 * - QUIC disabled: primary.domain (WSS) → each fallback.domain (WSS)
 *
 * [ConnectionAttempt.serverHost] is used for TLS/SNI (typically the certificate domain);
 * for IP direct connections, the node's [UrlInfo.domain] is passed.
 */
data class ConnectionAttempt(
    val serverHost: String,
    val connectUrl: String,
    val useQuic: Boolean,
    val nodeType: String = NODE_TYPE_PRIMARY,
) {
    companion object {
        const val NODE_TYPE_PRIMARY = "primary"
        const val NODE_TYPE_FALLBACK = "fallback"
    }
}

object MeetingConnectionPlanner {

    fun buildAttempts(serviceUrls: ServiceUrls, quicEnabled: Boolean): List<ConnectionAttempt> {
        val out = ArrayList<ConnectionAttempt>()
        val primary = serviceUrls.primary ?: return emptyList()
        appendNodeAttempts(out, primary, quicEnabled, ConnectionAttempt.NODE_TYPE_PRIMARY)
        for (fb in serviceUrls.fallback) {
            if (fb != null) appendNodeAttempts(out, fb, quicEnabled, ConnectionAttempt.NODE_TYPE_FALLBACK)
        }
        return dedupePreserveOrder(out)
    }

    private fun appendNodeAttempts(out: ArrayList<ConnectionAttempt>, node: UrlInfo, quicEnabled: Boolean, nodeType: String) {
        val domain = node.domain.trim().ifEmpty { null } ?: return
        if (quicEnabled) {
            if (ProxyConfigProvider.isProxyForCallQuicEnabled) {
                // QUIC-over-proxy (MASQUE-lite, §9.6): connect via the DOMAIN, not the
                // node IPs. The relay forwards by the tunnel's CONNECT target host and
                // enforces a domain-suffix allowlist — an IP literal would be denied.
                normalizeConnectUrl(domain)?.let { url ->
                    out += ConnectionAttempt(serverHost = domain, connectUrl = url, useQuic = true, nodeType = nodeType)
                }
            } else {
                for (raw in node.addrs) {
                    val url = normalizeConnectUrl(raw) ?: continue
                    out += ConnectionAttempt(serverHost = domain, connectUrl = url, useQuic = true, nodeType = nodeType)
                }
            }
        }
        val domainUrl = normalizeConnectUrl(domain) ?: return
        out += ConnectionAttempt(serverHost = domain, connectUrl = domainUrl, useQuic = false, nodeType = nodeType)
    }

    private fun dedupePreserveOrder(attempts: List<ConnectionAttempt>): List<ConnectionAttempt> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<ConnectionAttempt>()
        for (a in attempts) {
            val key = "${a.useQuic}|${a.connectUrl}|${a.serverHost}"
            if (seen.add(key)) result += a
        }
        return result
    }

    /**
     * Normalizes an IP, domain, or URL with scheme into an https URL used by LiveKit.
     */
    fun normalizeConnectUrl(hostOrUrl: String): String? {
        val t = hostOrUrl.trim()
        if (t.isEmpty()) return null
        return when {
            t.startsWith("https://", ignoreCase = true) -> t
            t.startsWith("http://", ignoreCase = true) -> "https://" + t.substring(7)
            else -> "https://$t"
        }
    }

    /**
     * Heuristic: tells whether [host] is an IP literal rather than a domain name.
     * Recognizes IPv4, bracketed IPv6 (`[::1]`), and bare IPv6 (≥2 colons).
     */
    fun isIpHost(host: String): Boolean {
        return host.matches(IPV4_REGEX) ||
            host.startsWith("[") ||
            host.count { it == ':' } >= 2
    }

    private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
}
