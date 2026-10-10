package com.difft.android.migration.v2.restore

import android.content.Context
import android.os.StatFs
import android.system.ErrnoException
import android.system.OsConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FilePathManager
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.handoff.HandoffSpaceCheck
import java.io.File

/**
 * The receiver's free-space rule for a network migration: one pre-check at `prepare-upload` and a
 * cheap headroom probe the receive and import paths call as they go.
 *
 * Distinct from [HandoffSpaceCheck], which answers the *sender's* same-device question from an
 * [com.difft.android.migration.v2.export.ExportEstimate]. This answers the *receiver's* question
 * from a declared wire size. Only [MARGIN_BYTES] is shared, so both paths reserve the same working
 * room for the staging-vs-real overlap during promote; the formulas are otherwise separate.
 *
 * @param volumes every volume the restore writes to. The tightest one decides, because the spool and
 *   the attachments land on the attachment mount while the staging database stays under `filesDir`,
 *   and those can be different filesystems.
 */
class StorageGuard(
    private val volumes: List<File>,
    // A mount that reports no capacity at all is not a real volume (an unmounted path, or a
    // stubbed StatFs); it must not read as "0 bytes free".
    private val freeBytes: (File) -> Long? = { file ->
        StatFs(file.absolutePath).let { stats -> stats.availableBytes.takeIf { stats.totalBytes > 0L } }
    },
) {

    data class Verdict(val requiredBytes: Long, val availableBytes: Long) {
        val sufficient: Boolean get() = availableBytes >= requiredBytes
    }

    /**
     * The `prepare-upload` pre-check.
     *
     * @param declaredBytes the sum of the declared file sizes; 0 when the sender does not know it,
     *   in which case only a flat floor can be required.
     */
    fun precheck(declaredBytes: Long): Verdict = Verdict(
        requiredBytes = if (declaredBytes > 0L) {
            2 * declaredBytes + FIXED_OVERHEAD_BYTES + MARGIN_BYTES
        } else {
            UNKNOWN_SIZE_FLOOR_BYTES
        },
        availableBytes = availableBytes(),
    )

    /**
     * Free space on the tightest volume. A volume that cannot be measured is skipped rather than
     * treated as full: a probe failure must not fail a transfer that would have fit.
     */
    fun availableBytes(): Long =
        volumes.mapNotNull { volume -> runCatching { freeBytes(volume) }.getOrNull() }
            .minOrNull() ?: Long.MAX_VALUE

    /**
     * Throws once headroom is gone, so the failure is a typed 403 rather than an opaque ENOSPC from
     * whichever write happened to run out first. Cheap enough to call on a throttle.
     */
    fun requireHeadroom(where: String) {
        val free = availableBytes()
        if (free < LOW_WATER_BYTES) {
            L.e { "[MigrationV2][Restore] out of space at $where free=${free / MB}MB" }
            throw MigrationException.OutOfSpace("out of space at $where free=${free}B")
        }
    }

    companion object {
        /** Database, WAL, indexes and temp files produced while importing. */
        const val FIXED_OVERHEAD_BYTES = 128L * 1024 * 1024

        /** Promote's staging-vs-real overlap: the same figure the same-device pre-check reserves. */
        const val MARGIN_BYTES = HandoffSpaceCheck.MARGIN_BYTES

        /** `size = 0` (the sender does not know): the flat floor a transfer must clear. */
        const val UNKNOWN_SIZE_FLOOR_BYTES = 512L * 1024 * 1024

        /** Below this, monitoring trips before a write has to discover ENOSPC the hard way. */
        const val LOW_WATER_BYTES = MARGIN_BYTES

        private const val MB = 1024L * 1024L

        /**
         * Production volumes: the attachment mount (spool + attachments) and `filesDir` (staging DB).
         *
         * The attachment root is resolved best-effort. It is initialized at app start, and a caller
         * that reaches this before then — or a host that has no external files mount — gets a guard
         * over `filesDir` alone rather than a thrown restore, which matches [availableBytes]'s rule
         * that an unmeasurable volume is skipped, not treated as full.
         */
        fun forContext(context: Context): StorageGuard = StorageGuard(
            volumes = listOfNotNull(
                context.filesDir,
                runCatching { FilePathManager.getBaseDirFile() }.getOrNull(),
            ),
        )

        /**
         * Whether [t], or anything it wraps, is an out-of-space failure. The cause chain is walked
         * because both `FileOutputStream` and WCDB wrap, and WCDB surfaces `SQLITE_FULL` as a message
         * rather than an errno.
         */
        fun isOutOfSpace(t: Throwable): Boolean =
            generateSequence(t, Throwable::cause).any { e ->
                (e is ErrnoException && e.errno == OsConstants.ENOSPC) ||
                    e.message?.let { m -> OUT_OF_SPACE_MARKERS.any { m.contains(it, ignoreCase = true) } } == true
            }

        private val OUT_OF_SPACE_MARKERS = listOf(
            "enospc",
            "no space left",
            "not enough space",
            "insufficient space",
            "disk is full",
            "database or disk is full",
        )
    }
}
