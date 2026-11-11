package com.difft.android.chat.jobs

import com.difft.android.base.utils.sanitizeUrl

import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import org.difft.app.database.wcdb
import com.difft.android.chat.fileshare.DownloadReq
import com.difft.android.chat.fileshare.FileShareRepo
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.VideoProbeState
import com.tencent.wcdb.base.Value
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.synthesizedLocalId
import util.FileSystemUtils
import com.difft.android.chat.attachment.AttachmentRowTarget
import com.difft.android.chat.attachment.attachmentRowKey
import com.difft.android.chat.attachment.attachmentRowTarget
import com.difft.android.chat.attachment.pickLegacyAttachmentRow
import com.difft.android.chat.attachment.migration.ForwardAttachmentMigration
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.FileDecryptionUtil
import com.difft.android.chat.util.MediaUtil
import com.difft.android.chat.util.SaveAttachmentUtil
import com.difft.android.network.NetworkException
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.auth.withInvalidTokenRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.difft.android.base.utils.Base64
import com.difft.android.websocket.api.crypto.CryptoUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit













class DownloadAttachmentJob private constructor(
    parameters: Parameters,
    localId: String,
    private val messageId: String,
    private val attachmentId: String,
    private val serializedFilePath: String,
    private val authorizedId: Long,
    private val fileKey: ByteArray,
    private val autoSave: Boolean
) : com.difft.android.chat.jobs.BaseJob(parameters) {
    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val fileShareRepo: FileShareRepo

        val forwardAttachmentMigration: ForwardAttachmentMigration

        val authTokenProvider: AuthTokenProvider
    }

    




    @Volatile
    private var localId: String? = localId.takeIf { it.isNotEmpty() }

    








    @Volatile
    private var resolvedProgressKey: String? = null

    @Volatile
    private var resolvedDatabaseId: Int? = null

    constructor(
        localId: String,
        messageId: String,
        attachmentId: String,
        filePath: String,
        authorizedId: Long,
        fileKey: ByteArray,
        autoSave: Boolean
    ) : this(
        Parameters.Builder()
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(3)
            .build(), localId, messageId, attachmentId, filePath, authorizedId, fileKey, autoSave
    )

    override fun serialize(): Data {
        val builder = Data.Builder()
            .putString(KEY_MESSAGE_ID, messageId)
            .putString(KEY_ATTACHMENT_ID, attachmentId)
            .putString(KEY_FILE_PATH, serializedFilePath)
            .putLong(KEY_AUTHORIZED_ID, authorizedId)
            .putByteArray(KEY_FILE_KEY, fileKey)
            .putBoolean(KEY_AUTO_SAVE, autoSave)
        localId?.let { builder.putString(KEY_LOCAL_ID, it) }
        return builder.build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    override fun onAdded() {
        updateAttachmentStatus(AttachmentStatus.LOADING.code, resetVideoProbe = true)
        FileUtil.emitProgressUpdate(progressKey(), 0)
    }

    override fun onFailure() {
        L.w { "[DownloadAttachmentJob] onFailure localId=${localId}, messageId=$messageId" }
        updateAttachmentStatus(AttachmentStatus.FAILED.code)
        FileUtil.emitProgressUpdate(progressKey(), -1)
    }

    




    private fun progressKey(): String = resolvedProgressKey ?: localId ?: messageId

    







    private fun updateAttachmentStatus(status: Int, resetVideoProbe: Boolean = false) {
        val condition = resolvedDatabaseId?.let { DBAttachmentModel.databaseId.eq(it) }
            ?: when (val target = attachmentRowTarget(localId, attachmentId, messageId)) {
                is AttachmentRowTarget.ByLocalId -> DBAttachmentModel.localId.eq(target.localId)
                is AttachmentRowTarget.ByIdAndMessage ->
                    DBAttachmentModel.id.eq(target.attachmentId).and(DBAttachmentModel.messageId.eq(target.messageId))

                is AttachmentRowTarget.ById -> DBAttachmentModel.id.eq(target.attachmentId)
            }
        if (resetVideoProbe) {
            
            
            wcdb.attachment.updateRow(
                arrayOf(Value(status), Value(VideoProbeState.NONE)),
                arrayOf(DBAttachmentModel.status, DBAttachmentModel.videoProbeState),
                condition
            )
        } else {
            wcdb.attachment.updateValue(status, DBAttachmentModel.status, condition)
        }
    }

    



    private fun resolveTarget(): Target? {
        val row = findAttachmentRow() ?: return null
        
        
        
        
        
        
        
        val rowKey = attachmentRowKey(row.localId, localId, row.synthesizedLocalId())
        
        val fileName = row.fileName?.takeIf { it.isNotEmpty() } ?: serializedFilePath.substringAfterLast(File.separatorChar, "")
        return Target(
            localId = row.localId?.takeIf { it.isNotEmpty() },
            progressKey = rowKey,
            filePath = FileUtil.getMessageAttachmentFilePath(rowKey) + fileName,
            row = row
        )
    }

    private fun findAttachmentRow(): AttachmentModel? {
        val key = localId
        if (key != null) {
            wcdb.attachment.getFirstObject(DBAttachmentModel.localId.eq(key))?.let { return it }
        }
        
        
        
        
        
        
        
        if (attachmentId.isEmpty()) return null
        val candidates = wcdb.attachment.getAllObjects(DBAttachmentModel.id.eq(attachmentId))
        if (candidates.size > 1) {
            L.w { "[DownloadAttachmentJob] legacy lookup matched ${candidates.size} rows, attachmentId=$attachmentId, messageId=$messageId" }
        }
        val recovered = pickLegacyAttachmentRow(candidates, messageId, { it.messageId }, { it.authorityId }) ?: return null
        
        
        if (key != null && candidates.size == 1 && recovered.localId.isNullOrEmpty()) {
            wcdb.attachment.updateValue(key, DBAttachmentModel.localId, DBAttachmentModel.databaseId.eq(recovered.databaseId))
            recovered.localId = key
            L.i { "[DownloadAttachmentJob] adopted localId for un-backfilled row, localId=$key, attachmentId=$attachmentId" }
        }
        return recovered
    }

    
    private data class Target(val localId: String?, val progressKey: String, val filePath: String, val row: AttachmentModel)

    override suspend fun onRun() {
        val target = resolveTarget()
        if (target == null) {
            
            
            L.w { "[DownloadAttachmentJob] give up, attachment row not found attachmentId=$attachmentId messageId=$messageId" }
            throw IllegalStateException("[DownloadAttachmentJob] attachment row not found, attachmentId=$attachmentId")
        }
        
        
        
        target.localId?.let { localId = it }
        resolvedProgressKey = target.progressKey
        resolvedDatabaseId = target.row.databaseId
        val filePath = target.filePath

        
        
        
        
        
        
        val migration = EntryPointAccessors.fromApplication<EntryPoint>(context).forwardAttachmentMigration
        
        
        
        
        if (migration.materializeFromLegacyAddress(target.row, target.progressKey, filePath)) {
            L.i { "[DownloadAttachmentJob] served from legacy address, rowKey=${target.progressKey}" }
            updateAttachmentStatus(AttachmentStatus.SUCCESS.code, resetVideoProbe = true)
            FileUtil.emitProgressUpdate(progressKey(), 100)
            return
        }

        val fileHashBytes: ByteArray = CryptoUtil.sha256(fileKey)
        val fileHash: String = Base64.encodeBytes(fileHashBytes)
        val buffer = ByteArray(8192)

        val encryptFile = File("$filePath.encrypt")
        
        
        
        
        val downloadTempFile = File("$filePath.encrypt.tmp")
        downloadTempFile.parentFile?.mkdirs()
        downloadTempFile.delete()

        try {
            val entryPoint = EntryPointAccessors.fromApplication<EntryPoint>(context)
            val fileShareRepo = entryPoint.fileShareRepo
            
            val envelope = withContext(Dispatchers.IO) {
                entryPoint.authTokenProvider.withInvalidTokenRetry("attachmentDownload") { token ->
                    val resp = fileShareRepo.download(DownloadReq(token, authorizedId, fileHash, "")).execute()
                    if (!resp.isSuccessful) {
                        throw NetworkException(resp.code(), "[DownloadAttachmentJob] download attachment fail: ${resp.message()}")
                    }
                    resp.body()
                        ?: throw Exception("[DownloadAttachmentJob] download response body is null, messageId: $messageId")
                }
            }

            
            when (val responseStatus = envelope.status) {
                0 -> {
                    
                }
                in EXPIRED_STATUS_CODES -> {
                    
                    
                    
                    L.w { "[DownloadAttachmentJob] file has expired (status code: $responseStatus) messageId=$messageId" }
                    updateAttachmentStatus(AttachmentStatus.EXPIRED.code)
                    FileUtil.emitProgressUpdate(progressKey(), -2)
                    return
                }
                else -> {
                    
                    val errorMessage = when (responseStatus) {
                        1 -> "INVALID_PARAMETER"
                        9 -> "NO_SUCH_FILE"
                        99 -> "OTHER_ERROR"
                        else -> "UNKNOWN_ERROR (status: $responseStatus)"
                    }
                    throw Exception("[DownloadAttachmentJob] download failed with status code $responseStatus: $errorMessage")
                }
            }

            
            val downloadData = envelope.data
            val urlsToTry = downloadData?.urls?.takeIf { it.isNotEmpty() } ?: listOfNotNull(downloadData?.url)
            L.i { "[DownloadAttachmentJob] API response: using ${if (downloadData?.urls?.isNotEmpty() == true) "urls array" else "fallback url"}, total URLs: ${urlsToTry.size}, messageId: $messageId" }

            if (urlsToTry.isEmpty()) {
                throw Exception("[DownloadAttachmentJob] No download URLs available")
            }

            var downloadSuccess = false
            var lastDownloadException: Exception? = null

            for ((index, url) in urlsToTry.withIndex()) {
                try {
                    L.i { "[DownloadAttachmentJob] Attempting download with URL ${index + 1}/${urlsToTry.size}, messageId: $messageId, url: ${url.sanitizeUrl()}" }

                    val downLoadResponse = fileShareRepo.downloadFromOSS(url).execute()

                    if (!downLoadResponse.isSuccessful) {
                        L.w { "[DownloadAttachmentJob] Download failed with URL ${index + 1}/${urlsToTry.size}: ${downLoadResponse.message}, messageId: $messageId, url: ${url.sanitizeUrl()}" }
                        lastDownloadException = Exception("Download from OSS failed: ${downLoadResponse.message}")
                        continue
                    }

                    val downLoadResponseBody = downLoadResponse.body
                    if (downLoadResponseBody == null) {
                        L.w { "[DownloadAttachmentJob] Download response body is null with URL ${index + 1}/${urlsToTry.size}, messageId: $messageId, url: ${url.sanitizeUrl()}" }
                        lastDownloadException = Exception("Download response body is null")
                        continue
                    }

                    val contentLength = downLoadResponseBody.contentLength()
                    var totalBytesRead: Long = 0
                    downLoadResponseBody.byteStream().let { inputStream ->
                        val encryptOutputStream = FileOutputStream(downloadTempFile)

                        try {
                            var bytesRead: Int
                            var lastEmitTime = System.currentTimeMillis()
                            var lastEmitProgress = 0

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                encryptOutputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                val progress = (100.0 * totalBytesRead / contentLength).toInt().coerceAtMost(99)
                                val currentTime = System.currentTimeMillis()
                                
                                if ((currentTime - lastEmitTime >= 50) || (progress - lastEmitProgress >= 5)) {
                                    L.d { "[DownloadAttachmentJob] download progress: $totalBytesRead/$contentLength = $progress%" }
                                    FileUtil.emitProgressUpdate(progressKey(), progress)
                                    lastEmitTime = currentTime
                                    lastEmitProgress = progress
                                }
                            }
                            encryptOutputStream.flush()
                        } finally {
                            inputStream.close()
                            encryptOutputStream.close()
                        }
                    }

                    
                    
                    
                    if (contentLength > 0 && totalBytesRead != contentLength) {
                        throw IOException("[DownloadAttachmentJob] incomplete download: $totalBytesRead/$contentLength bytes, messageId: $messageId")
                    }

                    L.i { "[DownloadAttachmentJob] Download successful with URL ${index + 1}/${urlsToTry.size}, messageId: $messageId, url: ${url.sanitizeUrl()}" }
                    downloadSuccess = true
                    break

                } catch (e: Exception) {
                    L.e { "[DownloadAttachmentJob] Download exception ${e::class.simpleName} ${index + 1}/${urlsToTry.size} messageId=$messageId url=${url.sanitizeUrl()}\n${e.stackTraceToString().sanitizeUrl()}" }
                    lastDownloadException = e
                    
                    downloadTempFile.delete()
                }
            }

            if (!downloadSuccess) {
                throw lastDownloadException ?: Exception("All download URLs failed")
            }

            
            
            
            
            
            
            migration.withAttachmentRowLock(target.progressKey) {
                
                
                
                if (fileKey.size >= 64 && !FileDecryptionUtil.verifyMac(downloadTempFile, fileKey)) {
                    downloadTempFile.delete()
                    throw SecurityException("[DownloadAttachmentJob] MAC verification failed, messageId: $messageId")
                }
                encryptFile.delete()
                if (!downloadTempFile.renameTo(encryptFile)) {
                    downloadTempFile.delete()
                    throw IOException("[DownloadAttachmentJob] rename to final ciphertext failed, messageId: $messageId")
                }
            }

            
            
            
            
            
            
            if (autoSave) {
                L.i { "[DownloadAttachmentJob] auto save to photos: $filePath" }
                if (FileUtil.canWriteToMediaStore()) {
                    val plainExists = File(filePath).exists()
                    val encryptedExists = EncryptedAttachmentAccess.hasEncrypted(filePath)
                    if (plainExists || encryptedExists) {
                        
                        
                        
                        val fileUri = if (encryptedExists) {
                            
                            
                            EncryptedAttachmentAccess.contentUriFromBasePath(filePath)
                        } else {
                            File(filePath).toUri()
                        }
                        val attachment = SaveAttachmentUtil.Attachment(
                            uri = fileUri,
                            contentType = MediaUtil.getMimeType(context, fileUri) ?: "",
                            date = System.currentTimeMillis(),
                            fileName = FileSystemUtils.getFileName(filePath),
                            shouldShowToast = false
                        )
                        SaveAttachmentUtil.saveAttachment(context, attachment)
                    }
                } else {
                    L.w { "[DownloadAttachmentJob] cannot write to media store, auto save skipped: $filePath" }
                }
            }

            updateAttachmentStatus(AttachmentStatus.SUCCESS.code, resetVideoProbe = true)
            FileUtil.emitProgressUpdate(progressKey(), 100)
        } catch (e: Exception) {
            L.w { "[DownloadAttachmentJob] download attachment fail: ${e.stackTraceToString()}" }
            downloadTempFile.delete()
            
            
            throw e
        }
    }

    override fun onShouldRetry(e: Exception): Boolean = e is IOException

    class Factory : Job.Factory<DownloadAttachmentJob> {
        override fun create(parameters: Parameters, data: Data): DownloadAttachmentJob {
            
            val localId = data.getStringOrDefault(KEY_LOCAL_ID, "").orEmpty()
            val messageId = data.getString(KEY_MESSAGE_ID)!!
            val attachmentId = data.getString(KEY_ATTACHMENT_ID)!!
            val filePath = data.getString(KEY_FILE_PATH)!!
            val authorizedId = data.getLongOrDefault(KEY_AUTHORIZED_ID, 0)
            val fileKey = data.getByteArray(KEY_FILE_KEY)!!
            val autoSave = data.getBooleanOrDefault(KEY_AUTO_SAVE, false)
            return DownloadAttachmentJob(
                parameters,
                localId,
                messageId,
                attachmentId,
                filePath,
                authorizedId,
                fileKey,
                autoSave
            )
        }
    }

    companion object {
        const val KEY = "DownloadAttachmentJob"

        



        val EXPIRED_STATUS_CODES: Set<Int> = setOf(2, 12)
        private const val KEY_LOCAL_ID = "local_id"
        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_ATTACHMENT_ID = "attachment_id"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_AUTHORIZED_ID = "authorized_id"
        private const val KEY_FILE_KEY = "file_key"
        private const val KEY_AUTO_SAVE = "auto_save"
    }
}
