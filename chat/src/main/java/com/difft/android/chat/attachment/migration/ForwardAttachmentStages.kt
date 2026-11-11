package com.difft.android.chat.attachment.migration

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.tencent.wcdb.winq.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.synthesizedLocalId
import org.difft.app.database.wcdb
import java.io.File
















internal class ForwardAttachmentStages(
    private val state: AttachmentMigrationState,
    private val rows: RowFileMigrator
) {

    




    suspend fun backfillLocalIds(): Boolean = withContext(Dispatchers.IO) {
        L.i { "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_BACKFILLED} started" }
        var rowCount = 0
        
        
        var lastPageStart = -1
        while (true) {
            val page = wcdb.attachment.getAllObjects(
                arrayOf(DBAttachmentModel.databaseId),
                DBAttachmentModel.localId.isNull().or(DBAttachmentModel.localId.eq("")),
                DBAttachmentModel.databaseId.order(Order.Asc),
                ForwardAttachmentMigration.BATCH_SIZE
            )
            if (page.isEmpty()) break
            val pageStart = page.first().databaseId
            if (pageStart == lastPageStart) {
                L.w {
                    "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_BACKFILLED} " +
                        "stalled at databaseId=$pageStart rows=$rowCount"
                }
                return@withContext false
            }
            lastPageStart = pageStart
            
            
            
            
            
            wcdb.db.runTransaction {
                page.forEach { row ->
                    wcdb.attachment.updateValue(
                        row.synthesizedLocalId(),
                        DBAttachmentModel.localId,
                        DBAttachmentModel.databaseId.eq(row.databaseId)
                    )
                }
                true
            }
            rowCount += page.size
            yield()
        }
        L.i { "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_BACKFILLED} completed rows=$rowCount" }
        true
    }

    






    suspend fun migrateFiles(): Boolean = withContext(Dispatchers.IO) {
        var cursor = state.watermark()
        L.i { "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_FILES_MIGRATED} started watermark=$cursor" }
        
        
        
        
        val movableAuthorityIds: Set<Long> = wcdb.attachment
            .getOneColumnLong(DBAttachmentModel.authorityId, DBAttachmentModel.forwardModelDatabaseId.notNull())
            .filter { it != 0L }
            .groupingBy { it }
            .eachCount()
            .filterValues { it == 1 }
            .keys
        var rowCount = 0
        var copied = 0
        var skipped = 0
        var failed = 0
        while (true) {
            val page = wcdb.attachment.getAllObjects(
                ForwardAttachmentMigration.MIGRATION_FIELDS,
                DBAttachmentModel.forwardModelDatabaseId.notNull()
                    .and(DBAttachmentModel.databaseId.gt(cursor)),
                DBAttachmentModel.databaseId.order(Order.Asc),
                ForwardAttachmentMigration.BATCH_SIZE
            )
            if (page.isEmpty()) break
            page.forEach { row ->
                rowCount++
                when (migrateRowFile(row, movableAuthorityIds)) {
                    LegacyAttachmentFiles.CopyOutcome.COPIED -> copied++
                    LegacyAttachmentFiles.CopyOutcome.ALREADY_PRESENT -> skipped++
                    LegacyAttachmentFiles.CopyOutcome.NO_SOURCE -> skipped++
                    LegacyAttachmentFiles.CopyOutcome.FAILED -> failed++
                }
            }
            cursor = page.last().databaseId
            
            
            
            
            if (failed == 0) state.advanceWatermark(cursor)
            yield()
        }
        L.i {
            "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_FILES_MIGRATED} completed " +
                "rows=$rowCount copied=$copied skipped=$skipped failed=$failed"
        }
        
        
        if (failed == 0) return@withContext true
        
        
        
        
        
        
        
        
        val attempts = state.recordFileAttempt()
        if (attempts >= MAX_FILE_MIGRATION_ATTEMPTS) {
            L.w {
                "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_FILES_MIGRATED} " +
                    "giving up on $failed uncopyable rows after $attempts passes"
            }
            return@withContext true
        }
        false
    }

    private fun migrateRowFile(
        row: AttachmentModel,
        movableAuthorityIds: Set<Long>
    ): LegacyAttachmentFiles.CopyOutcome {
        val localId = row.localId
        val fileName = row.fileName
        if (localId.isNullOrEmpty() || fileName.isNullOrEmpty()) {
            return LegacyAttachmentFiles.CopyOutcome.NO_SOURCE
        }
        
        
        val movableSource = row.authorityId
            ?.takeIf { it != 0L && it in movableAuthorityIds }
            ?.let { File(FileUtil.getMessageAttachmentFilePath(it.toString())) }
        val targetDirectory = FileUtil.getMessageAttachmentFilePath(localId)
        val outcome = rows.materialize(localId, rows.legacyKeysFor(row), fileName, targetDirectory, movableSource)
        if (outcome == LegacyAttachmentFiles.CopyOutcome.COPIED ||
            outcome == LegacyAttachmentFiles.CopyOutcome.ALREADY_PRESENT
        ) {
            rows.repairStatus(row, File(targetDirectory, fileName))
        }
        return outcome
    }

    







    suspend fun sweepLegacyDirectories(): Boolean = withContext(Dispatchers.IO) {
        L.i { "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_SWEPT} started" }
        val attachmentRoot = File(FileUtil.getFilePath(FileUtil.FILE_DIR_ATTACHMENT))
        val authorityIds = wcdb.attachment
            .getOneColumnLong(DBAttachmentModel.authorityId, DBAttachmentModel.forwardModelDatabaseId.notNull())
            .filter { it != 0L }
            .distinct()

        var swept = 0
        var kept = 0
        authorityIds.forEachIndexed { index, authorityId ->
            val legacyDirectory = File(attachmentRoot, authorityId.toString())
            if (legacyDirectory.isDirectory) {
                if (sweepOne(authorityId, legacyDirectory)) swept++ else kept++
            }
            if (index % ForwardAttachmentMigration.BATCH_SIZE.toInt() == 0) yield()
        }
        L.i {
            "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_SWEPT} completed " +
                "sweptDirs=$swept keptDirs=$kept"
        }
        if (kept == 0) return@withContext true
        
        
        
        
        
        val attempts = state.recordSweepAttempt()
        if (attempts >= MAX_SWEEP_ATTEMPTS) {
            L.w {
                "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_SWEPT} " +
                    "giving up on $kept unverifiable dirs after $attempts passes"
            }
            return@withContext true
        }
        false
    }

    private fun sweepOne(authorityId: Long, legacyDirectory: File): Boolean {
        
        
        if (wcdb.attachment.getFirstObject(DBAttachmentModel.messageId.eq(legacyDirectory.name)) != null) {
            return false
        }
        
        
        
        
        
        
        
        
        
        
        val candidates = wcdb.attachment.getAllObjects(
            ForwardAttachmentMigration.SWEEP_FIELDS,
            DBAttachmentModel.authorityId.eq(authorityId)
                .and(DBAttachmentModel.forwardModelDatabaseId.notNull())
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
        if (!LegacyAttachmentFiles.isFullyMigrated(legacyDirectory, rowCopies)) return false
        return LegacyAttachmentFiles.deleteDirectory(legacyDirectory)
    }

    private companion object {
        
        const val MAX_FILE_MIGRATION_ATTEMPTS = 3

        
        const val MAX_SWEEP_ATTEMPTS = 3
    }
}








internal interface RowFileMigrator {

    
    fun <T> withRowLock(localId: String, block: () -> T): T

    





    fun materialize(
        localId: String,
        legacyKeys: List<String>,
        fileName: String,
        targetDirectory: String,
        movableSource: File? = null
    ): LegacyAttachmentFiles.CopyOutcome

    
    fun legacyKeysFor(row: AttachmentModel): List<String>

    
    fun repairStatus(row: AttachmentModel, targetBasePath: File)
}
