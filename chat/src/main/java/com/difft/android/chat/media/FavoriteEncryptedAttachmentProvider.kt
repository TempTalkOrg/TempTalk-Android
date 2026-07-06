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

/**
 * Streams a favorite GIF's **decrypted** bytes over a `content://` uri without ever writing plaintext
 * to disk — the favorites analogue of [EncryptedAttachmentProvider], kept separate so favorites never
 * touch the message-attachment key-resolution / path-traversal logic (and vice versa).
 *
 * On-disk the favorite is the downloaded ciphertext `<gifFavoritesDir>/<attachmentId>.encrypt`
 * (`[IV16][AES-CBC][HMAC32]`, same format the fileshare download already produces). The 64-byte key
 * comes from [org.difft.app.database.models.FavoriteGifModel.encKey], looked up by `attachmentId`.
 *
 * URI: `content://<pkg>.favoriteattachment/<attachmentId>`. Favorites are small, so a simple
 * sequential decrypting pipe is enough (no random-access proxy fd).
 */
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

    /** Sequential decrypting pipe: decrypt on a background thread, stream plaintext to the read fd. */
    private fun openDecryptingPipe(encryptedFile: File, key: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        thread(name = "fav-attach-decrypt") {
            try {
                // verifyMacFirst=true: verify the HMAC-32 tail before streaming any plaintext, so a
                // corrupted/tampered/truncated ciphertext fails cleanly instead of rendering garbage.
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
            ?.takeIf { it.size >= 64 }
    } catch (e: Exception) {
        L.w { "[FavoriteEncryptedAttachmentProvider] resolveKey failed: ${e.message}" }
        null
    }

    // Read-only provider.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val authority: String get() = application.packageName + ".favoriteattachment"

        /** The on-disk ciphertext file for a favorite [attachmentId]. */
        fun encryptedFile(attachmentId: String): File =
            File(FilePathManager.gifFavoritesDir, "$attachmentId.encrypt")

        /** A `content://` uri whose bytes are the favorite's decrypted GIF. */
        fun contentUri(attachmentId: String): Uri =
            Uri.Builder().scheme("content").authority(authority).appendPath(attachmentId).build()

        /**
         * Extract + validate the attachmentId from a favorite content uri. Rejects anything that could
         * escape [FilePathManager.gifFavoritesDir] (path traversal) — the provider is not exported, but
         * our own app resolves inbound uris under the same uid, which bypasses the export check.
         */
        private fun attachmentIdOf(uri: Uri): String? {
            val id = uri.lastPathSegment ?: return null
            if (id.isBlank() || id.contains('/') || id.contains("..") || id.contains(' ')) return null
            return id
        }
    }
}
