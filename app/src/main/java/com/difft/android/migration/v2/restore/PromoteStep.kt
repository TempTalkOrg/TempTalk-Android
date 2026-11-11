package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDBKeyManager
import com.difft.android.base.log.WCDBKeyUnavailableException
import java.io.File


























class PromoteStep(
    private val context: Context,
    private val markerStore: PromoteMarkerStore = PromoteMarkerStore(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    



    private val dirLister: (File) -> Array<File>? = { it.listFiles() },
) {

    




    sealed interface ResumeResult {
        
        data object NothingToResume : ResumeResult

        
        data object ResumedWithData : ResumeResult

        



        data object DiscardedToCleanState : ResumeResult
    }

    















    suspend fun promote(
        stagingDbPath: String,
        stagingKey: ByteArray?,
        attachmentsStagingDir: String,
        realDbPath: String,
        realAttachmentDir: String,
    ) = withContext(ioDispatcher) {
        
        
        val staging = File(stagingDbPath)
        if (!staging.exists()) {
            throw MigrationException.RestoreFailed("staging db missing at promote: $stagingDbPath")
        }

        
        val marker = PromoteMarker.create(
            stagingDbPath = stagingDbPath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attachmentsStagingDir,
            realDbPath = realDbPath,
            realAttachmentDir = realAttachmentDir,
        )
        markerStore.write(marker)
        L.i { "[MigrationV2][Promote] P0 marker written; begin promote" }

        runPhases(marker)
    }

    









    suspend fun resumeIfPending(): ResumeResult = withContext(ioDispatcher) {
        val marker = when (val r = markerStore.readChecked()) {
            PromoteMarkerStore.ReadResult.Absent -> return@withContext ResumeResult.NothingToResume
            is PromoteMarkerStore.ReadResult.Present -> r.marker
            PromoteMarkerStore.ReadResult.Corrupt -> {
                
                
                
                
                
                L.e { "[MigrationV2][Promote] resume: corrupt marker → discard to clean net-install state" }
                markerStore.clear()
                return@withContext ResumeResult.DiscardedToCleanState
            }
        }
        L.i { "[MigrationV2][Promote] resumeIfPending: marker found, re-doing P1..P4" }

        
        
        
        
        
        
        
        val stagingDbGone = !File(marker.stagingDbPath).exists()
        val stagingAttachmentsGone = !File(marker.attachmentsStagingDir).exists()
        if (stagingDbGone && stagingAttachmentsGone) {
            
            
            
            
            
            L.w { "[MigrationV2][Promote] resume: staging products gone → discard half-promote (clean net-install)" }
            markerStore.clear()
            return@withContext ResumeResult.DiscardedToCleanState
        }

        runPhases(marker)
        ResumeResult.ResumedWithData
    }

    



    private fun runPhases(marker: PromoteMarker) {
        
        
        
        
        
        
        
        
        val stagingKey = try {
            marker.stagingKey()
        } catch (e: WCDBKeyUnavailableException) {
            L.e {
                "[MigrationV2][Promote] P1 staging key unrecoverable (Keystore alias invalidated) → " +
                    "abort to clean net-install state: ${e.stackTraceToString()}"
            }
            
            
            discardStagingProducts(marker)
            markerStore.clear()
            throw MigrationException.RestoreAbortedCleanState(
                "promote aborted: staging key unrecoverable; fell back to clean net-install state", e
            )
        }

        
        
        if (stagingKey != null) {
            try {
                WCDBKeyManager.installKey(context, stagingKey)
            } catch (e: Throwable) {
                L.e { "[MigrationV2][Promote] P1 installKey failed: ${e.stackTraceToString()}" }
                throw MigrationException.RestoreFailed("P1 installKey failed", e)
            }
            L.i { "[MigrationV2][Promote] P1 key installed" }
        } else {
            L.i { "[MigrationV2][Promote] P1 no staging key (plaintext debug build) — skip key install" }
        }

        
        moveDatabaseFile(marker.stagingDbPath, marker.realDbPath)
        L.i { "[MigrationV2][Promote] P2 db file in place" }

        
        moveAttachments(marker.attachmentsStagingDir, marker.realAttachmentDir)
        L.i { "[MigrationV2][Promote] P3 attachments in place" }

        
        
        markerStore.clear()
        L.i { "[MigrationV2][Promote] P4 marker cleared; promote complete" }
    }

    




    private fun discardStagingProducts(marker: PromoteMarker) {
        StagingDatabase.deleteStagingArtifacts(marker.stagingDbPath)
        runCatching { File(marker.attachmentsStagingDir).deleteRecursively() }
        L.i { "[MigrationV2][Promote] staging products discarded after unrecoverable abort" }
    }

    




    private fun moveDatabaseFile(stagingDbPath: String, realDbPath: String) {
        val realDir = File(realDbPath).parentFile
        runCatching { realDir?.mkdirs() }

        val mainStaging = File(stagingDbPath)
        val mainReal = File(realDbPath)
        if (mainStaging.exists()) {
            
            
            LIVE_SIDECAR_SUFFIXES.forEach { suffix ->
                val stale = File(realDbPath + suffix)
                if (stale.exists() && !stale.delete()) {
                    throw MigrationException.RestoreFailed("P2 could not remove stale live sidecar $suffix")
                }
            }
            if (!atomicMove(mainStaging, mainReal)) {
                throw MigrationException.RestoreFailed("P2 move db failed $stagingDbPath → $realDbPath")
            }
        } else if (!mainReal.exists()) {
            
            throw MigrationException.RestoreFailed("P2 db file missing at both staging and real paths")
        }

        
        
        
        
        STAGING_SIDECAR_SUFFIXES.forEach { suffix ->
            val s = File(stagingDbPath + suffix)
            if (s.exists()) {
                val dst = File(realDbPath + suffix)
                if (!atomicMove(s, dst)) {
                    L.e { "[MigrationV2][Promote] P2 sidecar move failed suffix=$suffix; keep marker for retry" }
                    throw MigrationException.RestoreFailed(
                        "P2 sidecar move failed suffix=$suffix; DB may be row-incomplete, keep marker for retry"
                    )
                }
            }
        }
    }

    






    private fun moveAttachments(stagingDirPath: String, realDirPath: String) {
        val stagingDir = File(stagingDirPath)
        if (!stagingDir.exists()) {
            
            return
        }
        val realDir = File(realDirPath).apply { mkdirs() }
        moveTree(stagingDir, realDir)
        
        runCatching { stagingDir.deleteRecursively() }
    }

    



    private fun moveTree(srcDir: File, dstDir: File) {
        
        
        
        val children = dirLister(srcDir)
            ?: throw MigrationException.RestoreFailed(
                "P3 listFiles returned null for existing staging dir ${srcDir.absolutePath} (I/O error); keep marker for retry"
            )
        children.forEach { child ->
            val dst = File(dstDir, child.name)
            if (child.isDirectory) {
                
                
                dst.apply { mkdirs() }
                moveTree(child, dst)
                runCatching { child.delete() } 
            } else if (dst.exists()) {
                
                runCatching { child.delete() }
            } else if (!atomicMove(child, dst)) {
                
                
                L.e { "[MigrationV2][Promote] P3 attachment move AND copy both failed name=${child.forLog()}; keep marker for retry" }
                throw MigrationException.RestoreFailed(
                    "P3 attachment move failed name=${child.forLog()}; keep marker for retry"
                )
            }
        }
    }

    



    private fun File.forLog(): String = if (isDirectory) name else "file(*.$extension, nameLen=${name.length})"

    



    private fun atomicMove(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        return try {
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = true)
                
                
                if (!src.deleteRecursively()) {
                    L.w { "[MigrationV2][Promote] move fallback: src dir delete failed (copy ok) name=${src.forLog()}" }
                }
            } else {
                src.copyTo(dst, overwrite = true)
                
                
                if (dst.length() != src.length()) {
                    L.e {
                        "[MigrationV2][Promote] move fallback copy truncated name=${src.forLog()} " +
                            "srcLen=${src.length()} dstLen=${dst.length()}"
                    }
                    runCatching { dst.delete() } 
                    return false
                }
                
                if (!src.delete()) {
                    L.w { "[MigrationV2][Promote] move fallback: src delete failed (copy ok) name=${src.forLog()}" }
                }
            }
            true
        } catch (e: Throwable) {
            L.e { "[MigrationV2][Promote] move fallback failed ${src.name}: ${e.stackTraceToString()}" }
            false
        }
    }

    companion object {
        









        fun isDatabasePromoted(stagingDbPath: String, realDbPath: String): Boolean =
            !File(stagingDbPath).exists() && File(realDbPath).exists()

        
        private val STAGING_SIDECAR_SUFFIXES = listOf("-wal", "-shm")

        
        private val LIVE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal", "-first.material", "-last.material", "-incremental.material")
    }
}
