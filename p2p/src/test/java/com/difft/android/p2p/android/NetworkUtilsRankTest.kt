package com.difft.android.p2p.android

import com.difft.android.p2p.android.NetworkUtils.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address order a pairing code advertises (§1.1 of the pairing proposal). */
class NetworkUtilsRankTest {

    private fun wlan(ip: String, site: Boolean = true) = Candidate("wlan0", ip, site)
    private fun ap(ip: String) = Candidate("ap0", ip, true)
    private fun rmnet(ip: String) = Candidate("rmnet1", ip, true)
    private fun tun(ip: String) = Candidate("tun0", ip, true)

    @Test
    fun `cellular and tunnel addresses never make the list, whatever their order`() {
        assertEquals(listOf("192.168.1.150"), NetworkUtils.rank(listOf(rmnet("10.23.85.127"), tun("10.8.0.2"), wlan("192.168.1.150"))))
        assertTrue(NetworkUtils.rank(listOf(rmnet("10.23.85.127"), Candidate("clat4", "192.0.0.4", false))).isEmpty())
    }

    @Test
    fun `wifi before hotspot, private before public, unknown names last`() {
        val ranked = NetworkUtils.rank(
            listOf(
                Candidate("weird0", "172.16.0.9", true),
                wlan("203.0.113.7", site = false),
                ap("192.168.43.1"),
                wlan("192.168.1.150"),
            ),
        )
        assertEquals(listOf("192.168.1.150", "192.168.43.1", "203.0.113.7", "172.16.0.9"), ranked)
    }

    @Test
    fun `at most four, deduplicated`() {
        val many = (1..6).map { Candidate("eth$it", "10.0.0.$it", true) } + wlan("10.0.0.1")
        val ranked = NetworkUtils.rank(many)
        assertEquals(NetworkUtils.MAX_CANDIDATES, ranked.size)
        assertEquals("10.0.0.1", ranked.first()) // wlan outranks eth even when listed last
        assertEquals(ranked.distinct(), ranked)
    }

    @Test
    fun `an unrecognised interface alone still yields an address`() {
        assertEquals(listOf("10.1.2.3"), NetworkUtils.rank(listOf(Candidate("vendor_wifi0", "10.1.2.3", true))))
    }
}
