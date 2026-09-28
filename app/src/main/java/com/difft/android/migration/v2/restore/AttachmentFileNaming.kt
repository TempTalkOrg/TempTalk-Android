package com.difft.android.migration.v2.restore

import com.difft.android.base.log.lumberjack.L

/**
 * How a migrated attachment's name becomes an on-disk path component — one definition, shared by every
 * restore path so they cannot drift.
 *
 * The rule is preserve, do not rewrite: the file must land under the exact name the record carries,
 * because every reader derives the on-disk path from `AttachmentModel.fileName`, which the importer
 * stores verbatim. Scrubbing anything outside `[A-Za-z0-9._-]` renames every non-ASCII attachment, and
 * the bytes are then on disk where no reader can find them: the attachment presents as not downloaded
 * and falls back to a re-download whose server pointer has long expired.
 */
internal object AttachmentFileNaming {

    /**
     * True for characters that cannot appear in a single path component on Android external storage:
     * the path separators, NUL, and the FAT-derived rejects. Deliberately NOT a general "non-ASCII"
     * filter — CJK, spaces and parentheses are all legal and must survive untouched.
     */
    private fun isUnusable(c: Char): Boolean =
        c == '/' || c == '\\' || c.code == 0 || c in ":*?\"<>|"

    /** True when [raw] is already a usable path component — [asPathComponent] would return it unchanged. */
    fun isUsable(raw: String): Boolean = asPathComponentQuietly(raw) == raw

    /**
     * [raw] made usable as one path component, or null when it cannot be one at all (blank, or
     * reducing to a directory reference — `File(dir, "")` resolves to the directory itself and
     * `FileOutputStream(dir)` then fails with an opaque error, so callers reject it up front).
     *
     * A substitution is logged: it is the one remaining case where the on-disk name and the DB name
     * diverge, and it never happens for a normal file name.
     */
    fun asPathComponent(raw: String): String? {
        val cleaned = asPathComponentQuietly(raw) ?: return null
        if (cleaned != raw) {
            // Name-only diagnostic (never the content).
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
