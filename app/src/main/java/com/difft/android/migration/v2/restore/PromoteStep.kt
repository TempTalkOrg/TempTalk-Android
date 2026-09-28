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

/**
 * The data-safety critical section of restore. Turns the importer's staging products — a
 * [StagingDatabase] file and a staging attachment dir — into the live chat library by moving them into
 * the real locations and installing the staging cipher key as the real `wcdb_key.bin`.
 *
 * Restore is net-install only, so the real DB is empty when promote runs and a half-promote can never
 * overwrite existing user data: the worst case is an incomplete restore that is re-done while staging
 * survives, or discarded back to the empty state once it does not. The orchestrator enforces that by
 * refusing to reach promote on a device that is not a fresh install, checked before import and
 * re-checked immediately before promote.
 *
 * The order is crash-idempotent, and [resumeIfPending] re-does P1..P4 from a surviving marker:
 * ```
 * P0 write promote-marker{staging paths, protected staging key, real destinations}
 * P1 install the staging cipher key as wcdb_key.bin (release only; debug is plaintext)
 * P2 move the DB file (+ -wal/-shm) onto tt_wcdb_database.db — an atomic same-FS rename
 * P3 move the staging attachments into the real attachment dir
 * P4 clear the promote-marker
 * ```
 * P2's rename is atomic, so a resume is either "already new, continue" or "still old, redo"; P3 moves
 * only the files still in staging.
 *
 * @param ioDispatcher dispatcher the file IO runs on. Injectable so unit tests can pin it to the test
 *   scheduler, since the real `Dispatchers.IO` leaks exceptions out of `runTest`.
 */
