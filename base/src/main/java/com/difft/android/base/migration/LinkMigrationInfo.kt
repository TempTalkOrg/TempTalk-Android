package com.difft.android.base.migration

import com.difft.android.base.log.lumberjack.L
import java.util.Base64

/**
 * Migration pairing info a Desktop link-login QR may carry in the `dataMigrationInfo` query
 * parameter: base64url (unpadded) of the same v2 pairing payload a migration QR carries, minus the
 * pre-check block — `link-precheck` over the connection is authoritative, so the phones never read
 * `env` / `account` from this code.
 *
 * Anything wrong with the parameter degrades silently to "no migration info"; link login must never
 * fail because of it. Never logs the parameter or its contents: it carries the PIN and transfer key.
 */
object LinkMigrationInfo {
    /** Query key on the `tsdevice:/` link url. */
    const val QUERY_KEY = "dataMigrationInfo"

    /**
     * Decodes [encoded] to the payload's bare-code text form ([Base45]), or null when the link
     * carries no usable migration info. Only the transport wrapping is checked here; the codec
     * validates the structure.
     */
    fun decode(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(encoded.trim()) }.getOrElse {
            L.w { "[LinkMigrationInfo] parameter is not base64url — ignoring migration info" }
            return null
        }
        if (bytes.isEmpty() || bytes.size > MigrationQrWire.MAX_PAYLOAD_BYTES) {
            L.w { "[LinkMigrationInfo] parameter size=${bytes.size} out of range — ignoring migration info" }
            return null
        }
        if (bytes[0] != MigrationQrWire.MAGIC_V2) {
            L.w { "[LinkMigrationInfo] parameter is not a v2 payload — ignoring migration info" }
            return null
        }
        L.i { "[LinkMigrationInfo] link carries a v2 pairing payload bytes=${bytes.size}" }
        return Base45.encode(bytes)
    }

    /** Query key a plain link url carries instead of pairing info. */
    const val STATE_QUERY_KEY = "dataMigrationState"

    /** Parses `dataMigrationState`; anything unrecognised is treated as absent (an older Desktop). */
    fun parseState(raw: String?): LinkMigrationState? =
        LinkMigrationState.entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
}

/**
 * Why a Desktop showed a plain link QR instead of a compound one. A Desktop too old to send this
 * shows a plain link QR with no state at all.
 */
enum class LinkMigrationState(val wire: String) {
    /**
     * The Desktop is current but could not bring up its receiver (no LAN address, port taken,
     * certificate failure). Whether the Desktop holds history is answered by `link-precheck`, not
     * by the code.
     */
    UNAVAILABLE("unavailable"),
}
