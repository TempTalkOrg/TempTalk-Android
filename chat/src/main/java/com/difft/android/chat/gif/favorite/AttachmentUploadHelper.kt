package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.sanitizeUrl
import com.difft.android.chat.attachment.decodeCipherHashOrNull
import com.difft.android.chat.attachment.resolveUploadIdentity
import com.difft.android.chat.fileshare.AttachmentUploadType
import com.difft.android.chat.fileshare.FileExistReq
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.fileshare.UploadInfoReq
import com.difft.android.network.NetworkException
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.auth.withInvalidTokenRetry
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





data class UploadedAttachment(
    val attachmentId: String,
    val authorizeId: Long,
    
    val key: ByteArray,
    
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






@Singleton
class AttachmentUploadHelper @Inject constructor(
    private val fileShareRepo: FileShareRepo,
    private val authTokenProvider: AuthTokenProvider,
) {
    










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
            
            val digest512 = MessageDigest.getInstance("SHA-512")
            
            FileInputStream(file).use { digestIn ->
                while (digestIn.read(buffer).also { bytesRead = it } != -1) {
                    digest512.update(buffer, 0, bytesRead)
                }
            }
            val originKey = digest512.digest()
            val fileHash = attachmentFileHashOf(originKey)

            
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

    










    suspend fun existingByFileHash(fileHash: String, recipients: List<String>): UploadedAttachment? =
        withContext(Dispatchers.IO) {
            val envelope = authTokenProvider.withInvalidTokenRetry("isExist") { token ->
                val resp = fileShareRepo.isExist(FileExistReq(token, fileHash, recipients)).execute()
                if (!resp.isSuccessful) throw NetworkException(resp.code(), "isExist fail: ${resp.message()}")
                resp.body() ?: throw IOException("isExist response body is null")
            }
            val res = envelope.data ?: throw IOException("isExist response data is null")
            if (!res.exists) return@withContext null
            if (res.authorizeId == 0L) throw IOException("isExist returned invalid authorizeId for existing file")
            UploadedAttachment(
                attachmentId = res.attachmentId,
                authorizeId = res.authorizeId,
                key = ByteArray(0),          
                digest = serverDigestOrEmpty(res.cipherHash),
                fileHash = fileHash,
                fileSize = 0                 
            )
        }

    















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

    




    private suspend fun publish(
        ciphertext: File,
        key: ByteArray,
        fileHash: String,
        plainSize: Int,
        recipients: List<String>,
        attachmentType: Int,
        onProgress: ((Int) -> Unit)?
    ): UploadedAttachment {
        val existEnvelope = authTokenProvider.withInvalidTokenRetry("publish.isExist") { token ->
            val resp = fileShareRepo.isExist(FileExistReq(token, fileHash, recipients)).execute()
            if (!resp.isSuccessful) {
                throw NetworkException(resp.code(), "check attachment exist fail: ${resp.message()}")
            }
            resp.body() ?: throw IOException("isExist response body is null")
        }
        val res = existEnvelope.data ?: throw IOException("isExist response data is null")

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

        
        uploadToOss(ciphertext, res.urls?.takeIf { it.isNotEmpty() } ?: listOf(res.url), onProgress)

        val cipherDigest = md5Of(ciphertext)
        val sentCipherHash = FileSystemUtils.bytesToHex(cipherDigest)
        
        
        val uploadInfoEnvelope = authTokenProvider.withInvalidTokenRetry("publish.uploadInfo") { token ->
            val resp = fileShareRepo.uploadInfo(
                UploadInfoReq(
                    token = token,
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
            if (!resp.isSuccessful) {
                throw NetworkException(resp.code(), "uploadInfo fail: ${resp.message()}")
            }
            resp.body() ?: throw IOException("uploadInfo response body is null")
        }
        val info = uploadInfoEnvelope.data ?: throw IOException("uploadInfo response data is null")
        val authorizeId = info.authorizeId.takeIf { it != 0L }
            ?: throw IOException("uploadInfo response has invalid authorizeId")

        
        
        
        val respAttachmentId: String? = info.attachmentId
        val respCipherHash: String? = info.cipherHash
        
        
        val identity = resolveUploadIdentity(
            localAttachmentId = res.attachmentId,
            localDigest = cipherDigest,
            respAttachmentId = respAttachmentId,
            respCipherHash = respCipherHash
        )
        if (identity.adoptedFromServer) {
            L.i { "[AttachmentUploadHelper] adopted server de-duplicated copy attachmentId=${identity.attachmentId} uploadedAs=${res.attachmentId}" }
        } else if (respCipherHash != null && !respCipherHash.equals(sentCipherHash, ignoreCase = true)) {
            
            L.w { "[AttachmentUploadHelper] uploadInfo returned an unusable de-dup cipherHash, keeping local digest attachmentId=${res.attachmentId} hasRespId=${!respAttachmentId.isNullOrBlank()}" }
        }

        
        
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



internal fun attachmentFileHashOf(key: ByteArray): String {
    val digest256 = MessageDigest.getInstance("SHA-256")
    digest256.update(key)
    return com.difft.android.base.utils.Base64.encodeBytes(digest256.digest())
}
