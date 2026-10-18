package com.difft.android.p2p.engine.qr

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.MigrationQrWire
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.TransferDirection
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The v2 pairing payload's binary layout — the structure a pairing code carries, as Base45 text on
 * a bare code and as base64url inside a link.
 *
 * ```
 * magic 0x02 · flags · nAddr · addr[nAddr] (IPv4) · [port] · fp (32) · pin (3, BCD) · [transferKey (32)]
 * · alias (len + UTF-8) · [precheck: env (1) · appVersion (len + ASCII) · formatVersion (2) · account (len + UTF-8)]
 * ```
 *
 * Decoding is strict and total: every rule the three platforms agreed on rejects with null, and
 * nothing here throws on foreign bytes. Encoding throws on a payload this build should never have
 * built (a fingerprint that is not 32 bytes, a PIN that is not six digits) — those are bugs, not
 * input. The in-memory [QrPayload] keeps its hex fingerprint and base64 key; only the wire is raw.
 */
object QrPayloadV2Codec {
    const val DEFAULT_PORT = 53317
    const val MAX_ADDRESSES = 4
    const val MAX_ALIAS_BYTES = 24
    const val MAX_ACCOUNT_BYTES = 16
    const val MAX_VERSION_BYTES = 16

    private const val FLAG_DOWNLOAD = MigrationQrWire.FLAG_DOWNLOAD
    private const val FLAG_HAS_KEY = 0x02
    private const val FLAG_HAS_PRECHECK = 0x04
    private const val FLAG_HAS_PORT = 0x08
    private const val KNOWN_FLAGS = FLAG_DOWNLOAD or FLAG_HAS_KEY or FLAG_HAS_PRECHECK or FLAG_HAS_PORT

    private const val FP_BYTES = 32
    private const val KEY_BYTES = 32
    private const val PIN_DIGITS = 6
    private const val PIN_BYTES = PIN_DIGITS / 2
    private const val IPV4_BYTES = 4
    private const val MAX_PORT = 0xFFFF

    /** Environment on the wire: byte 0/1 in the pairing code, name `online`/`test` in JSON. Same on every platform. */
    object Environment {
        const val PROD = "online"
        const val TEST = "test"

        fun toCode(name: String?): Int? = when (name) {
            PROD -> 0
            TEST -> 1
            else -> null
        }

        fun fromCode(code: Int): String? = when (code) {
            0 -> PROD
            1 -> TEST
            else -> null
        }
    }

    fun encode(payload: QrPayload): ByteArray {
        val addresses = payload.candidates.distinct()
        require(addresses.size in 1..MAX_ADDRESSES) { "1..$MAX_ADDRESSES addresses, got ${addresses.size}" }
        val fp = hexToBytes(payload.fp)
        require(fp.size == FP_BYTES) { "fingerprint must be $FP_BYTES bytes" }
        require(payload.pin.length == PIN_DIGITS && payload.pin.all { it in '0'..'9' }) { "pin must be $PIN_DIGITS digits" }
        val key = payload.transferKey?.let { Base64.getDecoder().decode(it) }
        require(key == null || key.size == KEY_BYTES) { "transferKey must be $KEY_BYTES bytes" }
        require(payload.port in 1..MAX_PORT) { "port out of range" }
        val precheck = precheckOf(payload)

        var flags = 0
        if (TransferDirection.of(payload.dir) == TransferDirection.DOWNLOAD) flags = flags or FLAG_DOWNLOAD
        if (key != null) flags = flags or FLAG_HAS_KEY
        if (precheck != null) flags = flags or FLAG_HAS_PRECHECK
        if (payload.port != DEFAULT_PORT) flags = flags or FLAG_HAS_PORT

        val out = ByteArrayOutputStream(160)
        out.write(MigrationQrWire.MAGIC_V2.toInt())
        out.write(flags)
        out.write(addresses.size)
        addresses.forEach { out.write(ipv4ToBytes(it)) }
        if (payload.port != DEFAULT_PORT) {
            out.write(payload.port shr 8)
            out.write(payload.port and 0xFF)
        }
        out.write(fp)
        out.write(bcd(payload.pin))
        if (key != null) out.write(key)
        out.writeLengthPrefixed(truncateUtf8(payload.alias, MAX_ALIAS_BYTES))
        if (precheck != null) {
            out.write(precheck.env)
            out.writeLengthPrefixed(precheck.appVersion)
            out.write(precheck.formatMajor)
            out.write(precheck.formatMinor)
            out.writeLengthPrefixed(precheck.account)
        }
        return out.toByteArray()
    }

    /** Null on any violation of the layout; never throws. */
    fun decode(bytes: ByteArray): QrPayload? = runCatching { decodeStrict(Reader(bytes)) }
        .onFailure { L.w { "[P2P][QR] v2 payload rejected: ${it.message}" } }
        .getOrNull()

