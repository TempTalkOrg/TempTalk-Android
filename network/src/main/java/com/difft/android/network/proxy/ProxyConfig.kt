package com.difft.android.network.proxy

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Self-hosted proxy connection descriptor (Mode B: IP + certificate fingerprint).
 *
 * The client establishes an OUTER TLS tunnel to [host]:[port] and pins the
 * proxy's SPKI fingerprint ([spkiPinBase64]) instead of trusting any CA — this
 * is why no domain / public CA is required on the operator side. The INNER TLS
 * (to the real TempTalk origin) is unaffected and keeps using the bundled
 * `chative_ssl_ca.pem` trust anchor.
 *
 * Share-code format (spec v1, see `docs/claude/proxy-share-link-format.md`) is
 * an obfuscated, optionally passphrase-encrypted token handled by
 * [ProxyLinkCodec]:
 * ```
 * ytp://config?d=<base64url( version | mode | [salt|iv|] json )>
 * json = {"v":1,"h":<host>,"p":<port>,"f":<spki-pin>,"t":<turn>}
 * ```
 *
 * The optional `t` (turn) parameter enables **call media** routing through the
 * operator's TURN server (coturn). When present, the client forces relay-only
 * ICE so WebRTC media is relayed via `turns:[host]:[port]` (TURN over TLS on the
 * same 443 front, stealth mode — see design §9.4.1), hiding the client IP from
 * TempTalk's media servers. When absent, only signaling rides the tunnel and
 * media connects directly (legacy behavior).
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
    /** Base64(SHA-256(DER SubjectPublicKeyInfo)) of the proxy's outer-TLS leaf cert. */
    val spkiPinBase64: String,
    /** Optional decoy SNI sent during the OUTER handshake (camouflage only). */
    val sni: String? = null,
    /**
     * coturn `static-auth-secret` (TURN REST API). When non-blank, call media is
     * relayed through the proxy's TURN server using time-limited credentials
     * derived from this secret via [turnCredentials]. Distributed inside the
     * self-hosted share code, never hardcoded into the APK.
     */
    val turnSecret: String? = null,
) {
    /**
     * Non-empty decoy SNI for the OUTER handshake.
     *
     * Returns [sni] when set, else a built-in camouflage hostname — never the IP
     * [host] (an IP literal yields NO SNI extension). A non-empty SNI is required
     * by the server's 443 SNI demux (phase-2 turns:443): the signaling tunnel must
     * carry a non-empty SNI so it routes to the TLS terminator, while `turns:<ip>`
     * media (no SNI) routes to coturn. Harmless on the phase-1 server, which
     * terminates any SNI with its single cert.
     */
    fun outerSni(): String = sni?.takeIf { it.isNotBlank() } ?: DEFAULT_DECOY_SNI

    /** Whether this config carries a usable TURN secret for call-media relay. */
    fun turnEnabled(): Boolean = !turnSecret.isNullOrBlank()

    /**
     * Defensive `toString()` override that masks the two secrets (`spkiPinBase64`
     * and `turnSecret`). Without this, the auto-generated data-class `toString()`
     * would expose them to any future `L.x { "config=$config" }` mishap. The pin
     * prefix is kept (first 8 chars) for diagnostic disambiguation; the TURN
     * secret is reduced to a present/absent indicator.
     */
    override fun toString(): String {
        val pinDigest = if (spkiPinBase64.length > 8) "${spkiPinBase64.take(8)}…" else "***"
        val turnTag = if (turnSecret.isNullOrBlank()) "<none>" else "<set>"
        return "ProxyConfig(host=$host, port=$port, spkiPinPrefix=$pinDigest, sni=$sni, turnSecret=$turnTag)"
    }

    /**
     * Generates time-limited TURN REST credentials (coturn `use-auth-secret`):
     * `username = <unix-expiry>`, `password = base64(HMAC-SHA1(secret, username))`.
     * Returns null when [turnSecret] is absent. The returned credentials stay
     * valid for [ttlSeconds]; coturn validates expiry only at allocation time,
     * so an in-progress call keeps working past expiry.
     */
    fun turnCredentials(ttlSeconds: Long = DEFAULT_TURN_TTL_SECONDS): Pair<String, String>? {
        val secret = turnSecret?.takeIf { it.isNotBlank() } ?: return null
        val expiry = (System.currentTimeMillis() / 1000L) + ttlSeconds
        val username = expiry.toString()
        return runCatching {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val raw = mac.doFinal(username.toByteArray(Charsets.UTF_8))
            username to Base64.encodeToString(raw, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** Serializes back to a PLAIN `ytp://config?d=...` share link (for persistence). */
    fun toShareLink(): String = ProxyLinkCodec.encodePlain(this)

    companion object {
        const val DEFAULT_PORT = 443
        private const val DEFAULT_TURN_TTL_SECONDS = 24L * 60L * 60L

        /**
         * Fallback OUTER-handshake SNI when the share code omits `sni`. Any
         * non-empty, well-formed hostname works for the server's SNI demux; a
         * common one blends with ordinary HTTPS for DPI. Mode B never validates
         * it (pin is on the public key), so the value is cosmetic camouflage.
         */
        const val DEFAULT_DECOY_SNI = "www.bing.com"

        /**
         * Recovers a standard-base64 SPKI pin from a transported value.
         *
         * The pin is compared as **standard** base64 (`Base64.NO_WRAP`, with
         * `+` and `/`) against the runtime-computed digest. Transport along the
         * import path (paste / URL) commonly decodes `+` to a space, and a sender
         * may also have emitted URL-safe base64 (`-` `_`). Since standard base64
         * contains no space / `-` / `_`, mapping them back is lossless and makes
         * the pin survive any of these transports.
         */
        internal fun normalizeBase64Pin(raw: String): String =
            raw.trim()
                .replace(' ', '+')
                .replace('-', '+')
                .replace('_', '/')

        /**
         * Parses a PLAIN (non-encrypted) `ytp://config?d=...` share link. Returns
         * null when malformed, missing required fields, OR passphrase-encrypted
         * (encrypted links require [ProxyLinkCodec.decodeEncrypted] with a
         * passphrase). Used on every process start by [ProxyConfigProvider], which
         * only ever persists plain links.
         */
        fun parse(shareLink: String): ProxyConfig? = ProxyLinkCodec.decodePlain(shareLink)
    }
}
