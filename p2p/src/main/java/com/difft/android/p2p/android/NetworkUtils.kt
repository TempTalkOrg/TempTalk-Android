package com.difft.android.p2p.android

import com.difft.android.base.log.lumberjack.L
import java.net.Inet4Address
import java.net.NetworkInterface

/** Small helpers for the P2P adapters (LAN address discovery for the QR payload + cert SAN). */
object NetworkUtils {

    /**
     * Interfaces that can actually carry a peer-to-peer LAN transfer: Wi-Fi, the hotspot AP, wired
     * and USB tethering. Matched by name prefix — Android has no portable API for "is this the LAN".
     */
    private val LAN_CAPABLE_PREFIXES = listOf("wlan", "ap", "swlan", "eth", "usb", "rndis")

    /**
     * Interfaces that carry a private-range address but can never reach a LAN peer: VPN tunnels and
     * the cellular link. Both hand out RFC1918 addresses (a carrier NAT commonly assigns `10.x`), so
     * an "is it site-local" test alone cannot tell them apart from Wi-Fi.
     */
    private val NON_LAN_PREFIXES = listOf("tun", "ppp", "ipsec", "rmnet", "ccmni", "pdp", "clat")

    /** The pairing code carries at most this many; the connecting side races them. */
    const val MAX_CANDIDATES = 4

    /** One address on one interface, as [rank] sees it. */
    data class Candidate(val interfaceName: String, val address: String, val siteLocal: Boolean)

    /**
     * The device's LAN IPv4 addresses for the QR payload, best first, at most [MAX_CANDIDATES];
     * empty when no interface that could carry a transfer is up.
     *
     * Selection is by interface, not by "the first site-local address found": cellular and VPN
     * interfaces also hand out RFC1918 addresses and would otherwise outrank Wi-Fi. Several
     * addresses are advertised because which one the peer can reach is the peer's business — the
     * connecting side settles it by racing them (see `EndpointRace`).
     */
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

    /**
     * Orders [candidates] the way the code should advertise them: private addresses on LAN-capable
     * interfaces first (in prefix order), then public addresses on those same interfaces, then
     * anything on an unrecognised interface — kept rather than dropped, but logged as the first
     * suspect when a transfer crawls. Tunnel and cellular interfaces never make the list.
     * Deduplicated, capped.
     */
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
