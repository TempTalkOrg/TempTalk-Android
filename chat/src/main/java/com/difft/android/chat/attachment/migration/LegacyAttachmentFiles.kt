package com.difft.android.chat.attachment.migration

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import java.io.File

/**
 * The file half of the forward-attachment migration: where a legacy copy may still be found, how it
 * is brought to the address the app now uses, and when a legacy directory is safe to delete.
 *
 * Every directory arrives as a parameter and no WCDB type appears here, so the decisions that can
 * destroy a user's only copy of a file are unit-testable against temp directories — the native WCDB
 * library is unavailable on the JVM, and "is this directory fully migrated" is exactly the decision
 * that must not be verified only on a device.
 *
 * Copies by default, because one legacy directory may be shared by every forwarded copy of the same
 * file — moving the file out of it would strip the copies that have not been processed yet. A move
 * happens only when the caller proves single ownership ([materialize]'s `movableSource`): after the
 * addressing flip no new row ever points at a legacy directory, so a directory referenced by exactly
 * one row can be renamed out of — O(1), no byte copy, no transient disk doubling.
 */
internal object LegacyAttachmentFiles {

    /** Half-written copy. Renamed onto the real name only once the bytes are complete. */
    const val TEMP_SUFFIX = ".migrating"

    /** Ciphertext written by an in-flight download, or by an interrupted legacy-plaintext re-encrypt. */
    private const val ENCRYPT_TEMP_SUFFIX = ".encrypt.tmp"

    /**
     * Temp shapes left behind by this migration, by the legacy-plaintext one, or by an interrupted
     * download; never content.
     */
    private val TEMP_SUFFIXES = listOf(TEMP_SUFFIX, ENCRYPT_TEMP_SUFFIX)

    /**
     * The temp shapes this sweep OWNS — the ones no other component would ever reclaim.
     *
     * [TEMP_SUFFIX] is this migration's own half-written copy: written by a short, purely local step,
     * so one older than [STRAY_TEMP_MIN_AGE_MS] is certainly the residue of a process death, and
     * nothing else ever revisits it. Which is why this sweep runs on every launch regardless of the
     * migration's version stamp — the stamp retires the bulk pass, not the reclaim.
     *
     * [ENCRYPT_TEMP_SUFFIX] is deliberately NOT swept. It is the live sink of an in-flight download
     * (a large file on a slow link legitimately holds it far past the age filter, and deleting it
     * under the writer fails that download), and both of its writers already drop it at the start of
     * their next run — the download job before streaming, the legacy-plaintext migration before
     * re-encrypting, and that migration retries until no plaintext is left. Reclaiming those bytes is
     * someone else's job.
     */
    private val SWEPT_TEMP_SUFFIXES = listOf(TEMP_SUFFIX)

    enum class CopyOutcome {
        /** This call put a complete copy at the current address. */
        COPIED,

        /** A usable file was already there; nothing was copied. */
        ALREADY_PRESENT,

        /** No legacy address holds a readable file — the attachment has to be downloaded. */
        NO_SOURCE,

        /** A legacy file exists but could not be copied (disk full, IO error, short copy). */
        FAILED
    }

