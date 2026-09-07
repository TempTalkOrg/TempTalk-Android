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

/**
 * Migration version 2's two stages: bringing a NORMAL message's attachment files under per-copy
 * addressing, and the cleanup that closes the migration out.
 *
 * Kept out of [ForwardAttachmentMigration] so that class stays the orchestrator — the version/stage
 * state machine and the row primitives — while the work of a wave lives beside the wave.
 *
 * Where version 1 had to COPY (one legacy directory was shared by every forwarded copy of a file), a
 * normal message's directory is normally referenced by exactly one attachment row, so this stage's
 * main path is a single directory rename: `attachment/<messageId>/` becomes `attachment/<localId>/`.
 * That is O(1), copies no bytes, needs no transient disk headroom, and — because a directory rename
 * on one filesystem is atomic — leaves no half-migrated state to reason about: a crash before it is
 * a retry, a crash after it is a skip.
 *
 * Renaming is the fast path, never the fallback. Anything the rename cannot be PROVEN safe for
 * degrades to the version-1 per-file copy, which never removes the legacy file.
 */
internal class NormalAttachmentStages(
    private val state: AttachmentMigrationState,
    private val rows: RowFileMigrator
) {

    /**
     * Stage 4 — move each normal attachment's file from its owner message's directory to its own,
     * resuming from the watermark of an interrupted run.
     *
     * Returns true once every row in scope reached a settled address, or once
     * [MAX_NORMAL_MIGRATION_ATTEMPTS] full passes have failed to settle the same residue. A row that
     * could not be brought across (IO error) holds the watermark and leaves the stage unfinished,
     * which is what brings the next launch back to it — see the give-up rationale at the end of the
     * body for why that retrying is bounded.
     */
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
            // Same rule as version 1's file stage: a page that did not fully settle must not be
            // marked as passed, so the resume point stays behind it. Replaying a page is cheap —
            // every row already at its address is skipped on sight.
            if (failed == 0) state.advanceWatermark(cursor)
            yield()
        }
        L.i {
            "[FwdAttachMigration] stage=${ForwardAttachmentMigration.STAGE_NORMAL_MIGRATED} completed " +
                "rows=$rowCount renamed=$renamed copied=$copied skipped=$skipped failed=$failed"
        }
        if (failed == 0) return@withContext true
        // A row that still cannot be settled after MAX_NORMAL_MIGRATION_ATTEMPTS full passes is
        // permanently unmovable here (a persistently full disk, a directory occupying the target file
        // name, a permission-denied legacy file that still reads as present). Retrying it forever
        // costs a full table scan on every cold start while never stamping the version — and an
        // unstamped version is what withholds stage 5, so every duplicated legacy directory on the
        // device would be retained for good.
        //
        // Giving up abandons nothing. The per-row rescue is NOT retired by the stamp
        // ([ForwardAttachmentMigration.migrateIfNeeded] / `materializeFromLegacyAddress` carry no
        // completion check, and a normal row's legacy key set is its owner message id — the same
        // address this stage reads), so an unmigrated row still materializes the next time it is
        // opened or downloaded. And stage 5 refuses to delete a legacy directory whose claiming rows
        // are unverified at their own addresses ([LegacyAttachmentFiles.isFullyMigrated]), so the
        // bytes stay exactly where that rescue looks for them.
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

    /**
     * Stage 5 — close the migration out: reclaim the legacy message directories whose content is
     * provably elsewhere, drop the directories the moves emptied, and report what is left that no
     * row explains.
     *
     * Row-driven, exactly like version 1's sweep: the directories to consider come from the rows'
     * message ids, never from the shape of a name on disk. A directory is deleted only when EVERY
     * file in it has a verified counterpart under a referencing row's own address — that is what
     * makes it safe to reclaim the copies stage 4 deliberately left behind (a never-uploaded row's
     * staged file), and what keeps anything unproven exactly where it is.
     *
     * Orphans are COUNTED, never deleted. A directory with no live row behind it can be a logged-out
     * account's leftovers or a row deleted before the sweep reached it, and nothing here can tell
     * those from a file a user still needs — deleting what cannot be verified is the one thing this
     * migration never does. Their disposal belongs to an explicit, user-visible storage cleanup.
     *
     * Always finishes. Unlike version 1's sweep this never withholds the stage: every live row's
     * file is already at its current address (stage 4 ran first), so a directory that cannot be
     * verified is residue, and re-walking the attachment root on every launch forever would buy
     * nothing. The kept count is logged instead.
     */
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

    /** The message ids whose pre-migration directory this sweep may consider, each once. */
    private fun legacyMessageDirectoryKeys(): List<String> = wcdb.attachment
        .getOneColumnString(DBAttachmentModel.messageId, normalRows())
        .asSequence()
        .filterNotNull()
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    private fun sweepOne(messageId: String, legacyDirectory: File): Boolean {
        // A directory named by a message id can also BE a row's own address — nothing stops a
        // localId column from holding a message id, and version 1's sweep guards the mirror image of
        // this for the same reason. Deleting it would destroy the file it currently addresses.
        if (wcdb.attachment.getFirstObject(DBAttachmentModel.localId.eq(legacyDirectory.name)) != null) {
            return false
        }
        // Wide on purpose, exactly like the rename proof: forwarded copies were historically written
        // into their owner message's directory too, so the rows that may account for a file here are
        // all the rows naming this message — not just the normal ones this stage migrated. Mirrors
        // version 1's per-row claimant proof ([ForwardAttachmentStages.sweepOne]): a row that cannot
        // name a localId is unprovable and keeps the whole directory alive.
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

    /**
     * How many attachment rows in the WHOLE table name each message id.
     *
     * Deliberately unfiltered by row family: this count exists to prove that a legacy directory
     * holds nothing but the one row's file, and a proof that only looks at the family it expects is
     * not a proof. Forwarded copies were historically written into their owner message's directory
     * too, so a narrower query would license a rename that strips a file another row still reads.
     */
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

        // Anything the rename was not proven safe for, or that it failed at: copy the one file this
        // row owns and leave the legacy directory exactly as it was. The owner message id is the
        // whole legacy key set for a normal row — the same one `legacyKeysFor` hands the lazy path,
        // which is what keeps the two rescues looking in the same place.
        return when (rows.materialize(move.localId, listOf(move.legacyMessageId), move.fileName, move.targetDirectory)) {
            LegacyAttachmentFiles.CopyOutcome.COPIED -> {
                rows.repairStatus(row, targetBasePath)
                NormalRowOutcome.COPIED
            }

            LegacyAttachmentFiles.CopyOutcome.ALREADY_PRESENT -> {
                rows.repairStatus(row, targetBasePath)
                NormalRowOutcome.SETTLED
            }

            // Nothing readable at any legacy address: the file has to be downloaded, which is the
            // row's own business and not a reason to hold the stage back.
            LegacyAttachmentFiles.CopyOutcome.NO_SOURCE -> NormalRowOutcome.SETTLED
            LegacyAttachmentFiles.CopyOutcome.FAILED -> NormalRowOutcome.FAILED
        }
    }

    /** The rows this wave owns: a message's own attachment, neither forwarded nor quoted. */
    private fun normalRows() = DBAttachmentModel.messageId.notNull()
        .and(DBAttachmentModel.forwardModelDatabaseId.isNull())
        .and(DBAttachmentModel.quoteModelDatabaseId.isNull())

    private fun normalRowsAfter(cursor: Int) = normalRows()
        .and(DBAttachmentModel.databaseId.gt(cursor))

    /** What a row's pass over stage 4 settled on. */
    private enum class NormalRowOutcome { RENAMED, COPIED, SETTLED, FAILED }

    companion object {

        /** Full passes stage 4 spends on a row it cannot settle before it reports done regardless. */
        private const val MAX_NORMAL_MIGRATION_ATTEMPTS = 3

        /**
         * What stage 4 must do with one attachment row — decided before anything is written, so the
         * decision can be pinned against temp directories instead of only on a device.
         *
         * The database is out of the picture by then: the row's own columns and the reference count
         * are all it takes, and the file questions are answered against the paths themselves.
         */
        fun planFor(row: AttachmentModel, referenceCounts: Map<String, Int>): NormalRowPlan {
            val localId = row.localId?.takeIf { it.isNotEmpty() } ?: return NormalRowPlan.Skip
            val fileName = row.fileName?.takeIf { it.isNotEmpty() } ?: return NormalRowPlan.Skip
            val messageId = row.messageId?.takeIf { it.isNotEmpty() } ?: return NormalRowPlan.Skip
            // A row already addressed by the directory it sits in has nothing to move.
            if (messageId == localId) return NormalRowPlan.Skip

            val targetDirectory = FileUtil.getMessageAttachmentFilePath(localId)
            val targetBasePath = File(targetDirectory, fileName)
            if (LegacyAttachmentFiles.isReadable(targetBasePath)) {
                return NormalRowPlan.AlreadyMigrated(targetBasePath)
            }

            // A send that never finished uploading (authorityId stays 0 until the upload lands) may
            // still have a resend reading its staged file from the legacy address. That row is
            // COPIED, never moved: the current address gets the bytes so the stage can finish and
            // every reader — the resend included, which resolves through the migrating read — finds
            // them, while the legacy file stays put for anything still holding the old path. What
            // used to hold such a row back instead was a livelock waiting to happen: a send that
            // never settles keeps the stage, and with it the version stamp, open forever. The legacy
            // copy is reclaimed later by stage 5, and only once it has been VERIFIED to exist at the
            // row's own address.
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

        /**
         * Whether `attachment/<messageId>/` may be renamed WHOLESALE onto one row's address.
         *
         * Two independent facts must agree, because either alone has a hole:
         *  - exactly one attachment row in the whole table names this message, so no other row can
         *    be addressed by the directory;
         *  - and every entry in it belongs to that row's file — its base name, its `.encrypt`
         *    sibling, a `.migrating` temp. A stray entry means something else wrote here (a forward
         *    copy's pre-migration landing, most of all: those rows carry no message id of their own,
         *    so the row count above cannot see them), and a rename would carry it off to an address
         *    its owner will never look at.
         *
         * Anything unexpected — an unreadable listing, a nested directory, an empty directory —
         * answers false and the caller copies instead. Renaming is an optimisation; being unable to
         * prove it safe costs a byte copy, never a file.
         */
        fun isSolelyOwnedLegacyDirectory(
            legacyDirectory: File,
            fileName: String,
            referencingRowCount: Int
        ): Boolean {
            if (referencingRowCount != 1) return false
            val entries = legacyDirectory.listFiles() ?: return false
            if (entries.isEmpty()) return false
            // The EXACT sibling shapes this row's file can produce — never a bare prefix match: a
            // foreign file whose user-chosen name merely starts with "<fileName>." (say
            // "photo.jpg.mp4" next to photo.jpg) must fail the proof and degrade to the copy path,
            // or the rename would carry it off to an address its owner never looks at.
            val ownShapes = ownFileShapes(fileName)
            return entries.all { it.isFile && it.name in ownShapes }
        }

        /** Every on-disk name [fileName]'s own storage can legitimately produce in its directory. */
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

        /**
         * Whether `attachment/<messageId>/` may be DELETED now that stage 4 has settled every row
         * that names it.
         *
         * Same two-part proof version 1's sweep verifies with ([LegacyAttachmentFiles.isFullyMigrated]):
         * every CLAIMANT (a row [rowCopies] names that actually holds a file here) must be verified at
         * its own address, and every file still in the legacy directory must have a same-name,
         * same-length counterpart under one of the claimed addresses. That is what makes it safe to
         * reclaim the legacy copy stage 4 deliberately left behind for a never-uploaded row.
         *
         * A directory no row accounts for is NOT swept: with nothing to verify against, "fully
         * migrated" would be vacuously true and the sweep would delete an orphan on no evidence.
         * Anything else unexpected (a nested directory, an unreadable listing) answers false too, so
         * a directory that is not understood is kept rather than removed.
         */
        fun mayReclaimLegacyMessageDirectory(
            legacyDirectory: File,
            rowCopies: List<LegacyAttachmentFiles.RowCopy>
        ): Boolean {
            if (!legacyDirectory.isDirectory) return false
            if (rowCopies.isEmpty()) return false
            return LegacyAttachmentFiles.isFullyMigrated(legacyDirectory, rowCopies)
        }

        /**
         * Renames [legacy] onto [target], or reports false without touching either.
         *
         * Refuses when the target already exists: a rename onto a live directory is not the atomic
         * swap this stage relies on, and the row's own address may already hold a downloaded file.
         */
        fun renameDirectory(legacy: File, target: File): Boolean {
            if (!legacy.isDirectory || target.exists()) return false
            target.parentFile?.mkdirs()
            if (!legacy.renameTo(target)) {
                L.w { "[FwdAttachMigration] directory rename failed, falling back to copy" }
                return false
            }
            // The validity cache keys on exact paths and caches only "valid", so the paths this move
            // emptied would keep reporting readable until logout.
            FileUtil.invalidateFileValidityUnder(legacy.path)
            return true
        }
    }
}

/**
 * What stage 4 decided to do with one attachment row. See [NormalAttachmentStages.planFor].
 */
internal sealed interface NormalRowPlan {

    /** Nothing this stage can act on: no identity, no file name, or already addressed by its own id. */
    data object Skip : NormalRowPlan

    /** A usable file is already at the row's own address; only its status may still need repair. */
    data class AlreadyMigrated(val targetBasePath: File) : NormalRowPlan

    /**
     * Bring the file across from `attachment/<legacyMessageId>/`.
     *
     * [wholeDirectory] is true ONLY when the legacy directory was proven to hold nothing but this
     * row's file AND nothing may still be reading it at that address, which is what licenses the
     * O(1) directory rename; false means copy the one file and leave the directory alone.
     */
    data class Move(
        val localId: String,
        val fileName: String,
        val legacyMessageId: String,
        val targetDirectory: String,
        val wholeDirectory: Boolean
    ) : NormalRowPlan
}
