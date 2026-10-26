package com.difft.android.call.connect

import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.call.UrlInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingConnectionPlannerTest {

    private val serviceUrls = ServiceUrls(
        config_version = 1,
        fallback = listOf(UrlInfo(addrs = listOf("198.51.100.1"), domain = "bj.example", region = "bj")),
        primary = UrlInfo(addrs = listOf("192.0.2.1", "192.0.2.2"), domain = "hk.example", region = "hk"),
        ttl = 1800,
    )

    @Test
    fun `QUIC enabled tries each IP over QUIC without a separate WSS attempt`() {
        val attempts = MeetingConnectionPlanner.buildAttempts(serviceUrls, quicEnabled = true)

        assertEquals(
            listOf(
                Triple("https://192.0.2.1", true, ConnectionAttempt.NODE_TYPE_PRIMARY),
                Triple("https://192.0.2.2", true, ConnectionAttempt.NODE_TYPE_PRIMARY),
                Triple("https://198.51.100.1", true, ConnectionAttempt.NODE_TYPE_FALLBACK),
            ),
            attempts.map { Triple(it.connectUrl, it.useQuic, it.nodeType) },
        )
        assertEquals(listOf("hk.example", "hk.example", "bj.example"), attempts.map { it.serverHost })
    }

    @Test
    fun `QUIC disabled tries each domain once over WSS`() {
        val attempts = MeetingConnectionPlanner.buildAttempts(serviceUrls, quicEnabled = false)

        assertEquals(
            listOf("https://hk.example" to false, "https://bj.example" to false),
            attempts.map { it.connectUrl to it.useQuic },
        )
    }

    @Test
    fun `QUIC enabled falls back to domain WSS when a node has no IPs`() {
        val noIps = serviceUrls.copy(
            fallback = listOf(UrlInfo(addrs = emptyList(), domain = "bj.example", region = "bj")),
        )

        val attempts = MeetingConnectionPlanner.buildAttempts(noIps, quicEnabled = true)

        assertEquals("https://bj.example" to false, attempts.last().let { it.connectUrl to it.useQuic })
    }
}
