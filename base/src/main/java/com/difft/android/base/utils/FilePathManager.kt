package com.difft.android.base.utils

import android.os.Environment
import android.os.Environment.MEDIA_MOUNTED
import com.difft.android.base.log.lumberjack.L
import java.io.File

/**
 * FilePathManager - Lazy caching for common directory File objects
 *
 * Purpose: Eliminate ANR caused by multiple threads calling Context.getExternalFilesDir()
 *
 * Strategy:
 * - Call getExternalFilesDir(null) ONCE to get base directory
 * - Derive all subdirectories using File(baseDir, subdir) constructor
 * - Cache File objects using lazy delegation (thread-safe, initialized once)
 * - After first access, all subsequent calls return cached File objects (no lock contention)
 *
 * Thread-Safety:
 * - lazy with SYNCHRONIZED mode ensures single initialization per property
 * - First access may block briefly, but only once per directory
 * - Subsequent accesses are lock-free (just return cached File object)
 */
object FilePathManager {

    /**
     * Base directory - the ONLY place we call getExternalFilesDir()
     * All other directories derive from this to avoid multiple synchronized calls
     */
    private val baseDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val dir = if (Environment.getExternalStorageState() == MEDIA_MOUNTED) {
            application.getExternalFilesDir(null)
        } else {
            application.filesDir
        }
        (dir ?: application.filesDir).also {
            L.d { "FilePathManager baseDir initialized: ${it.absolutePath}" }
        }
    }

    /**
     * Avatar cache directory
     * Derived from baseDir, no additional getExternalFilesDir() call
     */
    val avatarDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.FILE_DIR_AVATAR).also {
            ensureExists(it)
            L.d { "Initialized avatarDir: ${it.absolutePath}" }
        }
    }

    /**
     * Group avatar cache directory
     */
    val groupAvatarDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.FILE_DIR_GROUP_AVATAR).also {
            ensureExists(it)
            L.d { "Initialized groupAvatarDir: ${it.absolutePath}" }
        }
    }

    /**
     * Message attachment directory (base)
     * Note: Individual message attachments will be in subdirectories
     */
    val attachmentDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.FILE_DIR_ATTACHMENT).also {
            ensureExists(it)
            L.d { "Initialized attachmentDir: ${it.absolutePath}" }
        }
    }

    /**
     * App upgrade files directory
     */
    val upgradeDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.FILE_DIR_UPGRADE).also {
            ensureExists(it)
            L.d { "Initialized upgradeDir: ${it.absolutePath}" }
        }
    }

    /**
     * Draft message attachments directory
     */
    val draftAttachmentsDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.DRAFT_ATTACHMENTS_DIRECTORY).also {
            ensureExists(it)
            L.d { "Initialized draftAttachmentsDir: ${it.absolutePath}" }
        }
    }

    /**
     * Ensure directory exists, create if needed
     * Safe to call with I/O because this only happens once during lazy initialization
     */
    private fun ensureExists(file: File) {
        if (!file.exists()) {
            if (file.mkdirs()) {
                L.d { "Created directory: ${file.absolutePath}" }
            } else if (!file.exists()) {
                L.w { "Failed to create directory: ${file.absolutePath}" }
            }
        }
    }

    /**
     * Get cached File for common directories
     * Returns null if not a common directory
     */
    fun getCachedDir(dir: String): File? = when (dir) {
        FileUtil.FILE_DIR_AVATAR -> avatarDir
        FileUtil.FILE_DIR_GROUP_AVATAR -> groupAvatarDir
        FileUtil.FILE_DIR_ATTACHMENT -> attachmentDir
        FileUtil.FILE_DIR_UPGRADE -> upgradeDir
        FileUtil.DRAFT_ATTACHMENTS_DIRECTORY -> draftAttachmentsDir
        else -> null
    }

    /**
     * Get base directory for creating dynamic subdirectories
     * Uses cached baseDir to avoid repeated getExternalFilesDir() calls
     */
    fun getBaseDirFile(): File = baseDir
}