    /**
     * Brings [fileName] to [targetDirectory] from the first of [legacyDirectories] that holds it.
     *
     * Both on-disk shapes are carried over: the ciphertext (`<name>.encrypt`, the only shape written
     * today) and a legacy plaintext file when one is still there.
     *
     * [movableSource] is the one legacy directory the caller has PROVEN is referenced by no other
     * row: a file found there is renamed out (atomic, no byte copy) instead of copied. A rename
     * failure falls back to the copy path, so the outcome contract is unchanged.
     */
    fun materialize(
        legacyDirectories: List<File>,
        targetDirectory: File,
        fileName: String,
        movableSource: File? = null
    ): CopyOutcome {
        val target = File(targetDirectory, fileName)
        if (isReadable(target)) return CopyOutcome.ALREADY_PRESENT

        val source = legacyDirectories.firstOrNull { isReadable(File(it, fileName)) }
            ?.let { File(it, fileName) }
            ?: return CopyOutcome.NO_SOURCE

        val move = movableSource != null && source.parentFile?.path == movableSource.path
        return try {
            var attempted = false
            var copied = false
            if (EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(encrypted(source))) {
                attempted = true
                copied = transferExactly(encrypted(source), encrypted(target), move) || copied
            }
            if (isNonEmptyFile(source)) {
                attempted = true
                copied = transferExactly(source, target, move) || copied
            }
            when {
                copied -> CopyOutcome.COPIED
                // A source was there and every transfer of it came up short (short copy, failed
                // rename). This is a copy FAILURE, never "nothing to copy": NO_SOURCE counts as
                // skipped, which would advance the watermark past a row whose bytes never landed and
                // let the sweep delete the only copy left.
                attempted -> CopyOutcome.FAILED
                // Both shapes vanished between the readability check and the copy (a delete raced
                // us) — there is genuinely nothing to bring across.
                else -> CopyOutcome.NO_SOURCE
            }
        } catch (e: Exception) {
            L.w { "[FwdAttachMigration] copy failed target=${targetDirectory.name}: ${e.stackTraceToString()}" }
            CopyOutcome.FAILED
        } finally {
            // The cache only ever stores "this path is valid", so a write under a path that was
            // probed while missing must drop that entry — for the base path and its .encrypt sibling.
            FileUtil.invalidateFileValidity(target.path)
            if (move) FileUtil.invalidateFileValidity(source.path)
        }
    }

    /** Whether either on-disk shape of [basePath] can be read right now. */
    fun isReadable(basePath: File): Boolean =
        EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(encrypted(basePath)) ||
            isNonEmptyFile(basePath)

    /**
     * Whether the file at [basePath] may be declared downloaded.
     *
     * A ciphertext is self-verifying (structural check above, MAC on read). A plaintext file is
     * accepted only at the length the attachment row states, so a legacy file truncated by a crash
     * before the upgrade is never promoted to SUCCESS — it keeps the download path instead.
     */
    fun isVerified(basePath: File, expectedSize: Int): Boolean {
        if (EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(encrypted(basePath))) return true
        if (!isNonEmptyFile(basePath)) return false
        return expectedSize <= 0 || basePath.length() == expectedSize.toLong()
    }

    /**
     * Deletes the half-written files left by a process that died mid-write — this migration's own
     * copy temp ([SWEPT_TEMP_SUFFIXES], which states why that one and not the ciphertext temp). This
     * sweep is its only cleanup.
     */
    fun sweepStrayTempFiles(attachmentRoot: File): Int {
        val directories = attachmentRoot.listFiles() ?: return 0
        // A temp younger than this is presumed to belong to an in-flight lazy migration, not a crash
        // orphan — deleting it under the writer would fail that row's copy for the run.
        val strayBefore = System.currentTimeMillis() - STRAY_TEMP_MIN_AGE_MS
        var deleted = 0
        for (directory in directories) {
            if (!directory.isDirectory) continue
            val files = directory.listFiles() ?: continue
            for (file in files) {
                if (file.isFile && SWEPT_TEMP_SUFFIXES.any { file.name.endsWith(it) } &&
                    file.lastModified() < strayBefore && file.delete()
                ) deleted++
            }
        }
        return deleted
    }

    /** One hour: far above any single copy's duration, far below the next launch's sweep. */
    const val STRAY_TEMP_MIN_AGE_MS = 60 * 60 * 1000L

    /**
     * One row's claim on a legacy directory: the address its own copy must be at, the name it must
     * carry, and the size the row states. A row that owns no file (no file name) makes no claim and
     * is simply absent from the list.
     *
     * A claim only BINDS where the directory actually holds that file name — see [isFullyMigrated].
     */
    data class RowCopy(val directory: File, val fileName: String, val expectedSize: Int)

    /**
     * Whether [directory] physically holds either on-disk shape of [fileName] — the ciphertext or a
     * legacy plaintext.
     *
     * Existence, never readability: a truncated or half-written file still counts as held, so the row
     * that owns it keeps its directory alive instead of having it swept out from under a copy that
     * has not landed.
     */
    fun holdsFile(directory: File, fileName: String): Boolean {
        val base = File(directory, fileName)
        return base.exists() || encrypted(base).exists()
    }

