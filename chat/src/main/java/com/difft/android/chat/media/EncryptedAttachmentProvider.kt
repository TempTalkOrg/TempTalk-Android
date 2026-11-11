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
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.util.FileDecryptionUtil
import com.difft.android.websocket.api.crypto.AttachmentCipherStreamUtil
import difft.android.messageserialization.model.CONTENT_TYPE_LONG_TEXT
import org.difft.app.database.models.AttachmentModel
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread














class EncryptedAttachmentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val parsed = EncryptedAttachmentAccess.parse(uri) ?: return null
        val (messageId, fileName) = parsed
        
        
        
        return resolveExternalContentType(messageId, fileName) ?: mimeOf(fileName)
    }

    


    private fun resolveExternalContentType(messageId: String, fileName: String): String? =
        externalContentTypeFor(resolveContentType(messageId, fileName)) { mimeFromExtension(fileName) }

    
    private fun mimeFromExtension(fileName: String): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileName)?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    



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
        
        val basePath = resolveBasePath(messageId, fileName) ?: return null

        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        
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
        
        
        
        val basePath = resolveBasePath(messageId, fileName)
            ?: throw java.io.FileNotFoundException("attachment path rejected")

        
        if (!EncryptedAttachmentAccess.hasEncrypted(basePath) && EncryptedAttachmentAccess.hasPlaintext(basePath)) {
            return ParcelFileDescriptor.open(
                EncryptedAttachmentAccess.plaintextFile(basePath),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        }

        val encryptedFile = EncryptedAttachmentAccess.encryptedFile(basePath)
        
        
        if (!EncryptedAttachmentAccess.hasEncrypted(basePath)) {
            throw java.io.FileNotFoundException("attachment not available (missing or truncated)")
        }

        val key = resolveKey(messageId, fileName)
            ?: throw java.io.FileNotFoundException("decryption key not found")

        
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
        
        
        
        
        val declaredLength = runCatching {
            val parsed = EncryptedAttachmentAccess.parse(uri) ?: return@runCatching -1L
            val (messageId, fileName) = parsed
            val basePath = resolveBasePath(messageId, fileName) ?: return@runCatching -1L
            if (!EncryptedAttachmentAccess.hasEncrypted(basePath)) return@runCatching -1L
            val key = resolveKey(messageId, fileName) ?: return@runCatching -1L
            FileDecryptionUtil.exactPlaintextLength(EncryptedAttachmentAccess.encryptedFile(basePath), key)
        }.getOrDefault(-1L).takeIf { it >= 0 } ?: AssetFileDescriptor.UNKNOWN_LENGTH
        return AssetFileDescriptor(pfd, 0, declaredLength)
    }

    
    private fun findAttachment(key: String, fileName: String): AttachmentModel? {
        return try {
            findAttachmentRowByUriKey(key, fileName)
        } catch (e: Exception) {
            L.w { "[EncryptedAttachmentProvider] findAttachment failed: ${e.message}" }
            null
        }
    }

    



    private fun resolveBasePath(key: String, fileName: String): String? {
        val row = findAttachment(key, fileName)
        val directoryKey = row?.let { AttachmentPathResolver.directoryKeyForRow(it.localId) } ?: key
        
        val primary = EncryptedAttachmentAccess.resolveContainedBasePath(directoryKey, fileName) ?: return null
        if (row == null || EncryptedAttachmentAccess.isReadable(primary)) return primary
        val legacyKeys = listOfNotNull(
            key.takeIf { it != directoryKey },
            row.authorityId?.takeIf { it != 0L }?.toString(),
            row.messageId
        ).distinct()
        for (legacy in legacyKeys) {
            val candidate = EncryptedAttachmentAccess.resolveContainedBasePath(legacy, fileName) ?: continue
            if (EncryptedAttachmentAccess.isReadable(candidate)) return candidate
        }
        return primary
    }

    private fun resolveKey(messageId: String, fileName: String): ByteArray? =
        findAttachment(messageId, fileName)?.key?.takeIf(EncryptedAttachmentAccess::isUsableKey)

    private fun resolveContentType(messageId: String, fileName: String): String? =
        findAttachment(messageId, fileName)?.contentType?.takeIf { it.isNotBlank() }

    private fun plaintextSize(basePath: String, key: ByteArray?): Long {
        return try {
            when {
                EncryptedAttachmentAccess.hasEncrypted(basePath) -> {
                    val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
                    
                    
                    
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

    
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    







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

        











        internal fun externalContentTypeFor(dbType: String?, extensionMime: () -> String?): String? {
            if (dbType == CONTENT_TYPE_LONG_TEXT) return "text/plain"
            if (dbType.isNullOrBlank() || isOctetStream(dbType)) return extensionMime() ?: dbType
            return dbType
        }

        private fun isOctetStream(type: String): Boolean =
            type.equals("application/octet-stream", ignoreCase = true)

        
        
        
        
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
            
            val idx = (rrCounter.getAndIncrement() and Int.MAX_VALUE) % pool.size
            return pool[idx]
        }
    }
}
