package com.difft.android.base.security

import androidx.core.net.toUri
import java.net.IDN




enum class LinkRisk {
    
    SAFE,

    



    HOMOGRAPH,

    





    NON_ASCII_HOST,

    



    DECEPTIVE_USERINFO,
}










data class LinkVerdict(
    val risk: LinkRisk,
    val displayHost: String,
    val realAsciiHost: String,
    val safeDisplayUrl: String,
) {
    val isSafe: Boolean get() = risk == LinkRisk.SAFE
}

























object LinkSafetyInspector {

    private const val ACE_PREFIX = "xn--"

    
    
    private val CJK_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO,
    )

    fun inspect(url: String): LinkVerdict {
        val uri = runCatching { url.toUri() }.getOrNull()
        val rawHost = uri?.host?.takeIf { it.isNotEmpty() }
            ?: return LinkVerdict(LinkRisk.SAFE, "", "", url)

        
        
        val displayHost = decodeToDisplayHost(rawHost)
        val asciiHost = runCatching { IDN.toASCII(rawHost) }
            .recoverCatching { IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED) }
            .getOrDefault(rawHost)
        val safeUrl = buildAsciiUrl(uri, asciiHost)

        
        if (!uri.userInfo.isNullOrEmpty()) {
            return LinkVerdict(LinkRisk.DECEPTIVE_USERINFO, displayHost, asciiHost, safeUrl)
        }

        
        val isInternationalized = !displayHost.isAscii() || hasAceLabel(displayHost) || hasAceLabel(asciiHost)
        if (!isInternationalized) {
            return LinkVerdict(LinkRisk.SAFE, displayHost, asciiHost, safeUrl)
        }

        
        val hasDisallowedMix = displayHost.split('.').any { isDisallowedMixedScript(it) }
        val risk = if (hasDisallowedMix) LinkRisk.HOMOGRAPH else LinkRisk.NON_ASCII_HOST
        return LinkVerdict(risk, displayHost, asciiHost, safeUrl)
    }

    





    private fun buildAsciiUrl(uri: android.net.Uri, asciiHost: String): String {
        val builder = StringBuilder()
        uri.scheme?.let { builder.append(it).append("://") }
        builder.append(asciiHost)
        uri.port.takeIf { it != -1 }?.let { builder.append(':').append(it) }
        uri.encodedPath?.let { builder.append(it) }
        uri.encodedQuery?.let { builder.append('?').append(it) }
        uri.encodedFragment?.let { builder.append('#').append(it) }
        return builder.toString()
    }

    private fun hasAceLabel(host: String): Boolean =
        host.split('.').any { it.startsWith(ACE_PREFIX, ignoreCase = true) }

    




    private fun decodeToDisplayHost(host: String): String {
        val viaIdn = runCatching { IDN.toUnicode(host) }.getOrDefault(host)
        if (!hasAceLabel(viaIdn)) return viaIdn
        return viaIdn.split('.').joinToString(".") { label ->
            if (!label.startsWith(ACE_PREFIX, ignoreCase = true)) {
                label
            } else {
                runCatching { Punycode.decode(label.substring(ACE_PREFIX.length)) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() && !it.isAscii() }
                    ?: label
            }
        }
    }

    




    private fun isDisallowedMixedScript(label: String): Boolean {
        val scripts = mutableSetOf<Character.UnicodeScript>()
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue 
            val script = runCatching { Character.UnicodeScript.of(cp) }.getOrNull() ?: continue
            if (script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED) continue
            scripts.add(script)
        }
        if (scripts.size <= 1) return false
        return !scripts.all { it == Character.UnicodeScript.LATIN || it in CJK_SCRIPTS }
    }

    private fun CharSequence.isAscii(): Boolean = all { it.code < 0x80 }
}
