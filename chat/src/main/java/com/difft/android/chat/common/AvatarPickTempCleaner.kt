package com.difft.android.chat.common

import android.content.Context
import android.os.Environment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.pictureselector.ImageFileCompressEngine
import java.io.File

















object AvatarPickTempCleaner {

    
    private const val CROP_FILE_PREFIX = "CROP_"

    
    private const val LEGACY_CACHE_DIR = "luban_disk_cache"

    







    fun deleteCropTemp(media: LocalMedia?, keepPath: String? = null) {
        media ?: return
        setOfNotNull(media.cutPath, media.sandboxPath)
            .filter { it.isNotEmpty() && it != keepPath }
            .forEach { path ->
                runCatching {
                    val file = File(path)
                    if (file.isFile) file.delete()
                }.onFailure { L.w { "[AvatarPickTempCleaner] delete result=failed kind=crop" } }
            }
    }

    








    fun deleteUploadedTemp(context: Context, path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching {
            val file = File(path)
            if (file.isFile && isInsidePickTempDir(context, file)) file.delete()
        }.onFailure { L.w { "[AvatarPickTempCleaner] delete result=failed kind=uploaded" } }
    }

    private fun isInsidePickTempDir(context: Context, file: File): Boolean {
        val target = runCatching { file.canonicalPath }.getOrElse { return false }
        val roots = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            context.externalCacheDir?.let { File(it, ImageFileCompressEngine.CACHE_DIR_NAME) },
            context.externalCacheDir?.let { File(it, LEGACY_CACHE_DIR) },
        )
        return roots.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            target == rootPath || target.startsWith(rootPath + File.separator)
        }
    }

    











    fun sweepPickTempDirs(context: Context) {
        var deleted = 0
        var failed = 0
        runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?.listFiles { file -> file.isFile && file.name.startsWith(CROP_FILE_PREFIX) }
                ?.forEach { if (it.delete()) deleted++ else failed++ }
        }.onFailure {
            failed++
            L.w { "[AvatarPickTempCleaner] sweep result=failed kind=crop" }
        }

        listOf(ImageFileCompressEngine.CACHE_DIR_NAME, LEGACY_CACHE_DIR).forEach { name ->
            runCatching {
                context.externalCacheDir
                    ?.let { File(it, name) }
                    ?.takeIf { it.isDirectory }
                    ?.listFiles { file -> file.isFile }
                    ?.forEach { if (it.delete()) deleted++ else failed++ }
            }.onFailure {
                failed++
                L.w { "[AvatarPickTempCleaner] sweep result=failed kind=cache" }
            }
        }
        val event = "[AvatarPickTempCleaner] sweep deleted=$deleted failed=$failed"
        L.i { event }
    }
}
