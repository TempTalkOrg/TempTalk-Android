package com.difft.android.chat.media

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.application
import com.difft.android.chat.util.FileDecryptionUtil
import com.difft.android.chat.util.FileEncryptionUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.wcdb
import java.io.File

/**
 * One-time, idempotent background migration that purges **legacy plaintext** media left on disk by
 * builds that predate the encrypted-at-rest change.
 *
 * Before the migration, downloaded images/voice were decrypted to a plaintext file at
 * `.../attachment/<messageId>/<fileName>`. New downloads now keep only the ciphertext
 * (`<fileName>.encrypt`) and decrypt on demand via [EncryptedAttachmentProvider]. Readers still
 * auto-fall-back to any remaining plaintext, so the app works without this migration — but the
 * security benefit of encryption-at-rest is only realised once the plaintext copies are gone.
 *
 * For each legacy plaintext media file this:
 *  1. drops it outright if a structurally-valid `.encrypt` already exists;
 *  2. otherwise re-encrypts it with the attachment's DB key into `<fileName>.encrypt.tmp`, verifies
 *     the HMAC, atomically renames it to `<fileName>.encrypt`, then deletes the plaintext.
 *
 * Files without a usable key, or whose type is not kept encrypted-at-rest, are left untouched.
 *
 * **Completion is defined by disk state, not by "the loop ran once".** The version stamp is written
 * **only** when a full pass leaves **zero transient failures** — i.e. no encryptable plaintext
 * remains. Per-file transient failures (disk full, interrupted IO, rename/MAC failure) do NOT stamp
 * the version, so they are retried on every subsequent launch until clean; re-scans are cheap
 * because already-migrated entries are skipped fast and the plaintext remainder only shrinks.
 *
 * We deliberately never "give up" while encryptable plaintext is still on disk: claiming completion
 * with plaintext left behind would defeat encryption-at-rest (and a transient cause such as a full
 * disk self-heals once the user frees space). The worst case — a file that can genuinely never be
 * encrypted — costs only a cheap re-scan per launch, which is the right price for the guarantee.
 */
object LegacyPlaintextAttachmentMigration {

    private const val TAG = "[LegacyPlaintextMigration]"
    private const val PREFS = "attachment_migration"
    private const val KEY_VERSION = "legacy_plaintext_purge_version"
    // Retry counter kept purely for observability (logged), never used to abandon the migration.
    private const val KEY_ATTEMPTS = "legacy_plaintext_purge_attempts"
    // v2: video added to the encrypt-at-rest set — bump so legacy plaintext videos are purged too.
    // v3: long text (text/x-signal-plain) added — bump so legacy plaintext long-text files are purged too.
    // v4: all remaining generic files added (uniform encrypt-at-rest) — bump so legacy plaintext
    //     documents / audio files / archives / apk / octet-stream are purged too.
    private const val CURRENT_VERSION = 4
    private const val TMP_SUFFIX = ".encrypt.tmp"

    @Volatile
    private var running = false