    private fun decodeStrict(r: Reader): QrPayload {
        check(r.remaining() <= MigrationQrWire.MAX_PAYLOAD_BYTES) { "too large" }
        check(r.u8() == MigrationQrWire.MAGIC_V2.toInt()) { "magic" }
        val flags = r.u8()
        check(flags and KNOWN_FLAGS.inv() == 0) { "reserved flag" }
        val nAddr = r.u8()
        check(nAddr in 1..MAX_ADDRESSES) { "nAddr=$nAddr" }
        val addresses = List(nAddr) { bytesToIpv4(r.bytes(IPV4_BYTES)) }
        val port = if (flags and FLAG_HAS_PORT != 0) (r.u8() shl 8 or r.u8()).also { check(it != 0) { "port=0" } } else DEFAULT_PORT
        val fp = r.bytes(FP_BYTES).toHex()
        val pin = unbcd(r.bytes(PIN_BYTES))
        val key = if (flags and FLAG_HAS_KEY != 0) Base64.getEncoder().encodeToString(r.bytes(KEY_BYTES)) else null
        val alias = r.utf8(MAX_ALIAS_BYTES, "alias")
        var environment: String? = null
        var appVersion: String? = null
        var formatVersion: String? = null
        var account: String? = null
        if (flags and FLAG_HAS_PRECHECK != 0) {
            environment = checkNotNull(Environment.fromCode(r.u8())) { "env" }
            appVersion = r.ascii(MAX_VERSION_BYTES, "appVersion")
            formatVersion = "${r.u8()}.${r.u8()}"
            account = r.utf8(MAX_ACCOUNT_BYTES, "account").ifEmpty { null }
        }
        check(r.remaining() == 0) { "trailing bytes=${r.remaining()}" }
        return QrPayload(
            v = 2,
            ip = addresses.first(),
            port = port,
            fp = fp,
            pin = pin,
            alias = alias,
            transferKey = key,
            environment = environment,
            account = account,
            appVersion = appVersion,
            formatVersion = formatVersion,
            dir = if (flags and FLAG_DOWNLOAD != 0) TransferDirection.DOWNLOAD.wire else null,
            ips = addresses,
        )
    }

    private class Precheck(val env: Int, val appVersion: ByteArray, val formatMajor: Int, val formatMinor: Int, val account: ByteArray)

    /**
     * The pre-check block, or null when the payload does not carry a complete identity (the byte-pipe
     * path, the Desktop login link, an environment this codec cannot name). A partial identity is
     * dropped whole rather than written half: the connecting side treats "no block" as "check over
     * the connection", which is always right.
     */
    private fun precheckOf(p: QrPayload): Precheck? {
        val env = Environment.toCode(p.environment) ?: return null
        val version = p.appVersion?.takeIf { it.isNotBlank() && it.all { c -> c.code in 0x20..0x7E } } ?: return null
        val format = p.formatVersion?.split('.')?.takeIf { it.size == 2 }?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 2 && it.all { n -> n in 0..0xFF } } ?: return null
        val account = truncateUtf8(p.account.orEmpty(), MAX_ACCOUNT_BYTES)
        return Precheck(env, version.toByteArray(Charsets.US_ASCII).take(MAX_VERSION_BYTES).toByteArray(), format[0], format[1], account)
    }

    private fun ByteArrayOutputStream.writeLengthPrefixed(bytes: ByteArray) {
        write(bytes.size)
        write(bytes)
    }

    /** UTF-8 of [text] cut to at most [max] bytes on a character boundary. */
    private fun truncateUtf8(text: String, max: Int): ByteArray {
        val sb = StringBuilder()
        var size = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val width = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (size + width > max) break
            sb.appendCodePoint(cp)
            size += width
            i += n
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun bcd(pin: String): ByteArray = ByteArray(PIN_BYTES) { i ->
        ((pin[2 * i] - '0') shl 4 or (pin[2 * i + 1] - '0')).toByte()
    }

    private fun unbcd(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            val hi = (b.toInt() shr 4) and 0x0F
            val lo = b.toInt() and 0x0F
            check(hi <= 9 && lo <= 9) { "pin nibble" }
            append('0' + hi).append('0' + lo)
        }
    }

    private fun ipv4ToBytes(ip: String): ByteArray {
        val parts = ip.trim().split('.')
        require(parts.size == IPV4_BYTES) { "not an IPv4 address" }
        return ByteArray(IPV4_BYTES) { i ->
            val n = parts[i].toIntOrNull()
            require(n != null && n in 0..0xFF) { "not an IPv4 address" }
            n.toByte()
        }
    }

    private fun bytesToIpv4(b: ByteArray): String = b.joinToString(".") { (it.toInt() and 0xFF).toString() }

    /** Accepts plain or colon-separated hex, either case. */
    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it != ':' && !it.isWhitespace() }
        require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "not hex" }
        return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun remaining(): Int = bytes.size - pos

        fun u8(): Int {
            check(pos < bytes.size) { "truncated" }
            return bytes[pos++].toInt() and 0xFF
        }

        fun bytes(n: Int): ByteArray {
            check(pos + n <= bytes.size) { "truncated" }
            return bytes.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun utf8(max: Int, field: String): String {
            val len = u8()
            check(len <= max) { "$field length=$len" }
            val raw = bytes(len)
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw)).toString()
            } catch (e: CharacterCodingException) {
                throw IllegalStateException("$field utf8", e)
            }
        }

        fun ascii(max: Int, field: String): String {
            val len = u8()
            check(len in 1..max) { "$field length=$len" }
            val raw = bytes(len)
            check(raw.all { it.toInt() in 0x20..0x7E }) { "$field ascii" }
            return String(raw, Charsets.US_ASCII)
        }
    }
}