class PromoteStep(
    private val context: Context,
    private val markerStore: PromoteMarkerStore = PromoteMarkerStore(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Test seam for [File.listFiles], so a unit test can simulate the `listFiles()==null` I/O-error
     * shape that a healthy temp dir cannot reproduce. Production always uses the default.
     */
    private val dirLister: (File) -> Array<File>? = { it.listFiles() },
) {

    /**
     * Result of [resumeIfPending]. The caller must treat the two success-ish shapes differently: only
     * [ResumedWithData] means data is ready and sign-in continues, while [DiscardedToCleanState] means
     * restore must be re-prompted.
     */
    sealed interface ResumeResult {
        /** No marker was present — nothing to resume (the normal-launch case). */
        data object NothingToResume : ResumeResult

        /** A pending promote was re-done to completion; the real DB is on disk. */
        data object ResumedWithData : ResumeResult

        /**
         * A half-promote was discarded back to the empty net-install state (staging gone + real DB
         * empty, or a corrupt marker). No data is on disk — the caller re-prompts restore.
         */
        data object DiscardedToCleanState : ResumeResult
    }

    /**
     * Run the full promote, P0 through P4. Call only after import and verify succeeded and the
     * [StagingDatabase] has been closed, so its WAL is checkpointed into the db file.
     *
     * The caller must have asserted net-install — that the live chat DB has no message rows — before
     * calling this. Promote does not re-check: it runs on files and cannot distinguish a schema-only
     * fresh DB from real data by length, so a file-length check here would reject a legitimately empty
     * schema DB.
     *
     * @param stagingKey the staging cipher key, or null when the staging DB is plaintext, in which case
     *   nothing is protected in the marker and P1 installs no key.
     * @throws MigrationException.RestoreFailed on a precondition violation or a retryable failure; the
     *   marker survives so [resumeIfPending] retries.
     * @throws MigrationException.RestoreAbortedCleanState on the unrecoverable staging-key case; the
     *   marker is cleared and staging discarded, so the caller re-prompts restore.
     */
    suspend fun promote(
        stagingDbPath: String,
        stagingKey: ByteArray?,
        attachmentsStagingDir: String,
        realDbPath: String,
        realAttachmentDir: String,
    ) = withContext(ioDispatcher) {
        // Net-install is the caller's asserted precondition; promote does not re-check, because a
        // file-length check cannot tell a schema-only fresh DB from one holding data.
        val staging = File(stagingDbPath)
        if (!staging.exists()) {
            throw MigrationException.RestoreFailed("staging db missing at promote: $stagingDbPath")
        }

        // P0 — the single source of truth on disk; the staging key is protected, never plaintext.
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

    /**
     * Cold-start crash recovery: a surviving marker means a previous promote was interrupted, so re-do
     * P1..P4 idempotently. Safe because the real DB is empty — the redo either completes the promote
     * while staging survives, or leaves the empty DB untouched and clears the stale marker.
     *
     * @throws MigrationException.RestoreFailed on a retryable failure; the marker is kept and the next
     *   cold start retries.
     * @throws MigrationException.RestoreAbortedCleanState on the unrecoverable staging-key case; the
     *   marker is cleared and staging discarded, which guarantees cold start cannot loop.
     */
    suspend fun resumeIfPending(): ResumeResult = withContext(ioDispatcher) {
        val marker = when (val r = markerStore.readChecked()) {
            PromoteMarkerStore.ReadResult.Absent -> return@withContext ResumeResult.NothingToResume
            is PromoteMarkerStore.ReadResult.Present -> r.marker
            PromoteMarkerStore.ReadResult.Corrupt -> {
                // A marker file exists but is unparseable. Treating it as "no marker" would strand a
                // half-promote forever, no-op-ing every cold start. The real DB is empty, so clearing
                // the corrupt marker and falling back to a clean net install is safe. The staging paths
                // are unreadable from a corrupt marker, so only the marker is cleared; any orphaned
                // staging is reclaimed by the orchestrator's pre-import cleanup on the next attempt.
                L.e { "[MigrationV2][Promote] resume: corrupt marker → discard to clean net-install state" }
                markerStore.clear()
                return@withContext ResumeResult.DiscardedToCleanState
            }
        }
        L.i { "[MigrationV2][Promote] resumeIfPending: marker found, re-doing P1..P4" }

        // The discard-vs-redo decision runs before WCDB is safely usable, so it must not hinge on
        // opening the real DB or on its byte size: a fresh net install already has a schema-only real
        // DB, so neither a zero length nor a missing file means "no restore happened".
        //
        // The DB-free signal is the staging products. If either the staging db file or the staging
        // attachment dir still exists there is unfinished work, so redo P1..P4. If both are gone the
        // promote either completed or never produced anything, so discard back to the clean state.
        val stagingDbGone = !File(marker.stagingDbPath).exists()
        val stagingAttachmentsGone = !File(marker.attachmentsStagingDir).exists()
        if (stagingDbGone && stagingAttachmentsGone) {
            // Residual edge: a promote that completed P1..P3 but crashed before P4 also has no staging
            // products left, so this reports DiscardedToCleanState and re-prompts even though data is on
            // disk. That window is sub-millisecond, and re-doing the restore is idempotent and loses
            // nothing. Telling the two apart would need the real DB opened and counted, which is exactly
            // what is unsafe at cold start.
            L.w { "[MigrationV2][Promote] resume: staging products gone → discard half-promote (clean net-install)" }
            markerStore.clear()
            return@withContext ResumeResult.DiscardedToCleanState
        }

        runPhases(marker)
        ResumeResult.ResumedWithData
    }

    /**
     * P1..P4. Shared by [promote] and [resumeIfPending] so a fresh promote and a resumed one execute
     * exactly the same steps (idempotency by construction).
     */
    private fun runPhases(marker: PromoteMarker) {
        // P1a — recover the staging cipher key from the marker. A null key means the staging DB is
        // plaintext, mirroring the plaintext real DB in debug builds, so there is nothing to install and
        // P1 is skipped entirely.
        //
        // When a key is present, a WCDBKeyUnavailableException means the Keystore alias protecting it is
        // permanently gone, so the protected blob can never be decrypted and this promote can never
        // complete — retrying every cold start would loop forever. The real DB is still empty, so the
        // safe action is to clear the marker, discard staging and surface the distinct aborted outcome.
        val stagingKey = try {
            marker.stagingKey()
        } catch (e: WCDBKeyUnavailableException) {
            L.e {
                "[MigrationV2][Promote] P1 staging key unrecoverable (Keystore alias invalidated) → " +
                    "abort to clean net-install state: ${e.stackTraceToString()}"
            }
            // The real DB is empty, so discarding staging and clearing the marker loses nothing and
            // guarantees cold start cannot loop forever.
            discardStagingProducts(marker)
            markerStore.clear()
            throw MigrationException.RestoreAbortedCleanState(
                "promote aborted: staging key unrecoverable; fell back to clean net-install state", e
            )
        }

        // P1b — install the key when this build has one. Idempotent: re-installing the same key is a
        // no-op replacement. A failure here is retryable, so keep the marker and throw RestoreFailed.
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

        // P2 — move db file (+ -wal/-shm). rename is atomic on the same FS.
        moveDatabaseFile(marker.stagingDbPath, marker.realDbPath)
        L.i { "[MigrationV2][Promote] P2 db file in place" }

        // P3 — move attachments (idempotent: only moves files still in staging).
        moveAttachments(marker.attachmentsStagingDir, marker.realAttachmentDir)
        L.i { "[MigrationV2][Promote] P3 attachments in place" }

        // P4 — clear the marker; the real DB is now ready. Until this point a cold start treats it as
        // not ready.
        markerStore.clear()
        L.i { "[MigrationV2][Promote] P4 marker cleared; promote complete" }
    }

    /**
     * Discard the staging products referenced by [marker]. Used only on the unrecoverable abort path:
     * the real DB is empty, so throwing staging away loses nothing and returns the device to a clean
     * net-install state restore can be re-attempted from.
     */
    private fun discardStagingProducts(marker: PromoteMarker) {
        StagingDatabase.deleteStagingArtifacts(marker.stagingDbPath)
        runCatching { File(marker.attachmentsStagingDir).deleteRecursively() }
        L.i { "[MigrationV2][Promote] staging products discarded after unrecoverable abort" }
    }

    /**
     * Move the staging db file, and any `-wal`/`-shm` sidecars that survived the checkpoint, onto
     * [realDbPath]. Idempotent under crash recovery: a staging file that is already gone while the real
     * db is present means P2 ran before the crash, so the main file is a no-op.
     */
    private fun moveDatabaseFile(stagingDbPath: String, realDbPath: String) {
        val realDir = File(realDbPath).parentFile
        runCatching { realDir?.mkdirs() }

        val mainStaging = File(stagingDbPath)
        val mainReal = File(realDbPath)
        if (mainStaging.exists()) {
            // The old live DB's -wal/-shm (and WCDB repair materials) must not survive it: SQLite
            // would replay the old WAL onto the promoted file on the next open.
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
            // Neither staging nor real present — promote cannot complete.
            throw MigrationException.RestoreFailed("P2 db file missing at both staging and real paths")
        }

        // Move the sidecars. After a clean truncate-checkpoint the `-wal` is empty and its move is
        // cosmetic, but if the checkpoint did not succeed it still holds un-folded rows and losing it
        // would promote a row-incomplete DB. The two cases are indistinguishable here, so a sidecar move
        // failure is hard: throw, so the marker survives and the resume retries.
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

    /**
     * Move every attachment file from [stagingDirPath] into [realDirPath], preserving the relative
     * `<ownerKey>/<fileName>` subtree the importer wrote.
     *
     * The move is per file rather than per owner directory, so a crash-resume of an owner dir holding
     * several files moves only the files still in staging and never deletes a dir with un-moved children.
     */
    private fun moveAttachments(stagingDirPath: String, realDirPath: String) {
        val stagingDir = File(stagingDirPath)
        if (!stagingDir.exists()) {
            // Nothing to move: no attachments in this backup, or P3 completed before the crash.
            return
        }
        val realDir = File(realDirPath).apply { mkdirs() }
        moveTree(stagingDir, realDir)
        // Remove the now-empty staging tree; only empty dirs remain after the per-file moves.
        runCatching { stagingDir.deleteRecursively() }
    }

    /**
     * Recursively move the contents of [srcDir] into [dstDir] one file at a time. Subdirectories are
     * re-created on the destination and descended into; a file already at the destination is skipped.
     */
    private fun moveTree(srcDir: File, dstDir: File) {
        // listFiles() returns null on an I/O error even when the dir exists. Treating that as "no
        // attachments" would clear the marker with files still unmoved, orphaning them, so throw
        // instead and let the resume retry P3.
        val children = dirLister(srcDir)
            ?: throw MigrationException.RestoreFailed(
                "P3 listFiles returned null for existing staging dir ${srcDir.absolutePath} (I/O error); keep marker for retry"
            )
        children.forEach { child ->
            val dst = File(dstDir, child.name)
            if (child.isDirectory) {
                // A nested dir: re-create it on the destination and descend, so leaf files move
                // individually and no directory is deleted wholesale.
                dst.apply { mkdirs() }
                moveTree(child, dst)
                runCatching { child.delete() } // remove now-empty staging subdir (best-effort)
            } else if (dst.exists()) {
                // Already moved by a crashed run, or landed by a re-download: drop the staging copy.
                runCatching { child.delete() }
            } else if (!atomicMove(child, dst)) {
                // Both rename and copy failed. Proceeding to P4 would orphan the attachment, so throw
                // and let the resume retry P3; already-moved files are skipped on the next pass.
                L.e { "[MigrationV2][Promote] P3 attachment move AND copy both failed name=${child.forLog()}; keep marker for retry" }
                throw MigrationException.RestoreFailed(
                    "P3 attachment move failed name=${child.forLog()}; keep marker for retry"
                )
            }
        }
    }

    /**
     * What a log may say about a file being moved: a directory's name is a localId and identifies
     * nothing; a file's name is the user's, so only its extension and length go out.
     */
    private fun File.forLog(): String = if (isDirectory) name else "file(*.$extension, nameLen=${name.length})"

    /**
     * Atomic same-FS move via `renameTo`, falling back to a recursive copy and delete when that returns
     * false — across filesystems, or onto a non-empty destination dir.
     */
    private fun atomicMove(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        return try {
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = true)
                // A failed delete after a verified copy is non-fatal, since the copy is the source of
                // truth; the caller cleans up the stale source dir best-effort.
                if (!src.deleteRecursively()) {
                    L.w { "[MigrationV2][Promote] move fallback: src dir delete failed (copy ok) name=${src.forLog()}" }
                }
            } else {
                src.copyTo(dst, overwrite = true)
                // A truncated copy must not count as success, or a truncated real DB could be promoted
                // as complete; verify the byte length first.
                if (dst.length() != src.length()) {
                    L.e {
                        "[MigrationV2][Promote] move fallback copy truncated name=${src.forLog()} " +
                            "srcLen=${src.length()} dstLen=${dst.length()}"
                    }
                    runCatching { dst.delete() } // drop the partial dst so a retry re-copies cleanly
                    return false
                }
                // A failed delete after a verified copy is non-fatal — log and continue.
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

    private companion object {
        /** Staging sidecars moved along with the main file. */
        val STAGING_SIDECAR_SUFFIXES = listOf("-wal", "-shm")

        /** Sidecars an opened live DB leaves behind: SQLite WAL/shm/journal and WCDB repair materials. */
        val LIVE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal", "-first.material", "-last.material", "-incremental.material")
    }
}
