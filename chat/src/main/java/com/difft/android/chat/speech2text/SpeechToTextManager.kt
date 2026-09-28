package com.difft.android.chat.speech2text


import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.fileshare.AttachmentUploadType
import com.difft.android.chat.fileshare.FileExistReq
import com.difft.android.chat.fileshare.FileExistResp
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.fileshare.UploadInfoReq
import com.difft.android.chat.fileshare.UploadInfoResp
import difft.android.messageserialization.model.Attachment
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.UrlManager
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.auth.withInvalidTokenRetry
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ProgressRequestBody
import com.difft.android.network.requests.SpeechToTextRequestBody
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import util.FileSystemUtils
import com.difft.android.base.utils.Base64
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechToTextManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
){

    companion object {
        const val SPEECH_TO_TEXT = "speech2Text"
    }

    @Inject
    lateinit var urlManager: UrlManager

    @Inject
    lateinit var fileShareRepo: FileShareRepo

    @Inject
    lateinit var authTokenProvider: AuthTokenProvider

    @Inject
    @ChativeHttpClientModule.Default
    lateinit var defaultHttpClient: ChativeHttpClient

    /**
     * @param attachmentPath on-disk base path of [attachment], resolved by the caller through
     *   `AttachmentPathResolver`. Passed in rather than read from `Attachment.path`: that column is a
     *   frozen absolute path and is not the addressing authority (a forwarded voice message is
     *   addressed per copy).
     */
    fun speechToText(
        scope: CoroutineScope,
        context: Context,
        attachment: Attachment,
        attachmentPath: String,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
    ){
        convert(scope, attachment, attachmentPath, onSuccess, onFailure)
    }


    /**
     * Convert speech to text. Uses the caller-provided scope for lifecycle management.
     */
    private fun convert(scope: CoroutineScope, attachment: Attachment, attachmentPath: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val keyDigest = MessageDigest.getInstance("SHA-256").digest(attachment.key)
                    val fileHash = android.util.Base64.encodeToString(keyDigest, android.util.Base64.NO_WRAP)
                    val fileExistResp = requestFileExist(fileHash).data

                    if (fileExistResp?.exists == true) {
                        // Already exists, directly request speech-to-text service
                        val requestBody = SpeechToTextRequestBody(
                            authorizeId = fileExistResp.authorizeId.toString(),
                            key = Base64.encodeBytes(attachment.key, Base64.NO_OPTIONS)
                        )
                        defaultHttpClient.httpService.voiceToText(requestBody)
                    } else {
                        // Does not exist, upload audio file first then request speech-to-text service
                        val urlString = fileExistResp?.url
                        val encryptedFile = File("$attachmentPath.encrypt")
                        if (urlString.isNullOrEmpty() || attachmentPath.isEmpty() || !encryptedFile.exists()) {
                            throw Exception("file is not exist")
                        }

                        // Upload encrypted audio file
                        val body: RequestBody = ProgressRequestBody(encryptedFile, null, null)
                        val uploadToOSSCallResponse = fileShareRepo.uploadToOSS(urlString, body).execute()
                        if (!uploadToOSSCallResponse.isSuccessful) {
                            throw Exception("uploadToOSSCall execute failed:${uploadToOSSCallResponse.message}")
                        }

                        // Upload file info to server
                        val keyDigest2 = MessageDigest.getInstance("SHA-256").digest(attachment.key)
                        val fileHash2 = android.util.Base64.encodeToString(keyDigest2, android.util.Base64.NO_WRAP)
                        val uploadInfoEnvelope = requestUploadInfo(attachment, fileExistResp.attachmentId, fileHash2)

                        // Upload succeeded, request speech-to-text service
                        val authorityId = uploadInfoEnvelope.data?.authorizeId ?: 0
                        val requestBody = SpeechToTextRequestBody(
                            authorizeId = authorityId.toString(),
                            key = Base64.encodeBytes(attachment.key, Base64.NO_OPTIONS)
                        )
                        defaultHttpClient.httpService.voiceToText(requestBody)
                    }
                }

                if (result.status == 0 && result.data != null) {
                    L.d { "[SpeechToTextManager] speechToText response onSuccess:${result}" }
                    val concatenatedText = result.data?.segments?.map { segment ->
                        segment.text ?: ""
                    }?.joinToString(" ")
                    L.d { "[SpeechToTextManager] speechToText concatenatedText:${concatenatedText}" }
                    onSuccess(concatenatedText.toString())
                } else {
                    L.e { "[SpeechToTextManager] speechToText response error:${result}" }
                    onFailure(Exception(result.toString()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[SpeechToTextManager] speechToText response onFailure:${e}" }
                onFailure(e)
            }
        }
    }

    /** isExist for the voice attachment, with the body token refreshed and retried once (issue #1235). */
    private suspend fun requestFileExist(fileHash: String): BaseResponse<FileExistResp?> =
        authTokenProvider.withInvalidTokenRetry("s2t.isExist") { token ->
            val resp = fileShareRepo.isExist(FileExistReq(token, fileHash, listOf(SPEECH_TO_TEXT))).execute()
            if (!resp.isSuccessful) {
                throw NetworkException(resp.code(), "File permission application failed")
            }
            resp.body() ?: throw IOException("isExist response body is null")
        }

    /**
     * uploadInfo for the voice attachment. A SEPARATE retry scope from [requestFileExist]: the OSS
     * upload happens in between, so one wrapper spanning both would re-upload the audio file.
     */
    private suspend fun requestUploadInfo(
        attachment: Attachment,
        attachmentId: String,
        fileHash: String,
    ): BaseResponse<UploadInfoResp> =
        authTokenProvider.withInvalidTokenRetry("s2t.uploadInfo") { token ->
            val resp = fileShareRepo.uploadInfo(
                UploadInfoReq(
                    token = token,
                    numbers = listOf(SPEECH_TO_TEXT),
                    attachmentId = attachmentId,
                    fileHash = fileHash,
                    cipherHash = FileSystemUtils.bytesToHex(attachment.digest!!),
                    cipherHashType = "MD5",
                    hashAlg = "SHA-256",
                    keyAlg = "SHA-512",
                    encAlg = "AES-CBC-256",
                    fileSize = attachment.size,
                    attachmentType = AttachmentUploadType.VOICE
                )
            ).execute()
            if (!resp.isSuccessful) {
                L.w { "[SpeechToTextManager] upload attachment fail${resp.message()}" }
                throw NetworkException(resp.code(), "upload attachment fail:${resp.message()}")
            }
            resp.body() ?: throw IOException("uploadInfo response body is null")
        }
}
