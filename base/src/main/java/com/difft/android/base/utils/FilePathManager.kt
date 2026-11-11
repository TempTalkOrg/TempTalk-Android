package com.difft.android.base.utils

import android.os.Environment
import android.os.Environment.MEDIA_MOUNTED
import com.difft.android.base.log.lumberjack.L
import java.io.File






object FilePathManager {

    
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

    val avatarDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.FILE_DIR_AVATAR).also {
            ensureExists(it)
            L.d { "Initialized avatarDir: ${it.absolutePath}" }
        }
    }

    val groupAvatarDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.FILE_DIR_GROUP_AVATAR).also {
            ensureExists(it)
            L.d { "Initialized groupAvatarDir: ${it.absolutePath}" }
        }
    }

    
    val attachmentDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.FILE_DIR_ATTACHMENT).also {
            ensureExists(it)
            L.d { "Initialized attachmentDir: ${it.absolutePath}" }
        }
    }

    




    val upgradeDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(application.filesDir, FileUtil.FILE_DIR_UPGRADE).also {
            ensureExists(it)
            L.d { "Initialized upgradeDir: ${it.absolutePath}" }
        }
    }

    val draftAttachmentsDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.DRAFT_ATTACHMENTS_DIRECTORY).also {
            ensureExists(it)
            L.d { "Initialized draftAttachmentsDir: ${it.absolutePath}" }
        }
    }

    




    val shareImportDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(application.filesDir, FileUtil.SHARE_IMPORT_DIRECTORY).also { ensureExists(it) }
    }

    




    val gifFavoritesDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(baseDir, FileUtil.FILE_DIR_GIF_FAVORITES).also {
            ensureExists(it)
            L.d { "Initialized gifFavoritesDir: ${it.absolutePath}" }
        }
    }

    private fun ensureExists(file: File) {
        if (!file.exists()) {
            if (file.mkdirs()) {
                L.d { "Created directory: ${file.absolutePath}" }
            } else if (!file.exists()) {
                L.w { "Failed to create directory: ${file.absolutePath}" }
            }
        }
    }

    



    fun getCachedDir(dir: String): File? = when (dir) {
        FileUtil.FILE_DIR_AVATAR -> avatarDir
        FileUtil.FILE_DIR_GROUP_AVATAR -> groupAvatarDir
        FileUtil.FILE_DIR_ATTACHMENT -> attachmentDir
        FileUtil.FILE_DIR_UPGRADE -> upgradeDir
        FileUtil.DRAFT_ATTACHMENTS_DIRECTORY -> draftAttachmentsDir
        FileUtil.SHARE_IMPORT_DIRECTORY -> shareImportDir
        FileUtil.FILE_DIR_GIF_FAVORITES -> gifFavoritesDir
        else -> null
    }

    



    fun getBaseDirFile(): File = baseDir
}