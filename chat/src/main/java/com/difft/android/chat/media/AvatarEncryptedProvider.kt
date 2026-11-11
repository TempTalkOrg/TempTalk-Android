package com.difft.android.chat.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FilePathManager
import com.difft.android.base.utils.application
import com.difft.android.chat.common.AvatarCacheCipher
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger















class AvatarEncryptedProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("avatar cache is read-only")
        val file = resolveFile(uri) ?: throw FileNotFoundException("bad avatar uri: $uri")
        if (!file.exists() || file.length() <= 0L) {
            throw FileNotFoundException("avatar cache missing: ${uri.lastPathSegment}")
        }
        return openDecryptingPipe(file)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        
        
        val length = resolveFile(uri)?.let { AvatarCacheCipher.plaintextLength(it) }
            ?.takeIf { it > 0 } ?: AssetFileDescriptor.UNKNOWN_LENGTH
        return AssetFileDescriptor(pfd, 0, length)
    }

    
    private fun openDecryptingPipe(file: File): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        decryptExecutor.execute {
            try {
                AvatarCacheCipher.openDecrypting(file).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                        input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    }
                }
            } catch (e: Exception) {
                L.w { "[AvatarEncryptedProvider] decrypt stream failed: ${e.message}" }
                try {
                    writeSide.closeWithError(e.message ?: "decrypt failed")
                } catch (_: Exception) {
                }
            }
        }
        return readSide
    }

    
    private fun resolveFile(uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val dir = when (segments[0]) {
            DIR_AVATAR -> FilePathManager.avatarDir
            DIR_GROUP_AVATAR -> FilePathManager.groupAvatarDir
            else -> return null
        }
        val name = segments[1]
        if (name.isBlank() || name.contains('/') || name.contains("..") || name.contains(' ')) return null
        return File(dir, name)
    }

    
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val DIR_AVATAR = "avatar"
        const val DIR_GROUP_AVATAR = "group_avatar"

        private val authority: String get() = application.packageName + ".avatarcache"

        




        private val decryptExecutor = Executors.newFixedThreadPool(4, object : ThreadFactory {
            private val index = AtomicInteger(0)
            override fun newThread(r: Runnable) =
                Thread(r, "avatar-decrypt-${index.incrementAndGet()}").apply { isDaemon = true }
        })

        



        fun contentUri(dir: String, fileName: String): Uri =
            Uri.Builder().scheme("content").authority(authority)
                .appendPath(dir).appendPath(fileName).build()
    }
}