    /**
     * Whether [legacyDirectory] may be deleted.
     *
     * [rowCopies] are the rows that ADDRESS this directory; the CLAIMANTS are the subset it actually
     * [holdsFile] a file for. A forward row minted after the addressing flip is handed the same
     * authorityId, but its bytes were only ever written under its own localId — it can neither prove
     * nor disprove anything about this directory, and counting it would let a never-downloaded (or
     * confidential) re-forward keep a reclaimable directory alive until the sweep gives up.
     *
     * A post-flip re-forward that carries the SAME file name is the deliberate exception: the
     * directory does hold that name, so the row IS a claimant and the directory is kept until that
     * copy lands. The legacy file is the only rescue source that row has left once the server copy
     * expires — reclaiming the bytes must not cost the user their last copy.
     *
     * Two independent proofs, BOTH required:
     *
     *  1. per row — every CLAIMANT has a verified file at its OWN address. One sibling's successful
     *     migration must never authorize deleting the source another sibling still needs: the rows
     *     sharing a legacy directory migrate independently, and one of them can fail (or be skipped
     *     for want of a file name) while the others land.
     *  2. per file — every file still in the directory has a same-name, same-length counterpart under
     *     one of the claimed addresses. This is what covers the strays no row claims any more (a row
     *     deleted before the upgrade).
     *
     * Anything unexpected (a nested directory, an unreadable listing) answers false, so a directory
     * that is not understood is kept rather than removed.
     */
    fun isFullyMigrated(legacyDirectory: File, rowCopies: List<RowCopy>): Boolean {
        val entries = legacyDirectory.listFiles() ?: return false
        val claimants = rowCopies.filter { holdsFile(legacyDirectory, it.fileName) }
        val unmigratedRow = claimants.any { !isVerified(File(it.directory, it.fileName), it.expectedSize) }
        if (unmigratedRow) return false
        for (entry in entries) {
            if (!entry.isFile) return false
            if (TEMP_SUFFIXES.any { entry.name.endsWith(it) }) continue
            val hasCounterpart = claimants.any { copy ->
                File(copy.directory, entry.name).let { it.isFile && it.length() == entry.length() }
            }
            if (!hasCounterpart) return false
        }
        return true
    }

    /** Deletes [directory] and its files, dropping their cached validity first. */
    fun deleteDirectory(directory: File): Boolean {
        FileUtil.invalidateFileValidityUnder(directory.path)
        directory.listFiles()?.forEach { if (it.isFile) it.delete() }
        return directory.delete()
    }

    private fun encrypted(basePath: File): File = File(basePath.path + ".encrypt")

    private fun isNonEmptyFile(file: File): Boolean = file.isFile && file.length() > 0

    /**
     * Moves [source] onto [target] when [move] is allowed (atomic same-filesystem rename — either
     * name holds the complete file at every instant), else copies. A failed rename degrades to the
     * copy path rather than failing the row.
     */
    private fun transferExactly(source: File, target: File, move: Boolean): Boolean {
        if (move) {
            target.parentFile?.mkdirs()
            target.delete()
            if (source.renameTo(target)) return true
            L.w { "[FwdAttachMigration] move failed name=${target.name}, falling back to copy" }
        }
        return copyExactly(source, target)
    }

    /**
     * Copies [source] onto [target] through a temp name, so a crash mid-copy can never leave a
     * partial file under the name readers trust. Returns false when the copy is short — the temp is
     * dropped and the legacy file stays where it is.
     */
    private fun copyExactly(source: File, target: File): Boolean {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        target.parentFile?.mkdirs()
        temp.delete()
        source.copyTo(temp, overwrite = true)
        if (temp.length() != source.length()) {
            temp.delete()
            L.w { "[FwdAttachMigration] short copy name=${target.name}, discarded" }
            return false
        }
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            L.w { "[FwdAttachMigration] rename failed name=${target.name}" }
            return false
        }
        return true
    }
}
