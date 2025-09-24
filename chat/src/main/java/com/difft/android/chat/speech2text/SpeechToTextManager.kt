package com.difft.android.chat.speech2text

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.chat.fileshare.FileExistReq
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.fileshare.UploadInfoReq
import difft.android.messageserialization.model.Attachment
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ProgressRequestBody
import com.difft.android.network.requests.SpeechToTextRequestBody
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.RequestBody
import util.FileUtils
import com.difft.android.websocket.util.Base64
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechToTextManager @Inject constructor(
    @ApplicationContext private val context: Context,
){

    companion object {
        const val SPEECH_TO_TEXT = "speech2Text"
    }

    @Inject
    lateinit var urlManager: UrlManager

    @Inject
    lateinit var fileShareRepo: FileShareRepo

    @Inject
    @ChativeHttpClientModule.Default
    lateinit var chatHttpClient: ChativeHttpClient

    fun speechToText(
        context: Context,
        attachment: Attachment,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
    ){
        convert(context, attachment, onSuccess, onFailure)
    }


    private fun convert(context: Context, attachment: Attachment, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        Single.fromCallable {
            val keyDigest = MessageDigest.getInstance("SHA-256").digest(attachment.key)
            val fileHash = android.util.Base64.encodeToString(keyDigest, android.util.Base64.NO_WRAP)
            fileShareRepo.isExist(FileExistReq(SecureSharedPrefsUtil.getToken(), fileHash, listOf(SPEECH_TO_TEXT)))
                .execute()
        }.subscribeOn(Schedulers.io())
            .concatMap { fileExistResponse ->
                if(fileExistResponse.isSuccessful) {
                    val fileExistResp = fileExistResponse.body()?.data
                    L.i { "[SpeechToTextManager]  fileExistResp:${fileExistResp}" }
                    if(fileExistResp?.exists == true) {
                        // 已存在，直接请求语音转文字服务
                        val requestBody = SpeechToTextRequestBody(
                            authorizeId = fileExistResp.authorizeId.toString(),
                            key= Base64.encodeBytes(attachment.key, Base64.NO_OPTIONS)
                        )
                        chatHttpClient.httpService.voiceToText(SecureSharedPrefsUtil.getToken(), requestBody)
                    }else{
                        // 不存在，先请求文件权限，拿到文件链接后上传音频文件到OSS，然后语音转文字服务
                        val urlString = fileExistResp?.url
                        val filePath = attachment.path
                        val encryptedFile = File("$filePath.encrypt")
                        if(urlString.isNullOrEmpty() || filePath.isNullOrEmpty() || !encryptedFile.exists()){
                            // 文件不存在，直接返回失败结果
                            Single.just(BaseResponse(ver = 1, status = -1, reason = "file is not exist", data = null))
                        }else {
                            // 上传本地已加密的音频文件
                            val body: RequestBody = ProgressRequestBody(encryptedFile, null, null)
                            val uploadToOSSCallResponse = fileShareRepo.uploadToOSS(urlString, body).execute()
                            if (!uploadToOSSCallResponse.isSuccessful) {
                                Single.just(BaseResponse(ver = 1, status = -1, reason = "uploadToOSSCall execute failed:${uploadToOSSCallResponse.message}", data = null))
                            }else {
                                // 上传文件信息到服务器
                                val keyDigest = MessageDigest.getInstance("SHA-256").digest(attachment.key)
                                val fileHash = android.util.Base64.encodeToString(keyDigest, android.util.Base64.NO_WRAP)
                                val uploadInfoCallResponse = fileShareRepo.uploadInfo(
                                    UploadInfoReq(
                                        SecureSharedPrefsUtil.getToken(), listOf(SPEECH_TO_TEXT),
                                        fileExistResp.attachmentId, fileHash,
                                        FileUtils.bytesToHex(attachment.digest), "MD5", "SHA-256", "SHA-512", "AES-CBC-256", attachment.size
                                    )
                                ).execute()

                                if (uploadInfoCallResponse.isSuccessful) {
                                    // 上传文件信息到服务器成功，请求语音转文字服务
                                    val authorityId = uploadInfoCallResponse.body()?.data?.authorizeId ?: 0
                                    val requestBody = SpeechToTextRequestBody(
                                        authorizeId = authorityId.toString(),
                                        key= Base64.encodeBytes(attachment.key, Base64.NO_OPTIONS)
                                    )
                                    chatHttpClient.httpService.voiceToText(SecureSharedPrefsUtil.getToken(), requestBody)
                                } else {
                                    L.w { "[SpeechToTextManager] upload attachment fail${uploadInfoCallResponse.message()}" }
                                    Single.just(BaseResponse(ver = 1, status = -1, reason = "upload attachment fail:${uploadInfoCallResponse.message()}", data = null))
                                }
                            }
                        }
                    }
                }else{
                    Single.just(BaseResponse(ver = 1, status = -1, reason = "File permission application failed", data = null))
                }
            }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(context as LifecycleOwner))
            .subscribe({
                if(it.status == 0 && it.data != null){
                    L.d { "[SpeechToTextManager] speechToText response onSuccess:${it}" }
                    val concatenatedText = it.data?.segments?.map { segment ->
                        segment.text ?: ""
                    }?.joinToString(" ")
                    L.d { "[SpeechToTextManager] speechToText concatenatedText:${concatenatedText}" }
                    onSuccess(concatenatedText.toString())
                }else{
                    L.e { "[SpeechToTextManager] speechToText response error:${it}" }
                    onFailure(Exception(it.toString()))
                }
            }, {
                L.e { "[SpeechToTextManager]speechToText response onFailure:${it}" }
                onFailure(it as Exception)
            })
    }
}