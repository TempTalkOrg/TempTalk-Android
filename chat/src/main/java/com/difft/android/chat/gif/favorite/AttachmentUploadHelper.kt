package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sanitizeUrl
import com.difft.android.chat.fileshare.AttachmentUploadType
import com.difft.android.chat.fileshare.FileExistReq
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.fileshare.UploadInfoReq
import com.difft.android.network.requests.ProgressListener
import com.difft.android.network.requests.ProgressRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import util.FileUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of an account-level encrypt-and-upload: the attachment pointer fields the caller
 * needs to persist / build a message attachment or a favorite record.
 */
data class UploadedAttachment(
    val attachmentId: String,
    val authorizeId: Long,
    /** SHA-512(plaintext) — the AES/HMAC key material (first 32 = AES key, next 32 = HMAC key). */
    val key: ByteArray,
    /** cipherHash (MD5 of the ciphertext file). */
    val digest: ByteArray,
    val fileHash: String,
    val fileSize: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as UploadedAttachment
        return fileHash == other.fileHash && authorizeId == other.authorizeId
    }

    override fun hashCode(): Int = fileHash.hashCode() * 31 + authorizeId.hashCode()
}

/**
 * Shared attachment encrypt + fast-pass (isExist) + OSS upload + uploadInfo sequence,
 * extracted from [com.difft.android.chat.jobs.PushTextSendJob.uploadAttachment] so that
 * message sending and favorite asset trans-store share one implementation (§B5, fix #11/#K).
 *
 * Algorithm is byte-for-byte identical to the original:
 *  - key = SHA-512(plaintext); fileHash = Base64(SHA-256(key))
 *  - AES/CBC/PKCS5 with key[0..32) + 16B random IV (prefixed), HMAC-SHA256 with key[32..64)
 *    over (IV ‖ ciphertext), MAC appended.
 *  - isExist fast-pass: a hit returns the existing {attachmentId, authorizeId, cipherHash}.
 *
 * This is the only place that performs the upload; [com.difft.android.chat.jobs.PushTextSendJob]
 * delegates here (it still owns its own attachment-status / progress-emit lifecycle).
 */
