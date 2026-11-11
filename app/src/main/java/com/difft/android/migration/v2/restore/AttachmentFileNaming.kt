package com.difft.android.migration.v2.restore

import com.difft.android.base.log.lumberjack.L











internal object AttachmentFileNaming {

    




    private fun isUnusable(c: Char): Boolean =
        c == '/' || c == '\\' || c.code == 0 || c in ":*?\"<>|"

    
    fun isUsable(raw: String): Boolean = asPathComponentQuietly(raw) == raw

    







    fun asPathComponent(raw: String): String? {
        val cleaned = asPathComponentQuietly(raw) ?: return null
        if (cleaned != raw) {
            
            L.w { "[MigrationV2][Restore] attachment name had unusable characters, substituted" }
        }
        return cleaned
    }

    private fun asPathComponentQuietly(raw: String): String? {
        if (raw.isBlank()) return null
        val cleaned = raw.map { if (isUnusable(it)) '_' else it }.joinToString("").trim()
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return null
        return cleaned
    }
}
