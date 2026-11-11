package com.difft.android.chat.attachment.migration

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.tencent.wcdb.winq.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.wcdb
import java.io.File







internal class NormalAttachmentStages(
    private val state: AttachmentMigrationState,
    private val rows: RowFileMigrator
) {

    









    suspend fun migrateNormalFiles(): Boolean = withContext(Dispatchers.IO) {
        var cursor = state.watermark()
        L.i { "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_NORMAL_MIGRATED} started watermark=$cursor" }
        val referenceCounts = legacyDirectoryReferenceCounts()
        var rowCount = 0
        var renamed = 0
        var copied = 0
        var skipped = 0
        var failed = 0
        while (true) {
            val page = wcdb.attachment.getAllObjects(
                ForwardAttachmentMigration.MIGRATION_FIELDS,
                normalRowsAfter(cursor),
                DBAttachmentModel.databaseId.order(Order.Asc),
                ForwardAttachmentMigration.BATCH_SIZE
            )
            if (page.isEmpty()) break
            page.forEach { row ->
                rowCount++
                when (migrateRow(row, referenceCounts)) {
                    NormalRowOutcome.RENAMED -> renamed++
                    NormalRowOutcome.COPIED -> copied++
                    NormalRowOutcome.SETTLED -> skipped++
                    NormalRowOutcome.FAILED -> failed++
                }
            }
            cursor = page.last().databaseId
            
            
            
            if (failed == 0) state.advanceWatermark(cursor)
            yield()
        }
        L.i {
            "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_NORMAL_MIGRATED} completed " +
                "rows=$rowCount renamed=$renamed copied=$copied skipped=$skipped failed=$failed"
        }
        if (failed == 0) return@withContext true
        
        
        
        
        
        
        
        
        
        
        
        
        
        val attempts = state.recordNormalAttempt()
        if (attempts >= MAX_NORMAL_MIGRATION_ATTEMPTS) {
            L.w {
                "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_NORMAL_MIGRATED} " +
                    "giving up on $failed unmovable rows after $attempts passes"
            }
            return@withContext true
        }
        false
    }

    




















    suspend fun finalCleanup(): Boolean = withContext(Dispatchers.IO) {
        L.i { "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_FINAL_CLEANUP} started" }
        val attachmentRoot = File(FileUtil.getFilePath(FileUtil.FILE_DIR_ATTACHMENT))
        var swept = 0
        var kept = 0
        legacyMessageDirectoryKeys().forEachIndexed { index, messageId ->
            val legacyDirectory = File(attachmentRoot, messageId)
            if (legacyDirectory.isDirectory) {
                if (sweepOne(messageId, legacyDirectory)) swept++ else kept++
            }
            if (index % ForwardAttachmentMigration.BATCH_SIZE.toInt() == 0) yield()
        }

        val before = attachmentRoot.listFiles()?.count { it.isDirectory } ?: 0
        FileUtil.deleteMessageAttachmentEmptyDirectories()
        val remaining = attachmentRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()

        val liveKeys = wcdb.attachment
            .getOneColumnString(DBAttachmentModel.localId)
            .asSequence()
            .filter { !it.isNullOrEmpty() }
            .toHashSet()
        var orphans = 0
        remaining.forEachIndexed { index, directory ->
            if (directory.name !in liveKeys) orphans++
            if (index % ForwardAttachmentMigration.BATCH_SIZE.toInt() == 0) yield()
        }
        L.i {
            "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_FINAL_CLEANUP} completed " +
                "sweptDirs=$swept keptDirs=$kept emptyDirsRemoved=${before - remaining.size} orphanDirs=$orphans"
        }
        true
    }

    
    private fun legacyMessageDirectoryKeys(): List<String> = wcdb.attachment
        .getOneColumnString(DBAttachmentModel.messageId, normalRows())
        .asSequence()
        .filterNotNull()
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    private fun sweepOne(messageId: String, legacyDirectory: File): Boolean {
        
        
        
        if (wcdb.attachment.getFirstObject(DBAttachmentModel.localId.eq(legacyDirectory.name)) != null) {
            return false
        }
        
        
        
        
        
        val candidates = wcdb.attachment.getAllObjects(
            ForwardAttachmentMigration.SWEEP_FIELDS,
            DBAttachmentModel.messageId.eq(messageId)
        )
        val rowCopies = mutableListOf<LegacyAttachmentFiles.RowCopy>()
        for (row in candidates) {
            val fileName = row.fileName
            if (fileName.isNullOrEmpty()) continue
            if (!LegacyAttachmentFiles.holdsFile(legacyDirectory, fileName)) continue
            val localId = row.localId
            if (localId.isNullOrEmpty()) return false
            rowCopies += LegacyAttachmentFiles.RowCopy(
                directory = File(FileUtil.getMessageAttachmentFilePath(localId)),
                fileName = fileName,
                expectedSize = row.size
            )
        }
        if (!mayReclaimLegacyMessageDirectory(legacyDirectory, rowCopies)) return false
        return LegacyAttachmentFiles.deleteDirectory(legacyDirectory)
    }

    







    private fun legacyDirectoryReferenceCounts(): Map<String, Int> = wcdb.attachment
        .getOneColumnString(DBAttachmentModel.messageId)
        .asSequence()
        .filterNotNull()
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()

    private fun migrateRow(row: AttachmentModel, referenceCounts: Map<String, Int>): NormalRowOutcome {
        val plan = planFor(row, referenceCounts)
        val move = when (plan) {
            NormalRowPlan.Skip -> return NormalRowOutcome.SETTLED
            is NormalRowPlan.AlreadyMigrated -> {
                rows.repairStatus(row, plan.targetBasePath)
                return NormalRowOutcome.SETTLED
            }

            is NormalRowPlan.Move -> plan
        }

        val targetBasePath = File(move.targetDirectory, move.fileName)
        if (move.wholeDirectory) {
            val legacyDirectory = File(FileUtil.getMessageAttachmentFilePath(move.legacyMessageId))
            val renamed = rows.withRowLock(move.localId) {
                renameDirectory(legacyDirectory, File(move.targetDirectory))
            }
            if (renamed) {
                rows.repairStatus(row, targetBasePath)
                return NormalRowOutcome.RENAMED
            }
        }

        
        
        
        
        return when (rows.materialize(move.localId, listOf(move.legacyMessageId), move.fileName, move.targetDirectory)) {
            LegacyAttachmentFiles.CopyOutcome.COPIED -> {
                rows.repairStatus(row, targetBasePath)
                NormalRowOutcome.COPIED
            }

            LegacyAttachmentFiles.CopyOutcome.ALREADY_PRESENT -> {
                rows.repairStatus(row, targetBasePath)
                NormalRowOutcome.SETTLED
            }

            
            
            LegacyAttachmentFiles.CopyOutcome.NO_SOURCE -> NormalRowOutcome.SETTLED
            LegacyAttachmentFiles.CopyOutcome.FAILED -> NormalRowOutcome.FAILED
        }
    }

    
    private fun normalRows() = DBAttachmentModel.messageId.notNull()
        .and(DBAttachmentModel.forwardModelDatabaseId.isNull())
        .and(DBAttachmentModel.quoteModelDatabaseId.isNull())

    private fun normalRowsAfter(cursor: Int) = normalRows()
        .and(DBAttachmentModel.databaseId.gt(cursor))

    
    private enum class NormalRowOutcome { RENAMED, COPIED, SETTLED, FAILED }

    companion object {

        
        private const val MAX_NORMAL_MIGRATION_ATTEMPTS = 3

        






        fun planFor(row: AttachmentModel, referenceCounts: Map<String, Int>): NormalRowPlan {
            val localId = row.localId?.takeIf { it.isNotEmpty() } ?: return NormalRowPlan.Skip
            val fileName = row.fileName?.takeIf { it.isNotEmpty() } ?: return NormalRowPlan.Skip
            val messageId = row.messageId?.takeIf { it.isNotEmpty() } ?: return NormalRowPlan.Skip
            
            if (messageId == localId) return NormalRowPlan.Skip

            val targetDirectory = FileUtil.getMessageAttachmentFilePath(localId)
            val targetBasePath = File(targetDirectory, fileName)
            if (LegacyAttachmentFiles.isReadable(targetBasePath)) {
                return NormalRowPlan.AlreadyMigrated(targetBasePath)
            }

            
            
            
            val neverUploaded = (row.authorityId ?: 0L) == 0L

            return NormalRowPlan.Move(
                localId = localId,
                fileName = fileName,
                legacyMessageId = messageId,
                targetDirectory = targetDirectory,
                wholeDirectory = !neverUploaded && isSolelyOwnedLegacyDirectory(
                    File(FileUtil.getMessageAttachmentFilePath(messageId)),
                    fileName,
                    referenceCounts[messageId] ?: 0
                )
            )
        }

        















        fun isSolelyOwnedLegacyDirectory(
            legacyDirectory: File,
            fileName: String,
            referencingRowCount: Int
        ): Boolean {
            if (referencingRowCount != 1) return false
            val entries = legacyDirectory.listFiles() ?: return false
            if (entries.isEmpty()) return false
            
            
            
            
            val ownShapes = ownFileShapes(fileName)
            return entries.all { it.isFile && it.name in ownShapes }
        }

        
        private fun ownFileShapes(fileName: String): Set<String> {
            val encrypted = "$fileName.encrypt"
            return setOf(
                fileName,
                encrypted,
                fileName + LegacyAttachmentFiles.TEMP_SUFFIX,
                encrypted + LegacyAttachmentFiles.TEMP_SUFFIX,
                "$encrypted.tmp"
            )
        }

        














        fun mayReclaimLegacyMessageDirectory(
            legacyDirectory: File,
            rowCopies: List<LegacyAttachmentFiles.RowCopy>
        ): Boolean {
            if (!legacyDirectory.isDirectory) return false
            if (rowCopies.isEmpty()) return false
            return LegacyAttachmentFiles.isFullyMigrated(legacyDirectory, rowCopies)
        }

        





        fun renameDirectory(legacy: File, target: File): Boolean {
            if (!legacy.isDirectory || target.exists()) return false
            target.parentFile?.mkdirs()
            if (!legacy.renameTo(target)) {
                L.w { "[FwdAttachMigration] directory rename failed, falling back to copy" }
                return false
            }
            
            
            FileUtil.invalidateFileValidityUnder(legacy.path)
            return true
        }
    }
}




internal sealed interface NormalRowPlan {

    
    data object Skip : NormalRowPlan

    
    data class AlreadyMigrated(val targetBasePath: File) : NormalRowPlan

    






    data class Move(
        val localId: String,
        val fileName: String,
        val legacyMessageId: String,
        val targetDirectory: String,
        val wholeDirectory: Boolean
    ) : NormalRowPlan
}
