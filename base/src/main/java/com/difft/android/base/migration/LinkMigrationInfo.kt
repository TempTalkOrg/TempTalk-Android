package com.difft.android.base.migration

import com.difft.android.base.log.lumberjack.L
import java.util.Base64










object LinkMigrationInfo {
    
    const val QUERY_KEY = "dataMigrationInfo"

    




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

    
    const val STATE_QUERY_KEY = "dataMigrationState"

    
    fun parseState(raw: String?): LinkMigrationState? =
        LinkMigrationState.entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
}





enum class LinkMigrationState(val wire: String) {
    




    UNAVAILABLE("unavailable"),
}
