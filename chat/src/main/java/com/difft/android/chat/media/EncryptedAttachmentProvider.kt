package com.difft.android.chat.media

import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.util.FileDecryptionUtil
import com.difft.android.websocket.api.crypto.AttachmentCipherStreamUtil
import difft.android.messageserialization.model.CONTENT_TYPE_LONG_TEXT
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.wcdb
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Streams a message attachment's **decrypted** bytes over a `content://` uri without ever writing
 * plaintext to disk. Consumers: Glide, MediaMetadataRetriever, full-screen preview, and external
 * apps via ACTION_SEND / ACTION_VIEW.
 *
 * Two read paths:
 * - **API 26+**: a **seekable** file descriptor via [StorageManager.openProxyFileDescriptor], backed
 *   by on-the-fly AES-CBC random-access decryption. Seekability is required by many receiving apps
 *   (galleries, social apps, re-encoders) — a non-seekable pipe makes them report "unsupported file".
 * - **API 24-25**: a sequential pipe fallback (sufficient for in-app Glide decode and copying).
 *
 * URI shape: `content://<pkg>.encryptedattachment/m/<messageId>/<fileName>`.
 */
class EncryptedAttachmentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val parsed = EncryptedAttachmentAccess.parse(uri) ?: return null
        val (messageId, fileName) = parsed
        // Prefer the authoritative content type stored in the DB; an attachment file name's
        // extension is unreliable and MimeTypeMap.getFileExtensionFromUrl() frequently yields ""
        // for content:// uris → octet-stream → receiving apps reject as "unsupported file type".
        return resolveExternalContentType(messageId, fileName) ?: mimeOf(fileName)
    }

    /**
     * The externally-facing content type for a uri, derived from the DB type with two corrections
     * so external receivers (ACTION_SEND / ACTION_VIEW) can find a handler and infer the saved file's
     * extension:
     * - **long text**: the internal mime (`text/x-signal-plain`) is unknown to other apps (they fall
     *   back to a generic `.tmp` name), so it is mapped to `text/plain` — restoring the `.txt`
     *   behaviour of the pre-encryption FileProvider path (DISPLAY_NAME already carries the `.txt`).
     * - **generic files**: when the DB type is missing or a non-descriptive `application/octet-stream`
     *   but the file name has a known extension, prefer the extension-derived mime. This mirrors the
     *   pre-encryption FileProvider behaviour (`MediaUtil.getMimeType()` resolved from the extension),
     *   so encrypting generic files at rest does not regress "open in / share to" the external app.
     *
     * Returns `null` when no DB row exists; [getType] then falls back to [mimeOf].
     */
    private fun resolveExternalContentType(messageId: String, fileName: String): String? =
        externalContentTypeFor(resolveContentType(messageId, fileName)) { mimeFromExtension(fileName) }

    /** Extension-derived mime, or `null` when the extension is absent/unknown. */
    private fun mimeFromExtension(fileName: String): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName)?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    /**
     * Declare that we can supply the data as a typed stream — required by receivers that call
     * [android.content.ContentResolver.openTypedAssetFileDescriptor] (common for ACTION_SEND).
     */
    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        val type = getType(uri) ?: return null
        return if (ClipDescription.compareMimeTypes(type, mimeTypeFilter)) arrayOf(type) else null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val parsed = EncryptedAttachmentAccess.parse(uri) ?: return null
        val (messageId, fileName) = parsed
        // Reject path-traversal uris before any filesystem access (see resolveContainedBasePath).
        val basePath = EncryptedAttachmentAccess.resolveContainedBasePath(messageId, fileName) ?: return null

        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        // Resolved lazily only if SIZE is actually requested (avoids a needless DB lookup + block decrypt).
        val key: ByteArray? by lazy { resolveKey(messageId, fileName) }
        for (col in cols) {
            when (col) {
                OpenableColumns.DISPLAY_NAME -> row.add(fileName)
                OpenableColumns.SIZE -> row.add(plaintextSize(basePath, key))
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw IllegalArgumentException("EncryptedAttachmentProvider is read-only (mode=$mode)")

        val parsed = EncryptedAttachmentAccess.parse(uri)
            ?: throw IllegalArgumentException("Bad attachment uri")
        val (messageId, fileName) = parsed
        // Reject path-traversal uris BEFORE any filesystem access — in particular before the plaintext
        // fallback below, which would otherwise disclose arbitrary app-readable files for a crafted
        // `m/../../…` uri (confused-deputy; see resolveContainedBasePath).
        val basePath = EncryptedAttachmentAccess.resolveContainedBasePath(messageId, fileName)
            ?: throw java.io.FileNotFoundException("attachment path rejected")

        // Legacy plaintext still on disk → serve it directly (transition compatibility).
        if (!EncryptedAttachmentAccess.hasEncrypted(basePath) && EncryptedAttachmentAccess.hasPlaintext(basePath)) {
            return ParcelFileDescriptor.open(
                EncryptedAttachmentAccess.plaintextFile(basePath),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        }

        val encryptedFile = EncryptedAttachmentAccess.encryptedFile(basePath)
        // Reject a missing OR structurally incomplete ciphertext (e.g. truncated download): feeding a
        // short/odd-sized file to the decrypter yields garbage bytes that fail to decode silently.
        if (!EncryptedAttachmentAccess.hasEncrypted(basePath)) {
            throw java.io.FileNotFoundException("attachment not available (missing or truncated)")
        }

        val key = resolveKey(messageId, fileName)
            ?: throw java.io.FileNotFoundException("decryption key not found")

        // Seekable fd (API 26+): required for broad external-app compatibility.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val storageManager = context?.getSystemService(StorageManager::class.java)
            if (storageManager != null) {
                return runCatching {
                    storageManager.openProxyFileDescriptor(
                        ParcelFileDescriptor.MODE_READ_ONLY,
                        CbcDecryptingProxyCallback(encryptedFile, key),
                        proxyHandler()
                    )
                }.getOrElse { e ->
                    L.w { "[EncryptedAttachmentProvider] proxy fd failed, falling back to pipe: ${e.message}" }
                    openDecryptingPipe(encryptedFile, key)
                }
            }
        }

        return openDecryptingPipe(encryptedFile, key)
    }

    /** Sequential, non-seekable fallback (API < 26 or proxy-fd failure). */
    private fun openDecryptingPipe(encryptedFile: File, key: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        thread(name = "enc-attach-decrypt") {
            try {
                FileDecryptionUtil.decryptToStream(encryptedFile, key).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                        input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    }
                }
            } catch (e: Exception) {
                L.w { "[EncryptedAttachmentProvider] decrypt stream failed: ${e.message}" }
                try {
                    writeSide.closeWithError(e.message ?: "decrypt failed")
                } catch (_: Exception) {
                }
            }
        }
        return readSide
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        // Declare the exact plaintext length instead of UNKNOWN_LENGTH. Video playback of a
        // moov-at-end MP4 needs the total size so ExoPlayer can seek to the tail to read the moov
        // (otherwise it treats the source as non-seekable-to-end → no duration / no seek). Matches the
        // pad-aware length the proxy fd's onGetSize serves; falls back to UNKNOWN_LENGTH if unresolved.
        val declaredLength = runCatching {
            val parsed = EncryptedAttachmentAccess.parse(uri) ?: return@runCatching -1L
            val (messageId, fileName) = parsed
            val basePath = EncryptedAttachmentAccess.resolveContainedBasePath(messageId, fileName)
                ?: return@runCatching -1L
            if (!EncryptedAttachmentAccess.hasEncrypted(basePath)) return@runCatching -1L
            val key = resolveKey(messageId, fileName) ?: return@runCatching -1L
            FileDecryptionUtil.exactPlaintextLength(EncryptedAttachmentAccess.encryptedFile(basePath), key)
        }.getOrDefault(-1L).takeIf { it >= 0 } ?: AssetFileDescriptor.UNKNOWN_LENGTH
        return AssetFileDescriptor(pfd, 0, declaredLength)
    }

    /**
     * Resolve the attachment DB row for a uri's `(messageId, fileName)`.
     *
     * Normal messages: the file lives under `getMessageAttachmentFilePath(message.id)` and the DB
     * row's `messageId` equals that id. Forwarded single attachments are special — the bubble/preview
     * address the file by the attachment's **authorityId** (see ChatMessageListFragment), so the uri's
     * "messageId" segment is actually an authorityId that won't match any row's `messageId`. Fall back
     * to an `authorityId` lookup in that case so the decryption key/content type can still be found.
     */
    private fun findAttachment(messageId: String, fileName: String): AttachmentModel? {
        return try {
            val byMessage = wcdb.attachment.getAllObjects(DBAttachmentModel.messageId.eq(messageId))
            (byMessage.firstOrNull { it.fileName == fileName } ?: byMessage.firstOrNull())?.let { return it }

            val authorityId = messageId.toLongOrNull() ?: return null
            val byAuthority = wcdb.attachment.getAllObjects(DBAttachmentModel.authorityId.eq(authorityId))
            byAuthority.firstOrNull { it.fileName == fileName } ?: byAuthority.firstOrNull()
        } catch (e: Exception) {
            L.w { "[EncryptedAttachmentProvider] findAttachment failed: ${e.message}" }
            null
        }
    }

    private fun resolveKey(messageId: String, fileName: String): ByteArray? =
        findAttachment(messageId, fileName)?.key?.takeIf { it.size >= 64 }

    private fun resolveContentType(messageId: String, fileName: String): String? =
        findAttachment(messageId, fileName)?.contentType?.takeIf { it.isNotBlank() }

    private fun plaintextSize(basePath: String, key: ByteArray?): Long {
        return try {
            when {
                EncryptedAttachmentAccess.hasEncrypted(basePath) -> {
                    val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
                    // Pad-aware exact length so OpenableColumns.SIZE matches the bytes the fd actually
                    // serves (onGetSize); consumers that trust SIZE won't truncate the last partial
                    // block. Fall back to the block-floor estimate if the key is missing/invalid.
                    FileDecryptionUtil.exactPlaintextLength(encFile, key)
                        .takeIf { it >= 0 }
                        ?: AttachmentCipherStreamUtil.getPlaintextLength(encFile.length())
                }
                EncryptedAttachmentAccess.hasPlaintext(basePath) ->
                    EncryptedAttachmentAccess.plaintextFile(basePath).length()
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun mimeOf(fileName: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext?.lowercase()) ?: "application/octet-stream"
    }

    // Read-only provider: mutations are unsupported.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * Random-access AES-CBC decryption backing a proxy file descriptor.
     *
     * Ciphertext layout (matches [FileDecryptionUtil]): `[IV(16)][C0..Cn (16 each)][HMAC(32)]`.
     * To decrypt plaintext block `i`, the IV is the previous ciphertext block (`Ci-1`), or the file
     * IV for block 0 — this lets us decrypt any block range on demand without touching the rest.
     * Integrity was already verified once at download time, so HMAC is not re-checked here.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private class CbcDecryptingProxyCallback(
        file: File,
        private val fileKey: ByteArray
    ) : ProxyFileDescriptorCallback() {

        private val raf = RandomAccessFile(file, "r")
        private val cipherLen = (file.length() - IV_SIZE - MAC_SIZE).coerceAtLeast(0)
        private val fileIv = ByteArray(IV_SIZE)
        private val plaintextLen: Long

        init {
            raf.seek(0)
            raf.readFully(fileIv)
            plaintextLen = computePlaintextLen()
        }

        private fun cipherBlock(blockIndex: Long): ByteArray {
            val b = ByteArray(BLOCK)
            raf.seek(IV_SIZE + blockIndex * BLOCK)
            raf.readFully(b)
            return b
        }

        private fun ivForBlock(blockIndex: Long): ByteArray =
            if (blockIndex == 0L) fileIv else cipherBlock(blockIndex - 1)

        private fun newCipher(iv: ByteArray): Cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, 0, 32, "AES"), IvParameterSpec(iv))
        }

        private fun computePlaintextLen(): Long {
            if (cipherLen < BLOCK || cipherLen % BLOCK != 0L) return 0
            val lastIdx = cipherLen / BLOCK - 1
            val pt = newCipher(ivForBlock(lastIdx)).doFinal(cipherBlock(lastIdx))
            val pad = pt[pt.size - 1].toInt() and 0xFF
            val padding = if (pad in 1..BLOCK) pad else 0
            return cipherLen - padding
        }

        override fun onGetSize(): Long = plaintextLen

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                if (offset < 0 || offset >= plaintextLen) return 0
                val eff = minOf(size.toLong(), plaintextLen - offset).toInt()
                if (eff <= 0) return 0

                val startBlock = offset / BLOCK
                val endBlock = (offset + eff - 1) / BLOCK
                val runLen = ((endBlock - startBlock + 1) * BLOCK).toInt()

                val iv = ivForBlock(startBlock)
                val ctRun = ByteArray(runLen)
                raf.seek(IV_SIZE + startBlock * BLOCK)
                raf.readFully(ctRun)

                val ptRun = newCipher(iv).doFinal(ctRun)
                val within = (offset - startBlock * BLOCK).toInt()
                System.arraycopy(ptRun, within, data, 0, eff)
                return eff
            } catch (e: Exception) {
                L.w { "[EncryptedAttachmentProvider] proxy onRead failed: ${e.message}" }
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            try {
                raf.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val IV_SIZE = 16
        private const val MAC_SIZE = 32
        private const val BLOCK = 16

        /**
         * Pure decision for the externally-facing content type (framework/DB-free so it is unit
         * testable). Given the DB content type and a lazily-resolved extension mime, applies the two
         * corrections documented on [resolveExternalContentType]:
         * - long text (`text/x-signal-plain`) → `text/plain`;
         * - a missing / blank / `application/octet-stream` DB type falls back to the extension mime
         *   when one is known (else keeps the DB type — possibly `null`, letting [getType] fall back
         *   to [mimeOf]).
         *
         * [extensionMime] is a lambda so the (Android `MimeTypeMap`) lookup is only performed on the
         * fallback branch — a well-typed DB row never triggers it.
         */
        internal fun externalContentTypeFor(dbType: String?, extensionMime: () -> String?): String? {
            if (dbType == CONTENT_TYPE_LONG_TEXT) return "text/plain"
            if (dbType.isNullOrBlank() || isOctetStream(dbType)) return extensionMime() ?: dbType
            return dbType
        }

        private fun isOctetStream(type: String): Boolean =
            type.equals("application/octet-stream", ignoreCase = true)

        // Each opened proxy fd binds to ONE handler; all its onRead callbacks run on that handler's
        // thread. A single shared thread would serialize decryption across every concurrently-loading
        // image (scroll jank). Spread opens across a small pool (round-robin) so concurrent reads run
        // in parallel, while keeping the thread count bounded.
        private val POOL_SIZE = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        private val rrCounter = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile
        private var handlers: Array<Handler>? = null

        private fun proxyHandler(): Handler {
            val pool = handlers ?: synchronized(this) {
                handlers ?: Array(POOL_SIZE) { i ->
                    Handler(HandlerThread("enc-attach-proxy-$i").apply { start() }.looper)
                }.also { handlers = it }
            }
            // Non-negative index even after int overflow.
            val idx = (rrCounter.getAndIncrement() and Int.MAX_VALUE) % pool.size
            return pool[idx]
        }
    }
}
