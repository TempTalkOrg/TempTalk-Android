package com.difft.android.call.connect

import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.call.UrlInfo
import com.difft.android.network.proxy.ProxyConfigProvider


















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
        val domainUrl = normalizeConnectUrl(domain) ?: return
        if (!quicEnabled) {
            out += ConnectionAttempt(serverHost = domain, connectUrl = domainUrl, useQuic = false, nodeType = nodeType)
            return
        }
        val sizeBefore = out.size
        if (ProxyConfigProvider.isProxyForCallQuicEnabled) {
            
            
            
            out += ConnectionAttempt(serverHost = domain, connectUrl = domainUrl, useQuic = true, nodeType = nodeType)
        } else {
            for (raw in node.addrs) {
                val url = normalizeConnectUrl(raw) ?: continue
                out += ConnectionAttempt(serverHost = domain, connectUrl = url, useQuic = true, nodeType = nodeType)
            }
        }
        
        
        if (out.size == sizeBefore) {
            out += ConnectionAttempt(serverHost = domain, connectUrl = domainUrl, useQuic = false, nodeType = nodeType)
        }
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

    


    fun normalizeConnectUrl(hostOrUrl: String): String? {
        val t = hostOrUrl.trim()
        if (t.isEmpty()) return null
        return when {
            t.startsWith("https://", ignoreCase = true) -> t
            t.startsWith("http://", ignoreCase = true) -> "https://" + t.substring(7)
            else -> "https://$t"
        }
    }

    



    fun isIpHost(host: String): Boolean {
        return host.matches(IPV4_REGEX) ||
            host.startsWith("[") ||
            host.count { it == ':' } >= 2
    }

    private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
}