@Singleton
class AttachmentUploadHelper @Inject constructor(
    private val fileShareRepo: FileShareRepo
) {
    /**
     * Encrypt [file] and ensure it is stored server-side for [recipients] (account-level = [myId]
     * for favorites). [onProgress] receives 0..100 during a real upload (no-op on fast-pass).
     * Runs on IO (isExist/uploadToOSS/uploadInfo are blocking Call.execute()).
     *
     * The ciphertext is written to [encryptPath] (defaults to `<file>.fav.encrypt`). When
     * [deleteEncryptFile] is true (default) the ciphertext file is removed in `finally`;
     * callers that need to retain it (e.g. audio playback) pass false and own cleanup.
     *
     * @throws IOException on any network / server failure.
     */
    suspend fun encryptAndUpload(
        file: File,
        recipients: List<String>,
        attachmentType: Int = AttachmentUploadType.NORMAL,
        encryptPath: String = file.path + ".fav.encrypt",
        deleteEncryptFile: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
    ): UploadedAttachment = withContext(Dispatchers.IO) {
        val buffer = ByteArray(8192)
        var bytesRead: Int

        val encryptFile = File(encryptPath)
        val encryptOutputStream = FileOutputStream(encryptFile)
        var inputStream: FileInputStream? = null
        var cipherInputStream: CipherInputStream? = null

        try {
            // key = SHA-512(plaintext); fileHash = Base64(SHA-256(key)).
            val digest512 = MessageDigest.getInstance("SHA-512")
            // Scope the digest-pass stream so it closes before the encrypt-pass stream opens —
            // reassigning `inputStream` without closing the first leaked one fd per call.
            FileInputStream(file).use { digestIn ->
                while (digestIn.read(buffer).also { bytesRead = it } != -1) {
                    digest512.update(buffer, 0, bytesRead)
                }
            }
            val originKey = digest512.digest()
            val digest256 = MessageDigest.getInstance("SHA-256")
            digest256.update(originKey)
            val fileHash = com.difft.android.base.utils.Base64.encodeBytes(digest256.digest())

            // AES/CBC/PKCS5 + HMAC-SHA256 (IV prefixed, MAC appended).
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(originKey, 0, 32, "AES"), IvParameterSpec(iv))
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(originKey, 32, 32, "HmacSHA256"))
            mac.update(iv)
            encryptOutputStream.write(iv)

            inputStream = FileInputStream(file)
            cipherInputStream = CipherInputStream(inputStream, cipher)
            while (cipherInputStream.read(buffer).also { bytesRead = it } != -1) {
                encryptOutputStream.write(buffer, 0, bytesRead)
                mac.update(buffer, 0, bytesRead)
            }
            encryptOutputStream.write(mac.doFinal())
            encryptOutputStream.flush()

            val fileSize = Math.toIntExact(file.length())
            val microToken = globalServices.userManager.getUserData()?.microToken ?: ""

            val existResp = fileShareRepo.isExist(FileExistReq(microToken, fileHash, recipients)).execute()
            if (!existResp.isSuccessful) {
                throw IOException("check attachment exist fail: ${existResp.message()}")
            }
            val res = existResp.body()?.data ?: throw IOException("isExist response data is null")

            if (res.exists) {
                if (res.authorizeId == 0L) throw IOException("isExist returned invalid authorizeId for existing file")
                return@withContext UploadedAttachment(
                    attachmentId = res.attachmentId,
                    authorizeId = res.authorizeId,
                    key = originKey,
                    digest = FileUtils.decodeDigestHex(res.cipherHash),
                    fileHash = fileHash,
                    fileSize = fileSize
                )
            }

            // Real upload: try each pre-signed URL until one succeeds.
            uploadToOss(encryptFile, res.urls?.takeIf { it.isNotEmpty() } ?: listOf(res.url), onProgress)

            val cipherDigest = md5Of(encryptFile, buffer)
            val uploadInfoResp = fileShareRepo.uploadInfo(
                UploadInfoReq(
                    token = microToken,
                    numbers = recipients,
                    attachmentId = res.attachmentId,
                    fileHash = fileHash,
                    cipherHash = FileUtils.bytesToHex(cipherDigest),
                    cipherHashType = "MD5",
                    hashAlg = "SHA-256",
                    keyAlg = "SHA-512",
                    encAlg = "AES-CBC-256",
                    fileSize = fileSize,
                    attachmentType = attachmentType
                )
            ).execute()
            if (!uploadInfoResp.isSuccessful) {
                throw IOException("uploadInfo fail: ${uploadInfoResp.message()}")
            }
            val authorizeId = uploadInfoResp.body()?.data?.authorizeId?.takeIf { it != 0L }
                ?: throw IOException("uploadInfo response has invalid authorizeId")

            UploadedAttachment(
                attachmentId = res.attachmentId,
                authorizeId = authorizeId,
                key = originKey,
                digest = cipherDigest,
                fileHash = fileHash,
                fileSize = fileSize
            )
        } finally {
            inputStream?.close()
            cipherInputStream?.close()
            encryptOutputStream.close()
            if (deleteEncryptFile) encryptFile.delete()
        }
    }

    /**
     * isExist fast-pass keyed by a KNOWN [fileHash] (no plaintext, no hashing). Returns the existing
     * attachment pointer fields if the server holds the file for [recipients], else null (miss →
     * caller must upload). Runs on IO (blocking Call.execute()).
     *
     * The returned [UploadedAttachment.key]/[UploadedAttachment.fileSize] are intentionally empty/0:
     * isExist never returns the plaintext key or plaintext size, and the caller already carries both
     * from the message ref — a half-populated field would be misleading.
     *
     * @throws IOException on network / non-success server response.
     */
    suspend fun existingByFileHash(fileHash: String, recipients: List<String>): UploadedAttachment? =
        withContext(Dispatchers.IO) {
            val microToken = globalServices.userManager.getUserData()?.microToken ?: ""
            val resp = fileShareRepo.isExist(FileExistReq(microToken, fileHash, recipients)).execute()
            if (!resp.isSuccessful) throw IOException("isExist fail: ${resp.message()}")
            val res = resp.body()?.data ?: throw IOException("isExist response data is null")
            if (!res.exists) return@withContext null
            if (res.authorizeId == 0L) throw IOException("isExist returned invalid authorizeId for existing file")
            UploadedAttachment(
                attachmentId = res.attachmentId,
                authorizeId = res.authorizeId,
                key = ByteArray(0),          // caller supplies the real key (ref.key); not from isExist
                digest = FileUtils.decodeDigestHex(res.cipherHash),
                fileHash = fileHash,
                fileSize = 0                 // unknown here; caller carries the message size
            )
        }

    private fun uploadToOss(encryptFile: File, urls: List<String>, onProgress: ((Int) -> Unit)?) {
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            try {
                val body: RequestBody = ProgressRequestBody(encryptFile, null, object : ProgressListener {
                    override fun onProgress(bytesRead: Long, contentLength: Long, progress: Int) {
                        onProgress?.invoke(progress)
                    }
                })
                val resp = fileShareRepo.uploadToOSS(url, body).execute()
                if (resp.isSuccessful) {
                    L.i { "[AttachmentUploadHelper] upload ok ${index + 1}/${urls.size} url=${url.sanitizeUrl()}" }
                    return
                }
                lastError = IOException("uploadToOSS fail: ${resp.message}")
                L.w { "[AttachmentUploadHelper] upload fail ${index + 1}/${urls.size}: ${resp.message}" }
            } catch (e: Exception) {
                lastError = e
                L.e { "[AttachmentUploadHelper] upload exception ${index + 1}/${urls.size}: ${e.stackTraceToString().sanitizeUrl()}" }
            }
        }
        throw lastError ?: IOException("All upload URLs failed")
    }

    private fun md5Of(file: File, buffer: ByteArray): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { stream ->
            var n: Int
            while (stream.read(buffer).also { n = it } != -1) {
                md5.update(buffer, 0, n)
            }
        }
        return md5.digest()
    }
}
