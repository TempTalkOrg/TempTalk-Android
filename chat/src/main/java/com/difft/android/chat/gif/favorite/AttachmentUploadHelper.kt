package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sanitizeUrl
import com.difft.android.chat.attachment.decodeCipherHashOrNull
import com.difft.android.chat.attachment.resolveUploadIdentity
import com.difft.android.chat.fileshare.AttachmentUploadType
import com.difft.android.chat.fileshare.FileExistReq
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.fileshare.UploadInfoReq
import com.difft.android.network.requests.ProgressListener
import com.difft.android.network.requests.ProgressRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import util.FileSystemUtils
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
            val fileHash = attachmentFileHashOf(originKey)

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

            publish(
                ciphertext = encryptFile,
                key = originKey,
                fileHash = fileHash,
                plainSize = fileSize,
                recipients = recipients,
                attachmentType = attachmentType,
                onProgress = onProgress
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
                digest = serverDigestOrEmpty(res.cipherHash),
                fileHash = fileHash,
                fileSize = 0                 // unknown here; caller carries the message size
            )
        }

    /**
     * Re-authorizes an attachment whose bytes are already stored **encrypted at rest**, by uploading
     * that stored ciphertext as-is. Used when a forward's rapid-upload authorization misses: the
     * server no longer holds the file, but this device still does.
     *
     * The at-rest ciphertext is exactly the payload a recipient must receive — it was produced with
     * (or downloaded for) [key], so its IV/MAC already match. Re-encrypting instead would require the
     * plaintext, which is deleted for every encrypted-at-rest attachment, and would only produce an
     * equivalent payload under a fresh IV. [key] is content-derived (SHA-512 of the plaintext), so
     * `fileHash` — and therefore the server-side identity of the file — is unchanged; only the
     * authorization (and the cipherHash, when the stored ciphertext is not the one the server held)
     * comes back new. Callers MUST write every returned field back.
     *
     * @param plainSize the recorded PLAINTEXT size; the plaintext is not on disk to measure.
     * @throws IOException on any network / server failure.
     */
    suspend fun uploadStoredCiphertext(
        ciphertextFile: File,
        key: ByteArray,
        plainSize: Int,
        recipients: List<String>,
        attachmentType: Int = AttachmentUploadType.NORMAL,
        onProgress: ((Int) -> Unit)? = null
    ): UploadedAttachment = withContext(Dispatchers.IO) {
        publish(ciphertextFile, key, attachmentFileHashOf(key), plainSize, recipients, attachmentType, onProgress)
    }

    /**
     * isExist → (upload → uploadInfo) for an already-encrypted [ciphertext]. Shared by the
     * encrypt-then-upload path and the stored-ciphertext re-authorization path so both speak to the
     * server through one sequence. Blocking — callers are on IO.
     */
    private fun publish(
        ciphertext: File,
        key: ByteArray,
        fileHash: String,
        plainSize: Int,
        recipients: List<String>,
        attachmentType: Int,
        onProgress: ((Int) -> Unit)?
    ): UploadedAttachment {
        val microToken = globalServices.userManager.getUserData()?.microToken ?: ""

        val existResp = fileShareRepo.isExist(FileExistReq(microToken, fileHash, recipients)).execute()
        if (!existResp.isSuccessful) {
            throw IOException("check attachment exist fail: ${existResp.message()}")
        }
        val res = existResp.body()?.data ?: throw IOException("isExist response data is null")

        if (res.exists) {
            if (res.authorizeId == 0L) throw IOException("isExist returned invalid authorizeId for existing file")
            return UploadedAttachment(
                attachmentId = res.attachmentId,
                authorizeId = res.authorizeId,
                key = key,
                digest = serverDigestOrEmpty(res.cipherHash),
                fileHash = fileHash,
                fileSize = plainSize
            )
        }

        // Real upload: try each pre-signed URL until one succeeds.
        uploadToOss(ciphertext, res.urls?.takeIf { it.isNotEmpty() } ?: listOf(res.url), onProgress)

        val cipherDigest = md5Of(ciphertext)
        val sentCipherHash = FileSystemUtils.bytesToHex(cipherDigest)
        val uploadInfoResp = fileShareRepo.uploadInfo(
            UploadInfoReq(
                token = microToken,
                numbers = recipients,
                attachmentId = res.attachmentId,
                fileHash = fileHash,
                cipherHash = sentCipherHash,
                cipherHashType = "MD5",
                hashAlg = "SHA-256",
                keyAlg = "SHA-512",
                encAlg = "AES-CBC-256",
                fileSize = plainSize,
                attachmentType = attachmentType
            )
        ).execute()
        if (!uploadInfoResp.isSuccessful) {
            throw IOException("uploadInfo fail: ${uploadInfoResp.message()}")
        }
        val info = uploadInfoResp.body()?.data ?: throw IOException("uploadInfo response data is null")
        val authorizeId = info.authorizeId.takeIf { it != 0L }
            ?: throw IOException("uploadInfo response has invalid authorizeId")

        // Read as nullable although both are declared non-null: gson bypasses Kotlin nullability, and
        // a non-dedup uploadInfo response carries authorizeId only — these arrive null at runtime
        // (same defense as AttachmentSendIdentity.localIdOf).
        val respAttachmentId: String? = info.attachmentId
        val respCipherHash: String? = info.cipherHash
        // The server de-duplicates by fileHash, so a concurrent upload of the same plaintext can be
        // answered with the OTHER copy's object; the pointer must describe that copy, not ours.
        val identity = resolveUploadIdentity(
            localAttachmentId = res.attachmentId,
            localDigest = cipherDigest,
            respAttachmentId = respAttachmentId,
            respCipherHash = respCipherHash
        )
        if (identity.adoptedFromServer) {
            L.i { "[AttachmentUploadHelper] adopted server de-duplicated copy attachmentId=${identity.attachmentId} uploadedAs=${res.attachmentId}" }
        } else if (respCipherHash != null && !respCipherHash.equals(sentCipherHash, ignoreCase = true)) {
            // The server answered with a DIFFERENT ciphertext hash that could not be adopted (not
            // hex, or not the local digest's length), so the pointer keeps a digest the server does
            // not hold — the #1184 symptom. Silent here would leave nothing to diagnose it by.
            L.w { "[AttachmentUploadHelper] uploadInfo returned an unusable de-dup cipherHash, keeping local digest attachmentId=${res.attachmentId} hasRespId=${!respAttachmentId.isNullOrBlank()}" }
        }

        // key stays the caller's content-derived key (it decrypts either copy) and fileSize the
        // plaintext size; only the object identity and its digest come from the server.
        return UploadedAttachment(
            attachmentId = identity.attachmentId,
            authorizeId = authorizeId,
            key = key,
            digest = identity.digest,
            fileHash = fileHash,
            fileSize = plainSize
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

    /**
     * The digest of an `isExist` hit: the server's copy is the ONLY payload here, so its cipherHash
     * is the only digest available — but there is no local candidate to fall back to either, and an
     * unusable value must NOT fail the call. These hits sit on the message send main path
     * ([com.difft.android.chat.jobs.PushTextSendJob] → encryptAndUpload → publish), where a server
     * that answers deterministically would make the message permanently unsendable: no retry can
     * change a deterministic response. A send never fails over a server field it can proceed
     * without, so an absent (gson leaves the non-null field null) or malformed value degrades to an
     * empty digest — the one behavior this path is known to ship.
     */
    private fun serverDigestOrEmpty(cipherHash: String?): ByteArray {
        decodeCipherHashOrNull(cipherHash)?.let { return it }
        L.w { "[AttachmentUploadHelper] isExist hit carried an unusable cipherHash, publishing an empty digest blank=${cipherHash.isNullOrBlank()} len=${cipherHash?.length ?: 0}" }
        return ByteArray(0)
    }

    private fun md5Of(file: File, buffer: ByteArray = ByteArray(8192)): ByteArray {
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

/**
 * The server-side file identity: `Base64(SHA-256(key))`, where key = SHA-512(plaintext). Every
 * isExist / upload keys the file by this value, so all derivations MUST go through here — two
 * sites deriving it differently is a silent way to re-lose a file the server still holds.
 */
internal fun attachmentFileHashOf(key: ByteArray): String {
    val digest256 = MessageDigest.getInstance("SHA-256")
    digest256.update(key)
    return com.difft.android.base.utils.Base64.encodeBytes(digest256.digest())
}
