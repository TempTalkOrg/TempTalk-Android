package com.difft.android.chat.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FilePathManager
import com.difft.android.base.utils.application
import com.difft.android.chat.util.FileDecryptionUtil
import org.difft.app.database.models.DBFavoriteGifModel
import org.difft.app.database.wcdb
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread













class FavoriteEncryptedAttachmentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/webp"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("favorite attachments are read-only")
        val attachmentId = attachmentIdOf(uri) ?: throw FileNotFoundException("bad favorite uri: $uri")
        val encFile = encryptedFile(attachmentId)
        if (!encFile.exists() || encFile.length() <= 0L) {
            throw FileNotFoundException("favorite ciphertext missing: $attachmentId")
        }
        val key = resolveKey(attachmentId) ?: throw FileNotFoundException("favorite key missing: $attachmentId")
        return openDecryptingPipe(encFile, key)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    
    private fun openDecryptingPipe(encryptedFile: File, key: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        thread(name = "fav-attach-decrypt") {
            try {
                
                
                FileDecryptionUtil.decryptToStream(encryptedFile, key, verifyMacFirst = true).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                        input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    }
                }
            } catch (e: Exception) {
                L.w { "[FavoriteEncryptedAttachmentProvider] decrypt stream failed: ${e.message}" }
                try {
                    writeSide.closeWithError(e.message ?: "decrypt failed")
                } catch (_: Exception) {
                }
            }
        }
        return readSide
    }

    private fun resolveKey(attachmentId: String): ByteArray? = try {
        wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.attachmentId.eq(attachmentId))
            ?.encKey
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?.takeIf(EncryptedAttachmentAccess::isUsableKey)
    } catch (e: Exception) {
        L.w { "[FavoriteEncryptedAttachmentProvider] resolveKey failed: ${e.message}" }
        null
    }

    
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val authority: String get() = application.packageName + ".favoriteattachment"

        
        fun encryptedFile(attachmentId: String): File =
            File(FilePathManager.gifFavoritesDir, "$attachmentId.encrypt")

        
        fun contentUri(attachmentId: String): Uri =
            Uri.Builder().scheme("content").authority(authority).appendPath(attachmentId).build()

        




        private fun attachmentIdOf(uri: Uri): String? {
            val id = uri.lastPathSegment ?: return null
            if (id.isBlank() || id.contains('/') || id.contains("..") || id.contains(' ')) return null
            return id
        }
    }
}
