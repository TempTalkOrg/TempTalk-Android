package com.difft.android.base.migration

/**
 * The few facts about the v2 pairing payload that every module reading a scanned code needs, kept
 * here so `:base` (scan-entry classification, the Desktop link parameter) and `:p2p` (the codec)
 * agree without `:base` depending on the codec.
 *
 * A v2 payload is a binary structure whose first byte is [MAGIC_V2]; on a bare code it travels as
 * [Base45] text, inside a link (the iOS transfer envelope, the Desktop login link) as base64url.
 */
object MigrationQrWire {
    /** First byte of every v2 payload. A different value is a format this build does not read. */
    const val MAGIC_V2: Byte = 0x02

    /** A payload is around a hundred bytes; anything past this is not one. */
    const val MAX_PAYLOAD_BYTES = 512

    /**
     * The shortest legal payload — magic, flags, one address, fingerprint, pin, empty alias — is 43
     * bytes, 65 Base45 characters. Shorter alphanumeric text is a PIN or a code from another feature.
     */
    const val MIN_BARE_TEXT_LENGTH = 65

    /** Flags bit (second byte) marking a code whose displayer holds the data: a Desktop export code. */
    const val FLAG_DOWNLOAD = 0x01

    /** Whether [payload] is a v2 structure whose displayer holds the data (see [FLAG_DOWNLOAD]). */
    fun isDownloadPayload(payload: ByteArray): Boolean =
        payload.size >= 2 && payload[0] == MAGIC_V2 && payload[1].toInt() and FLAG_DOWNLOAD != 0

    /** Line breaks a scanner may append; spaces are part of the alphabet and must stay. */
    fun stripLineBreaks(raw: String): String = raw.trim('\r', '\n')

    /** Shape test for a bare v2 code: long enough and entirely within the Base45 alphabet. */
    fun looksLikeBarePayload(raw: String): Boolean {
        val text = stripLineBreaks(raw)
        return text.length >= MIN_BARE_TEXT_LENGTH && Base45.isAlphabet(text)
    }
}
