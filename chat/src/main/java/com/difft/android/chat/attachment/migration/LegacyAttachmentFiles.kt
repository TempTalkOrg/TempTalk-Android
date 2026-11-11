package com.difft.android.chat.attachment.migration

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import java.io.File
















internal object LegacyAttachmentFiles {

    
    const val TEMP_SUFFIX = ".migrating"

    
    private const val ENCRYPT_TEMP_SUFFIX = ".encrypt.tmp"

    



    private val TEMP_SUFFIXES = listOf(TEMP_SUFFIX, ENCRYPT_TEMP_SUFFIX)

    














    private val SWEPT_TEMP_SUFFIXES = listOf(TEMP_SUFFIX)

    enum class CopyOutcome {
        
        COPIED,

        
        ALREADY_PRESENT,

        
        NO_SOURCE,

        
        FAILED
    }

    









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
                
                
                
                
                attempted -> CopyOutcome.FAILED
                
                
                else -> CopyOutcome.NO_SOURCE
            }
        } catch (e: Exception) {
            L.w { "[FwdAttachMigration] copy failed target=${targetDirectory.name}: ${e.stackTraceToString()}" }
            CopyOutcome.FAILED
        } finally {
            
            
            FileUtil.invalidateFileValidity(target.path)
            if (move) FileUtil.invalidateFileValidity(source.path)
        }
    }

    
    fun isReadable(basePath: File): Boolean =
        EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(encrypted(basePath)) ||
            isNonEmptyFile(basePath)

    






    fun isVerified(basePath: File, expectedSize: Int): Boolean {
        if (EncryptedAttachmentAccess.isStructurallyCompleteCiphertext(encrypted(basePath))) return true
        if (!isNonEmptyFile(basePath)) return false
        return expectedSize <= 0 || basePath.length() == expectedSize.toLong()
    }

    




    fun sweepStrayTempFiles(attachmentRoot: File): Int {
        val directories = attachmentRoot.listFiles() ?: return 0
        
        
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

    
    const val STRAY_TEMP_MIN_AGE_MS = 60 * 60 * 1000L

    






    data class RowCopy(val directory: File, val fileName: String, val expectedSize: Int)

    







    fun holdsFile(directory: File, fileName: String): Boolean {
        val base = File(directory, fileName)
        return base.exists() || encrypted(base).exists()
    }

    


























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

    
    fun deleteDirectory(directory: File): Boolean {
        FileUtil.invalidateFileValidityUnder(directory.path)
        directory.listFiles()?.forEach { if (it.isFile) it.delete() }
        return directory.delete()
    }

    private fun encrypted(basePath: File): File = File(basePath.path + ".encrypt")

    private fun isNonEmptyFile(file: File): Boolean = file.isFile && file.length() > 0

    




    private fun transferExactly(source: File, target: File, move: Boolean): Boolean {
        if (move) {
            target.parentFile?.mkdirs()
            target.delete()
            if (source.renameTo(target)) return true
            L.w { "[FwdAttachMigration] move failed name=${target.name}, falling back to copy" }
        }
        return copyExactly(source, target)
    }

    




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
