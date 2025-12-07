package com.difft.android.chat.fileshare

import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.TlsVersion
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface FileShareService {
    @POST("v1/file/isExists")
    fun isExist(@Body request: FileExistReq): Call<BaseResponse<FileExistResp?>>

    @POST("v1/file/uploadInfo")
    fun uploadInfo(@Body uploadInfoReq: UploadInfoReq): Call<BaseResponse<UploadInfoResp>>

    /**
     * Download file from server
     * Status codes:
     * - 0: OK
     * - 1: INVALID_PARAMETER
     * - 2: NO_PERMISSION (File expired)
     * - 9: NO_SUCH_FILE
     * - 12: INVALID_FILE
     * - 99: OTHER_ERROR
     */
    @POST("v1/file/download")
    fun download(@Body downloadReq: DownloadReq): Call<BaseResponse<DownloadResp>>
}

class FileShareRepo @Inject constructor() {
    @Inject
    @ChativeHttpClientModule.FileShare
    lateinit var fileShareClient: ChativeHttpClient

    private val customConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3) // 指定TLS版本为TLS 1.2  TLS 1.3
        .build()

    private var ossClient = OkHttpClient.Builder()
        .connectTimeout(TimeUnit.SECONDS.toMillis(30), TimeUnit.MILLISECONDS)
        .readTimeout(TimeUnit.SECONDS.toMillis(300), TimeUnit.MILLISECONDS)
        .writeTimeout(TimeUnit.SECONDS.toMillis(300), TimeUnit.MILLISECONDS)
        .connectionSpecs(listOf(customConnectionSpec))
        .build()

    private val fileShareService by lazy {
        fileShareClient.getService(FileShareService::class.java)
    }

    fun isExist(request: FileExistReq): Call<BaseResponse<FileExistResp?>> {
        return fileShareService.isExist(request)
    }

    fun uploadInfo(request: UploadInfoReq): Call<BaseResponse<UploadInfoResp>> {
        return fileShareService.uploadInfo(request)
    }

    fun download(request: DownloadReq): Call<BaseResponse<DownloadResp>> {
        return fileShareService.download(request)
    }

    fun uploadToOSS(url: String, file: RequestBody): okhttp3.Call {
        val request: Request = Request.Builder()
            .url(url)
            .method("PUT", file)
            .build()

        return ossClient.newCall(request)
    }

    fun downloadFromOSS(url: String): okhttp3.Call {
        val request: Request = Request.Builder()
            .url(url)
            .build()
        return ossClient.newCall(request)
    }

    private fun getRequestParamBody(content: String): RequestBody {
        return RequestBody.create("multipart/form-data".toMediaTypeOrNull(), content)
    }
}

data class FileExistReq(
    val token: String,
    val fileHash: String,
    val numbers: List<String>?
)

data class FileExistResp(
    val attachmentId: String,
    val authorizeId: Long,
    val cipherHash: String,
    val cipherHashType: String,
    val exists: Boolean,
    val url: String
)

data class UploadInfoReq(
    val token: String,
    val numbers: List<String>,
    val attachmentId: String,
    val fileHash: String,
    val cipherHash: String,
    val cipherHashType: String,
    val hashAlg: String,
    val keyAlg: String,
    val encAlg: String,
    val fileSize: Int,
    val attachmentType: Int = AttachmentUploadType.NORMAL
)

data class UploadInfoResp(
    val attachmentId: String,
    val authorizeId: Long,
    val cipherHash: String,
    val cipherHashType: String,
    val exists: Boolean,
    val url: Any
)

data class DownloadReq(
    val token: String,
    val authorizeId: Long,
    val fileHash: String,
    val gid: String
)

data class DownloadResp(
    val attachmentId: String,
    val encAlg: String,
    val fileHash: String,
    val fileSize: Int,
    val hashAlg: String,
    val keyAlg: String,
    val url: String
)

/**
 * 附件类型定义
 * - NORMAL: 普通附件 (默认值 0)
 * - VOICE: 语音消息附件 (1)
 * - LARGE: 大附件，大于200M (2)
 */
object AttachmentUploadType {
    const val NORMAL = 0    // 普通附件
    const val VOICE = 1     // 语音消息附件
    const val LARGE = 2     // 大附件（>200M）
}
