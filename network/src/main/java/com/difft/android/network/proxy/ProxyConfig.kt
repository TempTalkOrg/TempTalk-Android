package com.difft.android.network.proxy

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec














data class ProxyConfig(
    val host: String,
    val port: Int,
    
    val spkiPinBase64: String,
    
    val sni: String? = null,
    





    val turnSecret: String? = null,
    




    val quicEnabled: Boolean = false,
) {
    




    fun outerSni(): String = sni?.takeIf { it.isNotBlank() } ?: DEFAULT_DECOY_SNI

    
    fun turnEnabled(): Boolean = !turnSecret.isNullOrBlank()

    






    override fun toString(): String {
        val pinDigest = if (spkiPinBase64.length > 8) "${spkiPinBase64.take(8)}…" else "***"
        val turnTag = if (turnSecret.isNullOrBlank()) "<none>" else "<set>"
        return "ProxyConfig(host=$host, port=$port, spkiPinPrefix=$pinDigest, sni=$sni, turnSecret=$turnTag)"
    }

    






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

    
    fun toShareLink(): String = ProxyLinkCodec.encodePlain(this)

    companion object {
        const val DEFAULT_PORT = 443
        private const val DEFAULT_TURN_TTL_SECONDS = 24L * 60L * 60L

        





        const val DEFAULT_DECOY_SNI = "www.bing.com"

        









        internal fun normalizeBase64Pin(raw: String): String =
            raw.trim()
                .replace(' ', '+')
                .replace('-', '+')
                .replace('_', '/')

        






        fun parse(shareLink: String): ProxyConfig? = ProxyLinkCodec.decodePlain(shareLink)
    }
}
