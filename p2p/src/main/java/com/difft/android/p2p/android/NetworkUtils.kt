package com.difft.android.p2p.android

import com.difft.android.base.log.lumberjack.L
import java.net.Inet4Address
import java.net.NetworkInterface


object NetworkUtils {

    



    private val LAN_CAPABLE_PREFIXES = listOf("wlan", "ap", "swlan", "eth", "usb", "rndis")

    




    private val NON_LAN_PREFIXES = listOf("tun", "ppp", "ipsec", "rmnet", "ccmni", "pdp", "clat")

    
    const val MAX_CANDIDATES = 4

    
    data class Candidate(val interfaceName: String, val address: String, val siteLocal: Boolean)

    








    fun lanCandidates(): List<String> = runCatching {
        val seen = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                    .map { Candidate(nif.name.lowercase(), it.hostAddress, it.isSiteLocalAddress) }
            }
        rank(seen)
    }.getOrElse { e ->
        L.w { "[P2P][Net] interface enumeration failed: ${e.message}" }
        emptyList()
    }

    






    fun rank(candidates: List<Candidate>): List<String> {
        val usable = candidates.filter { c -> NON_LAN_PREFIXES.none { c.interfaceName.startsWith(it) } }
        fun lanRank(c: Candidate): Int = LAN_CAPABLE_PREFIXES.indexOfFirst { c.interfaceName.startsWith(it) }
        val lan = usable.filter { lanRank(it) >= 0 }.sortedBy { lanRank(it) }
        val private = lan.filter { it.siteLocal }
        val public = lan.filter { !it.siteLocal }
        val unknown = usable.filter { lanRank(it) < 0 }.sortedByDescending { it.siteLocal }
        val ordered = (private + public + unknown).map { it.address }.distinct().take(MAX_CANDIDATES)
        val unknownAdvertised = unknown.filter { it.address in ordered }
        if (unknownAdvertised.isNotEmpty()) {
            L.w {
                "[P2P][Net] advertising ${unknownAdvertised.size} address(es) on unrecognised interfaces " +
                    "${unknownAdvertised.map { it.interfaceName }.distinct()} — first suspect if the transfer crawls"
            }
        }
        if (ordered.isEmpty()) {
            L.w { "[P2P][Net] no usable LAN address (up: ${candidates.map { it.interfaceName }.distinct()})" }
        } else {
            L.i { "[P2P][Net] LAN candidates=${ordered.size} first=${ordered.first()} on ${(private + public + unknown).first().interfaceName}" }
        }
        return ordered
    }
}
