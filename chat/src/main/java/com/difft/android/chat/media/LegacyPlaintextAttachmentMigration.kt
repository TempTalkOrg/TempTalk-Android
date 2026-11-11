package com.difft.android.chat.media

import android.content.Context
import android.os.Environment
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






object LegacyPlaintextAttachmentMigration {

    private const val TAG = "[LegacyPlaintextMigration]"
    private const val PREFS = "attachment_migration"
    private const val KEY_VERSION = "legacy_plaintext_purge_version"
    
    private const val KEY_ATTEMPTS = "legacy_plaintext_purge_attempts"
    
    
    
    
    private const val CURRENT_VERSION = 4
    private const val TMP_SUFFIX = ".encrypt.tmp"
    private const val KEY_REDUNDANT_PURGE_VERSION = "redundant_plaintext_purge_version"
    private const val REDUNDANT_PURGE_VERSION = 1

    @Volatile
    private var running = false

    



    suspend fun runIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= CURRENT_VERSION) return@withContext
        if (running) return@withContext
        running = true
        try {
            val stats = purgeLegacyPlaintext()
            if (stats.failedTransient == 0) {
                
                
                prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).remove(KEY_ATTEMPTS).apply()
                L.i { "$TAG done: migrated=${stats.migrated}, dropped=${stats.dropped}, skipped=${stats.skippedPermanent}" }
            } else {
                
                
                val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
                prefs.edit().putInt(KEY_ATTEMPTS, attempts).apply()
                L.w { "$TAG incomplete: transientFailures=${stats.failedTransient} (attempt $attempts), retry next launch" }
            }
        } catch (e: Exception) {
            
            L.w { "$TAG failed, will retry next launch: ${e.message}" }
        } finally {
            running = false
        }
    }

    


    suspend fun purgeRedundantPlaintextIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REDUNDANT_PURGE_VERSION, 0) >= REDUNDANT_PURGE_VERSION) return@withContext
        try {
            val root = File(FileUtil.getFilePath(FileUtil.FILE_DIR_ATTACHMENT))
            val storageState = Environment.getExternalStorageState(root)
            if (storageState != Environment.MEDIA_UNKNOWN && storageState != Environment.MEDIA_MOUNTED) {
                L.w { "$TAG redundant purge skipped storageState=$storageState" }
                return@withContext
            }
            val result = purgeRedundantPlaintext(root) { dirKey, fileName -> findAttachment(dirKey, fileName)?.key }
            val stamped = mayStampRedundantPurge(result.complete, storageState)
            if (stamped) prefs.edit().putInt(KEY_REDUNDANT_PURGE_VERSION, REDUNDANT_PURGE_VERSION).apply()
            L.i { "$TAG redundant purge deleted=${result.deleted} complete=${result.complete} stamped=$stamped" }
        } catch (e: Exception) {
            
            L.w { "$TAG redundant purge failed type=${e.javaClass.simpleName}" }
        }
    }

    





    internal fun mayStampRedundantPurge(complete: Boolean, rootStorageState: String): Boolean =
        complete && rootStorageState == Environment.MEDIA_MOUNTED

    internal class PurgeResult(val deleted: Int, val complete: Boolean)

    
    internal fun purgeRedundantPlaintext(root: File, keyOf: (String, String) -> ByteArray?): PurgeResult {
        if (!root.exists()) return PurgeResult(0, complete = true)
        val copyDirs = root.listFiles() ?: return PurgeResult(0, complete = false)
        var deleted = 0
        var complete = true
        for (copyDir in copyDirs) {
            if (!copyDir.isDirectory) continue
            val files = copyDir.listFiles()
            if (files == null) {
                complete = false
                continue
            }
            for (file in files) {
                if (!file.isFile || file.name.endsWith(".encrypt") || file.name.endsWith(TMP_SUFFIX)) continue
                if (!EncryptedAttachmentAccess.hasEncrypted(file.absolutePath)) continue
                val key = keyOf(copyDir.name, file.name)?.takeIf(EncryptedAttachmentAccess::isUsableKey) ?: continue
                
                
                if (!FileDecryptionUtil.verifyMac(EncryptedAttachmentAccess.encryptedFile(file.absolutePath), key)) continue
                if (file.delete()) deleted++ else complete = false
            }
        }
        return PurgeResult(deleted, complete)
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
                
                if (name.endsWith(".encrypt") || name.endsWith(TMP_SUFFIX)) continue

                val basePath = file.absolutePath
                
                if (EncryptedAttachmentAccess.hasEncrypted(basePath)) {
                    if (file.delete()) dropped++
                    continue
                }

                when (migrateOne(messageId, name, file)) {
                    MigrateResult.MIGRATED -> migrated++
                    MigrateResult.SKIPPED_PERMANENT -> skippedPermanent++
                    MigrateResult.FAILED_TRANSIENT -> failedTransient++
                }
                
                yield()
            }
        }
        return Stats(migrated, dropped, skippedPermanent, failedTransient)
    }

    







    private enum class MigrateResult { MIGRATED, SKIPPED_PERMANENT, FAILED_TRANSIENT }

    private fun migrateOne(messageId: String, fileName: String, plainFile: File): MigrateResult {
        
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

    









    @Suppress("UNUSED_PARAMETER")
    private fun keepEncryptedAtRest(model: AttachmentModel): Boolean = true

    








    private fun findAttachment(messageId: String, fileName: String): AttachmentModel? {
        
        
        
        val byMessage = wcdb.attachment.getAllObjects(DBAttachmentModel.messageId.eq(messageId))
        (byMessage.firstOrNull { it.fileName == fileName } ?: byMessage.firstOrNull())?.let { return it }

        wcdb.attachment.getFirstObject(DBAttachmentModel.localId.eq(messageId))?.let { return it }

        val authorityId = messageId.toLongOrNull() ?: return null
        val byAuthority = wcdb.attachment.getAllObjects(DBAttachmentModel.authorityId.eq(authorityId))
        return byAuthority.firstOrNull { it.fileName == fileName } ?: byAuthority.firstOrNull()
    }
}
