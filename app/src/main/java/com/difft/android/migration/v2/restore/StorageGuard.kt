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














class StorageGuard(
    private val volumes: List<File>,
    
    
    private val freeBytes: (File) -> Long? = { file ->
        StatFs(file.absolutePath).let { stats -> stats.availableBytes.takeIf { stats.totalBytes > 0L } }
    },
) {

    data class Verdict(val requiredBytes: Long, val availableBytes: Long) {
        val sufficient: Boolean get() = availableBytes >= requiredBytes
    }

    





    fun precheck(declaredBytes: Long): Verdict = Verdict(
        requiredBytes = if (declaredBytes > 0L) {
            2 * declaredBytes + FIXED_OVERHEAD_BYTES + MARGIN_BYTES
        } else {
            UNKNOWN_SIZE_FLOOR_BYTES
        },
        availableBytes = availableBytes(),
    )

    



    fun availableBytes(): Long =
        volumes.mapNotNull { volume -> runCatching { freeBytes(volume) }.getOrNull() }
            .minOrNull() ?: Long.MAX_VALUE

    



    fun requireHeadroom(where: String) {
        val free = availableBytes()
        if (free < LOW_WATER_BYTES) {
            L.e { "[MigrationV2][Restore] out of space at $where free=${free / MB}MB" }
            throw MigrationException.OutOfSpace("out of space at $where free=${free}B")
        }
    }

    companion object {
        
        const val FIXED_OVERHEAD_BYTES = 128L * 1024 * 1024

        
        const val MARGIN_BYTES = HandoffSpaceCheck.MARGIN_BYTES

        
        const val UNKNOWN_SIZE_FLOOR_BYTES = 512L * 1024 * 1024

        
        const val LOW_WATER_BYTES = MARGIN_BYTES

        private const val MB = 1024L * 1024L

        







        fun forContext(context: Context): StorageGuard = StorageGuard(
            volumes = listOfNotNull(
                context.filesDir,
                runCatching { FilePathManager.getBaseDirFile() }.getOrNull(),
            ),
        )

        




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
