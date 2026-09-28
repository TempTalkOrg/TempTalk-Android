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

/**
 * Migration version 1's three stages: give every attachment row a local identity, bring each
 * FORWARDED copy's file to its own directory, and delete the legacy directories whose content is
 * provably elsewhere.
 *
 * Before per-copy addressing, every forwarded copy of a file was read from and written to
 * `attachment/<authorityId>/` — one directory shared by the original message and by every forward of
 * it. Copies rather than moves by default, because moving a file out of a shared directory would
 * strip the copies not processed yet; a move happens only where exactly one row references the
 * directory, which after the addressing flip is a stable fact (no new row ever points at one).
 *
 * Kept beside version 2's stages ([NormalAttachmentStages]) so [ForwardAttachmentMigration] stays
 * what it is — the version/stage state machine and the row primitives both waves borrow through
 * [RowFileMigrator].
 */
internal class ForwardAttachmentStages(
    private val state: AttachmentMigrationState,
    private val rows: RowFileMigrator
) {

    /**
     * Stage 1 — give every localId-less attachment row an id. All rows, not just forwards: the id is
     * how rows are addressed from here on, and a row that never gets one keeps synthesizing a fresh
     * transient id on every read.
     */
    suspend fun backfillLocalIds(): Boolean = withContext(Dispatchers.IO) {
        L.i { "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_BACKFILLED} started" }
        var rowCount = 0
        // The query is the remaining work, so each page must be a page the last round did not see.
        // A page that repeats means the writes are not landing — stop rather than spin forever.
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
            // One transaction per page: a hundred single-row updates would otherwise take a hundred
            // implicit ones while the user may be sending. Row updates only — no file IO inside.
            // The persisted id is the row's deterministic synthesized id — the exact value every
            // reader derived for this row before backfill — so a download/copy that already landed
            // under the synthesized address is at its final address, not in an orphan directory.
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

    /**
     * Stage 2 — copy every forward attachment's file to its own directory, resuming from the
     * watermark of an interrupted run.
     *
     * Reports done once every file is placed, or once [MAX_FILE_MIGRATION_ATTEMPTS] full passes have
     * failed to place the same residue — see the give-up rationale at the end of the body.
     */
    suspend fun migrateFiles(): Boolean = withContext(Dispatchers.IO) {
        var cursor = state.watermark()
        L.i { "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_FILES_MIGRATED} started watermark=$cursor" }
        // Directories referenced by exactly one row can be renamed out of instead of byte-copied —
        // after the addressing flip no NEW row ever points at a legacy directory, so this reference
        // set is frozen. A row minted mid-run that shares the authorityId only ever reads its own
        // localId directory (or falls back to download); it never needs the legacy file.
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
            // A page that could not be copied in full (disk full, IO error) must not be marked as
            // passed: the rest of the run still proceeds, but the resume point stays behind the
            // failure so the next launch retries it. Replaying a page is cheap — every row that did
            // land is skipped on sight.
            if (failed == 0) state.advanceWatermark(cursor)
            yield()
        }
        L.i {
            "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_FILES_MIGRATED} completed " +
                "rows=$rowCount copied=$copied skipped=$skipped failed=$failed"
        }
        // Same reason the stage is not finished until every file is placed: an unstamped stage is
        // what brings the next launch back here.
        if (failed == 0) return@withContext true
        // A row that still cannot be copied after MAX_FILE_MIGRATION_ATTEMPTS full passes is
        // permanently uncopyable here (a persistently full disk, a stale directory occupying the
        // target name), and retrying it forever costs a full table scan on every cold start while
        // never stamping the version. Giving up is safe because nothing is abandoned: the per-row
        // rescue path is NOT retired by the stamp, so an unmigrated row still materializes from its
        // legacy address the next time it is opened or downloaded, and stage 3 refuses to delete a
        // legacy directory whose claiming rows are unverified — the bytes stay where that rescue
        // will find them.
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
        // Only the authorityId directory is ever movable — the owner-message directory can hold
        // files that are not this row's, so it is always copied from, never renamed out of.
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

    /**
     * Stage 3 — delete the legacy directories whose content is provably elsewhere.
     *
     * Driven by the rows, never by the shape of a directory name: a message id is numeric too, so
     * "looks like an authority id" would happily delete a normal message's attachments. Returns true
     * when nothing was left behind; anything skipped leaves the stage unfinished, and the next launch
     * tries again.
     */
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
        // The sweep only ever deletes, so a directory still kept after MAX_SWEEP_ATTEMPTS full
        // passes is permanently unverifiable garbage (a nested directory, a stray entry whose row
        // was deleted pre-upgrade). Every live row's file is already at its current address —
        // stage 2 finished first — so finishing the stage with the residue left in place is safe,
        // whereas an unstamped version re-walks the attachment root on every launch forever.
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
        // A numeric directory name can also be a message id. Deleting a directory that a normal
        // attachment row is addressed by would destroy that message's file, so leave it alone.
        if (wcdb.attachment.getFirstObject(DBAttachmentModel.messageId.eq(legacyDirectory.name)) != null) {
            return false
        }
        // Every row that shares this legacy directory states where ITS copy must be. A row with no
        // file name owns no file and makes no claim; a row that owns one but still cannot name an
        // address (localId not backfilled) is unprovable, so the directory is kept.
        //
        // Addressing this directory is not the same as having LIVED in it: a forward minted after
        // the addressing flip is still handed this authorityId, yet its bytes were only ever written
        // under its own localId. Such a row is filtered out here — treating it as a claimant lets a
        // never-downloaded (or confidential) re-forward block the sweep until it gives up, and the
        // reclaimable bytes are abandoned. A re-forward carrying the SAME file name deliberately
        // stays a claimant: the legacy file is that row's only rescue source once the server copy
        // expires, so the directory is kept until its own copy lands.
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
        /** Full passes before stage 2 stops retrying rows whose file cannot be copied. */
        const val MAX_FILE_MIGRATION_ATTEMPTS = 3

        /** Full sweep passes before stage 3 stops retrying unverifiable legacy directories. */
        const val MAX_SWEEP_ATTEMPTS = 3
    }
}

/**
 * The per-row file primitives the stages borrow from [ForwardAttachmentMigration]: the row lock
 * shared with the lazy path and the download job, the legacy-address copy, and the status repair.
 *
 * A narrow seam on purpose — a stage needs these behaviours, not the migration's state machine, and
 * stating that in a type keeps the direction of the dependency readable.
 */
internal interface RowFileMigrator {

    /** Runs [block] holding this attachment copy's row lock. */
    fun <T> withRowLock(localId: String, block: () -> T): T

    /**
     * Brings [fileName] to [targetDirectory] from the first of [legacyKeys] that holds it.
     *
     * [movableSource] is the one legacy directory the caller has PROVEN no other row references: a
     * file found there is renamed out instead of copied.
     */
    fun materialize(
        localId: String,
        legacyKeys: List<String>,
        fileName: String,
        targetDirectory: String,
        movableSource: File? = null
    ): LegacyAttachmentFiles.CopyOutcome

    /** Legacy directory keys of a row, in the order the pre-migration code wrote them. */
    fun legacyKeysFor(row: AttachmentModel): List<String>

    /** Promotes a row still marked LOADING whose file is verifiably readable at [targetBasePath]. */
    fun repairStatus(row: AttachmentModel, targetBasePath: File)
}
