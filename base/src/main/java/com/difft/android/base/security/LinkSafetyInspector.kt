package com.difft.android.base.security

import androidx.core.net.toUri
import java.net.IDN

/**
 * Risk classification for an outbound link, produced by [LinkSafetyInspector].
 */
enum class LinkRisk {
    /** Plain ASCII host, no spoofing signal. Safe to open directly. */
    SAFE,

    /**
     * A single host label mixes scripts in a combination used for spoofing
     * (e.g. Latin + Cyrillic "аpple"). High-confidence impersonation.
     */
    HOMOGRAPH,

    /**
     * The host contains non-ASCII characters but no disallowed script mixing.
     * It may be a legitimate internationalized domain, or a whole-script
     * look-alike (e.g. all-Cyrillic "аррӏе"). We cannot tell them apart without
     * Unicode confusable data, so we ask the user to verify the real host.
     */
    NON_ASCII_HOST,

    /**
     * The URL embeds user-info (`https://apple.com@evil.com`); the visible prefix
     * does not match the host the browser will actually navigate to.
     */
    DECEPTIVE_USERINFO,
}

/**
 * Result of inspecting a link.
 *
 * @property displayHost    the Unicode form of the host — what the user visually reads.
 * @property realAsciiHost  the punycode/ASCII form — the host the browser resolves to.
 * @property safeDisplayUrl the original URL with its host normalised to [realAsciiHost]
 *                          and any deceptive `userinfo` prefix stripped — this is the
 *                          real destination to surface to the user before opening.
 */
data class LinkVerdict(
    val risk: LinkRisk,
    val displayHost: String,
    val realAsciiHost: String,
    val safeDisplayUrl: String,
) {
    val isSafe: Boolean get() = risk == LinkRisk.SAFE
}

/**
 * Detects deceptive (homograph / phishing) links before they are opened.
 *
 * Implementation uses only the JDK: [java.net.IDN] for punycode/Unicode
 * conversion and [Character.UnicodeScript] for per-label script analysis. It
 * deliberately does NOT use `android.icu.text.SpoofChecker`, which is `@hide`
 * in the public Android SDK (non-SDK/greylisted interface).
 *
 * Classification:
 * - A label that mixes scripts in a spoofing combination (anything other than
 *   Latin combined with CJK) → [LinkRisk.HOMOGRAPH].
 * - Any other non-ASCII host, or any undecoded `xn--` (ACE) label →
 *   [LinkRisk.NON_ASCII_HOST] (confirm before open; without Unicode confusable
 *   data we cannot prove a single-script IDN is safe).
 * - `userinfo@host` → [LinkRisk.DECEPTIVE_USERINFO].
 *
 * Note on punycode: Android's [IDN.toUnicode] is ICU-backed and "never fails —
 * on any error it returns the input unmodified", so it may leave a valid `xn--`
 * label undecoded. We therefore never rely on decoding alone: the presence of an
 * ACE label is itself treated as a non-ASCII host. When decoding does succeed we
 * additionally refine the verdict to [LinkRisk.HOMOGRAPH] via script analysis.
 *
 * Stateless and thread-safe.
 */
object LinkSafetyInspector {

    private const val ACE_PREFIX = "xn--"

    // Scripts that legitimately co-occur (with each other and with Latin) in real
    // domains — CJK. Any other cross-script mixing within one label is a red flag.
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

        // android.net.Uri does NOT perform IDNA, so rawHost may be either the raw
        // Unicode form or a punycode literal. Normalise both directions explicitly.
        val displayHost = decodeToDisplayHost(rawHost)
        val asciiHost = runCatching { IDN.toASCII(rawHost) }
            .recoverCatching { IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED) }
            .getOrDefault(rawHost)
        val safeUrl = buildAsciiUrl(uri, asciiHost)

        // https://apple.com@evil.com — visible prefix != resolved host.
        if (!uri.userInfo.isNullOrEmpty()) {
            return LinkVerdict(LinkRisk.DECEPTIVE_USERINFO, displayHost, asciiHost, safeUrl)
        }

        // Detect ACE labels on either form; toUnicode may have failed to decode.
        val isInternationalized = !displayHost.isAscii() || hasAceLabel(displayHost) || hasAceLabel(asciiHost)
        if (!isInternationalized) {
            return LinkVerdict(LinkRisk.SAFE, displayHost, asciiHost, safeUrl)
        }

        // Evaluate per label: e.g. `例え.аpple.com` must still flag the `аpple` label.
        val hasDisallowedMix = displayHost.split('.').any { isDisallowedMixedScript(it) }
        val risk = if (hasDisallowedMix) LinkRisk.HOMOGRAPH else LinkRisk.NON_ASCII_HOST
        return LinkVerdict(risk, displayHost, asciiHost, safeUrl)
    }

    /**
     * Rebuilds the URL using the resolved ASCII/punycode [asciiHost], preserving
     * scheme, port, path, query and fragment but dropping any `userinfo` prefix.
     * This is the concrete address the browser will navigate to, shown to the user
     * so a Unicode homograph cannot masquerade as the legitimate site.
     */
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

    /**
     * Best-effort Unicode form of the host for display. Prefers [IDN.toUnicode];
     * falls back to a manual [Punycode] decode for any `xn--` label the platform
     * left undecoded (Android's IDN.toUnicode can return ACE unchanged).
     */
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

    /**
     * True when a label mixes scripts in a combination not seen in legitimate
     * domains. Latin + CJK is allowed; everything else (e.g. Latin + Cyrillic,
     * Cyrillic + Greek, Han + Cyrillic) is treated as a homograph attack.
     */
    private fun isDisallowedMixedScript(label: String): Boolean {
        val scripts = mutableSetOf<Character.UnicodeScript>()
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue // digits/hyphens/marks are script-neutral
            val script = runCatching { Character.UnicodeScript.of(cp) }.getOrNull() ?: continue
            if (script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED) continue
            scripts.add(script)
        }
        if (scripts.size <= 1) return false
        return !scripts.all { it == Character.UnicodeScript.LATIN || it in CJK_SCRIPTS }
    }

    private fun CharSequence.isAscii(): Boolean = all { it.code < 0x80 }
}
