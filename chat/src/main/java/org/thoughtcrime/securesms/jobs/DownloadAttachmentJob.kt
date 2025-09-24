package org.thoughtcrime.securesms.jobs

import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.fileshare.DownloadReq
import com.difft.android.chat.fileshare.FileShareRepo
import difft.android.messageserialization.model.AttachmentStatus
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.difft.app.database.models.DBAttachmentModel
import util.FileUtils
import util.concurrent.TTExecutors
import org.thoughtcrime.securesms.jobmanager.Data
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.util.FileDecryptionUtil
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.SaveAttachmentTask
import org.thoughtcrime.securesms.util.StorageUtil
import org.thoughtcrime.securesms.util.whispersystems.Base64
import com.difft.android.websocket.api.crypto.CryptoUtil
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadAttachmentJob private constructor(
    parameters: Parameters,
    private val messageId: String,
    private val attachmentId: String,
    private val filePath: String,
    private val authorizedId: Long,
    private val fileKey: ByteArray,
    private val shouldDecrypt: Boolean = true,
    private val autoSave: Boolean
) : BaseJob(parameters) {
    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val fileShareRepo: FileShareRepo
    }

    constructor(
        messageId: String,
        attachmentId: String,
        filePath: String,
        authorizedId: Long,
        fileKey: ByteArray,
        shouldDecrypt: Boolean = true,
        autoSave: Boolean
    ) : this(
        Parameters.Builder()
            .setLifespan(TimeUnit.DAYS.toMillis(5))
            .setMaxAttempts(Parameters.UNLIMITED)
            .build(), messageId, attachmentId, filePath, authorizedId, fileKey, shouldDecrypt, autoSave
    )

    override fun serialize(): Data {
        return Data.Builder()
            .putString(KEY_MESSAGE_ID, messageId)
            .putString(KEY_ATTACHMENT_ID, attachmentId)
            .putString(KEY_FILE_PATH, filePath)
            .putLong(KEY_AUTHORIZED_ID, authorizedId)
            .putByteArray(KEY_FILE_KEY, fileKey)
            .putBoolean(KEY_SHOULD_DECRYPT, shouldDecrypt)
            .putBoolean(KEY_AUTO_SAVE, autoSave)
            .build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    override fun onAdded() {
        updateAttachmentStatus(AttachmentStatus.LOADING.code)
        FileUtil.emitProgressUpdate(messageId, 0)
    }

    override fun onFailure() {
        L.w { "[DownloadAttachmentJob] onFailure" }
        updateAttachmentStatus(AttachmentStatus.FAILED.code)
        FileUtil.emitProgressUpdate(messageId, -1)
    }

    private fun updateAttachmentStatus(status: Int) {
        wcdb.attachment.updateValue(
            status,
            DBAttachmentModel.status,
            DBAttachmentModel.id.eq(attachmentId)
        )
    }

    override fun onRun() {
        val fileHashBytes: ByteArray = CryptoUtil.sha256(fileKey)
        val fileHash: String = Base64.encodeBytes(fileHashBytes)
        val buffer = ByteArray(8192)

        val encryptFile = File("$filePath.encrypt")
        if (!encryptFile.exists()) {
            encryptFile.createNewFile()
        } else {
            encryptFile.delete()
        }

        try {
            val fileShareRepo = EntryPointAccessors.fromApplication<EntryPoint>(context).fileShareRepo
            val response = fileShareRepo.download(DownloadReq(SecureSharedPrefsUtil.getToken(), authorizedId, fileHash, "")).execute()

            if (!response.isSuccessful) {
                throw Exception("[DownloadAttachmentJob] download attachment fail: ${response.message()}")
            }

            val url = response.body()?.data?.url ?: throw Exception("[DownloadAttachmentJob] download URL is null")
            val downLoadResponse = fileShareRepo.downloadFromOSS(url).execute()

            if (!downLoadResponse.isSuccessful) {
                throw Exception("[DownloadAttachmentJob] download attachment fail: ${downLoadResponse.message}")
            }

            val downLoadResponseBody = downLoadResponse.body ?: throw Exception("[DownloadAttachmentJob] download response body is null")
            downLoadResponseBody.byteStream().let { inputStream ->
                val encryptOutputStream = FileOutputStream(encryptFile)

                try {
                    var bytesRead: Int
                    var totalBytesRead: Long = 0
                    var lastEmitTime = System.currentTimeMillis()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        encryptOutputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        val progress = (100.0 * totalBytesRead / downLoadResponseBody.contentLength()).toInt()
                        val currentTime = System.currentTimeMillis()
                        if ((currentTime - lastEmitTime >= 500)) {
                            L.d { "[DownloadAttachmentJob]===download progress======$totalBytesRead===${downLoadResponseBody.contentLength()}===$progress%" }
                            FileUtil.emitProgressUpdate(messageId, progress)
                            lastEmitTime = currentTime
                        }
                    }
                } finally {
                    inputStream.close()
                    encryptOutputStream.close()
                }

                if (shouldDecrypt) {
                    // 只在需要解密且下载成功后才创建realFile
                    val realFile = File(filePath).apply {
                        if (!exists()) {
                            createNewFile()
                        } else {
                            delete()
                        }
                    }
                    // 解密后删除加密文件
                    FileDecryptionUtil.decryptFile(encryptFile, realFile, fileKey)
                    encryptFile.delete()
                }

                //判断是否需要自动保存到相册
                if (autoSave && globalServices.userManager.getUserData()?.saveToPhotos == true) {
                    L.i { "[DownloadAttachmentJob] need to save: $filePath" }
                    if (StorageUtil.canWriteToMediaStore()) {
                        if (File(filePath).exists()) {
                            val fileUri = File(filePath).toUri()
                            val saveAttachment = SaveAttachmentTask.Attachment(
                                fileUri,
                                MediaUtil.getMimeType(context, fileUri) ?: "",
                                System.currentTimeMillis(),
                                FileUtils.getFileName(filePath),
                                false,
                                false
                            )
                            SaveAttachmentTask(context).executeOnExecutor(TTExecutors.BOUNDED, saveAttachment)
                        }
                    } else {
                        L.w { "[DownloadAttachmentJob] cannot write to media store, auto save skipped: $filePath" }
                    }
                } else {
                    L.i { "[DownloadAttachmentJob] no need to save: $filePath" }
                }

                updateAttachmentStatus(AttachmentStatus.SUCCESS.code)
                FileUtil.emitProgressUpdate(messageId, 100)
            }
        } catch (e: Exception) {
            L.w { "[DownloadAttachmentJob] download attachment fail: ${e.stackTraceToString()}" }
            updateAttachmentStatus(AttachmentStatus.FAILED.code)
            FileUtil.emitProgressUpdate(messageId, -1)
            encryptFile.delete()
            throw e
        }
    }

    override fun onShouldRetry(e: java.lang.Exception): Boolean {
        return false
    }

    class Factory : Job.Factory<DownloadAttachmentJob> {
        override fun create(parameters: Parameters, data: Data): DownloadAttachmentJob {
            val messageId = data.getString(KEY_MESSAGE_ID)
            val attachmentId = data.getString(KEY_ATTACHMENT_ID)
            val filePath = data.getString(KEY_FILE_PATH)
            val authorizedId = data.getLongOrDefault(KEY_AUTHORIZED_ID, 0)
            val fileKey = data.getByteArray(KEY_FILE_KEY)
            val shouldDecrypt = data.getBooleanOrDefault(KEY_SHOULD_DECRYPT, true)
            val autoSave = data.getBooleanOrDefault(KEY_AUTO_SAVE, false)
            return DownloadAttachmentJob(
                parameters,
                messageId,
                attachmentId,
                filePath,
                authorizedId,
                fileKey,
                shouldDecrypt,
                autoSave
            )
        }
    }

    companion object {
        const val KEY = "DownloadAttachmentJob"
        private val TAG = L.tag(DownloadAttachmentJob::class.java)
        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_ATTACHMENT_ID = "attachment_id"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_AUTHORIZED_ID = "authorized_id"
        private const val KEY_FILE_KEY = "file_key"
        private const val KEY_SHOULD_DECRYPT = "should_decrypt"
        private const val KEY_AUTO_SAVE = "auto_save"
    }
}