    /**
     * Runs the migration once (no-op if already completed or currently running). Safe to call from
     * app startup on a background scope; performs all IO on [Dispatchers.IO].
     */
    suspend fun runIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= CURRENT_VERSION) return@withContext
        if (running) return@withContext
        running = true
        try {
            val stats = purgeLegacyPlaintext()
            if (stats.failedTransient == 0) {
                // Only stamp when no encryptable plaintext remains — never claim "done" while a
                // legacy plaintext copy is still on disk (encryption-at-rest is the whole point).
                prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).remove(KEY_ATTEMPTS).apply()
                L.i { "$TAG done: migrated=${stats.migrated}, dropped=${stats.dropped}, skipped=${stats.skippedPermanent}" }
            } else {
                // Do NOT stamp: retry on every launch until clean. Re-scan is cheap (already-migrated
                // entries are skipped fast) and transient causes (full disk / IO) self-heal over time.
                val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
                prefs.edit().putInt(KEY_ATTEMPTS, attempts).apply()
                L.w { "$TAG incomplete: transientFailures=${stats.failedTransient} (attempt $attempts), retry next launch" }
            }
        } catch (e: Exception) {
            // Whole-run failure (e.g. DB unavailable): do not stamp or bump, retry next launch.
            L.w { "$TAG failed, will retry next launch: ${e.message}" }
        } finally {
            running = false
        }
    }

    private data class Stats(
        val migrated: Int,
        val dropped: Int,
        val skippedPermanent: Int,
        val failedTransient: Int,
    )

    private suspend fun purgeLegacyPlaintext(): Stats {
        val root = File(FileUtil.getFilePath(FileUtil.FILE_DIR_ATTACHMENT))
        if (!root.exists() || !root.isDirectory) return Stats(0, 0, 0, 0)

        var migrated = 0
        var dropped = 0
        var skippedPermanent = 0
        var failedTransient = 0

        val messageDirs = root.listFiles() ?: return Stats(0, 0, 0, 0)
        for (messageDir in messageDirs) {
            if (!messageDir.isDirectory) continue
            val messageId = messageDir.name
            val files = messageDir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                // Skip ciphertext and leftover temp files; only plaintext candidates remain.
                if (name.endsWith(".encrypt") || name.endsWith(TMP_SUFFIX)) continue

                val basePath = file.absolutePath
                // Already migrated (valid ciphertext present): just drop the redundant plaintext.
                if (EncryptedAttachmentAccess.hasEncrypted(basePath)) {
                    if (file.delete()) dropped++
                    continue
                }

                when (migrateOne(messageId, name, file)) {
                    MigrateResult.MIGRATED -> migrated++
                    MigrateResult.SKIPPED_PERMANENT -> skippedPermanent++
                    MigrateResult.FAILED_TRANSIENT -> failedTransient++
                }
                // Cooperative cancellation + yield to avoid hogging the IO dispatcher.
                yield()
            }
        }
        return Stats(migrated, dropped, skippedPermanent, failedTransient)
    }

    /**
     * - [MIGRATED]: re-encrypted (or redundant plaintext dropped) — resolved.
     * - [SKIPPED_PERMANENT]: will never succeed and that's acceptable — not an encrypted-at-rest
     *   type (correctly stays plaintext), or no usable key / no DB row (cannot encrypt). These do
     *   NOT block completion.
     * - [FAILED_TRANSIENT]: encrypt threw, rename failed, or MAC verification failed — should be
     *   retried on a later launch.
     */
    private enum class MigrateResult { MIGRATED, SKIPPED_PERMANENT, FAILED_TRANSIENT }

    private fun migrateOne(messageId: String, fileName: String, plainFile: File): MigrateResult {
        // No DB row (orphan plaintext) / no usable key / wrong type: cannot or should not encrypt.
        val model = findAttachment(messageId, fileName) ?: return MigrateResult.SKIPPED_PERMANENT
        val key = model.key
        if (key == null || key.size < 64) return MigrateResult.SKIPPED_PERMANENT
        if (!keepEncryptedAtRest(model)) return MigrateResult.SKIPPED_PERMANENT

        val encryptedFile = EncryptedAttachmentAccess.encryptedFile(plainFile.absolutePath)
        val tmpFile = File(plainFile.parentFile, fileName + TMP_SUFFIX)

        return try {
            if (tmpFile.exists()) tmpFile.delete()
            FileEncryptionUtil.encryptFile(plainFile, tmpFile, key)

            if (!FileDecryptionUtil.verifyMac(tmpFile, key)) {
                tmpFile.delete()
                L.w { "$TAG verifyMac failed after re-encrypt, keeping plaintext: $messageId" }
                return MigrateResult.FAILED_TRANSIENT
            }

            // Atomic replace: only delete the plaintext once the ciphertext is durably in place.
            if (encryptedFile.exists()) encryptedFile.delete()
            if (!tmpFile.renameTo(encryptedFile)) {
                tmpFile.delete()
                L.w { "$TAG rename to .encrypt failed, keeping plaintext: $messageId" }
                return MigrateResult.FAILED_TRANSIENT
            }
            plainFile.delete()
            MigrateResult.MIGRATED
        } catch (e: Exception) {
            tmpFile.delete()
            L.w { "$TAG re-encrypt failed, keeping plaintext: $messageId: ${e.message}" }
            MigrateResult.FAILED_TRANSIENT
        }
    }

    /**
     * Whether a legacy plaintext file of this row's type must be re-encrypted, decided from the DB
     * model fields. Must stay in step with which types [EncryptedAttachmentAccess] states are kept
     * encrypted at rest, and [CURRENT_VERSION] bumped whenever that set changes.
     *
     * All attachment types are kept encrypted at rest (uniform model), so every legacy
     * plaintext file with a usable key is migrated. A file whose DB row is missing or whose key is
     * unusable (`< 64B`) is still handled as a permanent skip by [migrateOne] (stays plaintext,
     * read via the plaintext-fallback path), so it never blocks migration completion.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun keepEncryptedAtRest(model: AttachmentModel): Boolean = true

    /**
     * Resolve the attachment DB row for `(messageId, fileName)`. Mirrors
     * [EncryptedAttachmentProvider]'s lookup: normal messages match by `messageId`; forward copies
     * are addressed by their per-copy `localId` (the forward-attachment migration renames/copies
     * legacy files — plaintext included — into `attachment/<localId>/` directories, so a
     * localId-named directory holding a plaintext file MUST resolve here or the file would be
     * misclassified as an orphan and left unencrypted at rest forever); forwarded single attachments
     * at the pre-localId address are matched by `authorityId` when the id is numeric.
     */
    private fun findAttachment(messageId: String, fileName: String): AttachmentModel? {
        // DB read failures are transient — let them propagate so the whole run aborts and retries,
        // rather than mislabelling every file as a permanent skip. Return null only for a genuine
        // "no row" (orphan plaintext).
        val byMessage = wcdb.attachment.getAllObjects(DBAttachmentModel.messageId.eq(messageId))
        (byMessage.firstOrNull { it.fileName == fileName } ?: byMessage.firstOrNull())?.let { return it }

        wcdb.attachment.getFirstObject(DBAttachmentModel.localId.eq(messageId))?.let { return it }

        val authorityId = messageId.toLongOrNull() ?: return null
        val byAuthority = wcdb.attachment.getAllObjects(DBAttachmentModel.authorityId.eq(authorityId))
        return byAuthority.firstOrNull { it.fileName == fileName } ?: byAuthority.firstOrNull()
    }
}
