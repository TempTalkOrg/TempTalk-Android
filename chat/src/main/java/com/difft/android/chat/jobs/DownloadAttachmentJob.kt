package com.difft.android.chat.jobs

import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sanitizeUrl

import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import org.difft.app.database.wcdb
import com.difft.android.chat.fileshare.DownloadReq
import com.difft.android.chat.fileshare.FileShareRepo
import difft.android.messageserialization.model.AttachmentStatus
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
import com.difft.android.base.utils.Base64
import com.difft.android.websocket.api.crypto.CryptoUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads one attachment COPY.
 *
 * Identity is [localId] — the attachment row's own local id, which is also the progress-map key the
 * rendering bubble collects on (`TextChatMessage.getAttachmentIdForProgress`). The server-side
 * attachment id is shared by every forwarded copy of the same file, so it can only ever be a lookup
 * hint, never the thing a status write or a file path is derived from.
 *
 * The serialized file path is an INPUT HINT, not the destination: `onRun` re-resolves the target
 * from the attachment row on every attempt, so a job that outlives an upgrade or a migration writes
 * to the file's current address instead of a frozen one.
 */
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
    }

    /**
     * Row identity + progress key. Null only for a job persisted before this key existed; `onRun`
     * recovers it from the row and fills it in, so every later status write and progress emit of
     * that job is keyed exactly like a new one.
     */
    @Volatile
    private var localId: String? = localId.takeIf { it.isNotEmpty() }

    /**
     * The resolved row's per-copy key and its rowid, cached by [onRun] from the ONE lookup
     * [resolveTarget] already performs — no emission may cost a database read.
     *
     * Both stay null until a row is resolved. They exist because the serialized identifiers alone are
     * not enough for a job persisted before [KEY_LOCAL_ID]: such a job would emit progress under its
     * message id while the bubble collects under the row's (possibly synthesized) local id, and would
     * address a status write by an id+messageId pair that a forward-owned row cannot match.
     */
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
        updateAttachmentStatus(AttachmentStatus.LOADING.code)
        FileUtil.emitProgressUpdate(progressKey(), 0)
    }

    override fun onFailure() {
        L.w { "[DownloadAttachmentJob] onFailure localId=${localId}, messageId=$messageId" }
        updateAttachmentStatus(AttachmentStatus.FAILED.code)
        FileUtil.emitProgressUpdate(progressKey(), -1)
    }

    /**
     * Progress-map key — the resolved row's own per-copy key, which is exactly what the rendering
     * bubble derives through `getAttachmentIdForProgress`, and the same key the file is written under.
     *
     * The pre-localId key survives only while no row has been resolved, i.e. in [onAdded]. That is
     * safe rather than a second instance of this bug: [onAdded] fires once, at first submit, so it
     * only ever runs for a job THIS build enqueued — one that carries its copy's localId. A job
     * persisted before [KEY_LOCAL_ID] is restored, never re-added, and by the time it emits anything
     * [onRun] has filled [resolvedProgressKey] in.
     */
    private fun progressKey(): String = resolvedProgressKey ?: localId ?: messageId

    /**
     * Located by the resolved row's rowid once [onRun] has one: the legacy id+messageId locator cannot
     * reach a forward-owned row at all — such a row's `messageId` column is NULL, the owning message
     * id lives on its ForwardModel — so a job persisted before [KEY_LOCAL_ID] would silently write no
     * status for a forwarded copy, and an id-only locator would reach every sibling copy instead.
     * Before resolution only the serialized identifiers exist, which for a job this build enqueued is
     * the copy's own localId.
     */
    private fun updateAttachmentStatus(status: Int) {
        val condition = resolvedDatabaseId?.let { DBAttachmentModel.databaseId.eq(it) }
            ?: when (val target = attachmentRowTarget(localId, attachmentId, messageId)) {
                is AttachmentRowTarget.ByLocalId -> DBAttachmentModel.localId.eq(target.localId)
                is AttachmentRowTarget.ByIdAndMessage ->
                    DBAttachmentModel.id.eq(target.attachmentId).and(DBAttachmentModel.messageId.eq(target.messageId))

                is AttachmentRowTarget.ById -> DBAttachmentModel.id.eq(target.attachmentId)
            }
        wcdb.attachment.updateValue(status, DBAttachmentModel.status, condition)
    }

    /**
     * Where this attempt writes: recomputed from the attachment row, never from the serialized path.
     * Null means the row is gone (message deleted) or unidentifiable — the job then gives up.
     */
    private fun resolveTarget(): Target? {
        val row = findAttachmentRow() ?: return null
        // A row that cannot name a directory yet (localId not backfilled, and this job could not
        // safely adopt one) is addressed by the key the bubble that asked for the download used —
        // still a per-copy address, never the shared legacy one. A job persisted before the localId
        // key existed carries none, and its row's synthesized id is exactly the address every reader
        // of that row already derives (and the one the backfill will persist), so it is the fallback
        // rather than giving up: with no key at all this job would fail permanently for a row the
        // background pass has simply not reached yet.
        val rowKey = attachmentRowKey(row.localId, localId, row.synthesizedLocalId())
        // A row with no file name keeps the caller's "bare directory" shape (trailing separator).
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
        // Either a job persisted before KEY_LOCAL_ID, or a row written before the localId column —
        // the latter names no id and readers synthesize a fresh transient one per read, so the key
        // this job carries matches nothing. Both fall back to the identifiers such a row does have.
        // The server-side id can match several copies; the single legacy key — an owner message id,
        // or the authority id for a single-forward bubble — is the only disambiguator left.
        // Jobs enqueued since the id stopped being seeded carry no id at all: for those the localId
        // lookup above was the only chance, and an `id.eq("")` scan would name arbitrary rows.
        if (attachmentId.isEmpty()) return null
        val candidates = wcdb.attachment.getAllObjects(DBAttachmentModel.id.eq(attachmentId))
        if (candidates.size > 1) {
            L.w { "[DownloadAttachmentJob] legacy lookup matched ${candidates.size} rows, attachmentId=$attachmentId, messageId=$messageId" }
        }
        val recovered = pickLegacyAttachmentRow(candidates, messageId, { it.messageId }, { it.authorityId }) ?: return null
        // Adopt this job's key only when the row is unambiguously the right one: writing it into a
        // sibling copy would hand that copy an identity it was never rendered under.
        if (key != null && candidates.size == 1 && recovered.localId.isNullOrEmpty()) {
            wcdb.attachment.updateValue(key, DBAttachmentModel.localId, DBAttachmentModel.databaseId.eq(recovered.databaseId))
            recovered.localId = key
            L.i { "[DownloadAttachmentJob] adopted localId for un-backfilled row, localId=$key, attachmentId=$attachmentId" }
        }
        return recovered
    }

    /** [progressKey] is the row key [filePath] was built from — the two may never diverge. */
    private data class Target(val localId: String?, val progressKey: String, val filePath: String, val row: AttachmentModel)

    override suspend fun onRun() {
        val target = resolveTarget()
        if (target == null) {
            // Nothing to write to and nothing to update: give up permanently rather than retry into
            // a deleted message (and never fall back to the frozen serialized path).
            L.w { "[DownloadAttachmentJob] give up, attachment row not found attachmentId=$attachmentId messageId=$messageId" }
            throw IllegalStateException("[DownloadAttachmentJob] attachment row not found, attachmentId=$attachmentId")
        }
        // Adopt the row's identity BEFORE any status write or progress emit, so a legacy job behaves
        // exactly like a new one for the rest of its life. The key and the rowid come from the lookup
        // resolveTarget just did, so no later emission or status write re-reads the database.
        target.localId?.let { localId = it }
        resolvedProgressKey = target.progressKey
        resolvedDatabaseId = target.row.databaseId
        val filePath = target.filePath

        // Last chance to find this attachment's file at the address it had before per-copy
        // addressing: the remote file may already have expired, in which case the legacy copy on
        // disk is the only one left and downloading would lose it for good. Only a copy made HERE
        // ends the job — an unusable file already at the current address must still be re-fetched.
        // This is also the funnel every main-thread read miss lands in, which is why no bind or tap
        // path needs a migration call of its own.
        val migration = EntryPointAccessors.fromApplication<EntryPoint>(context).forwardAttachmentMigration
        // The row's EFFECTIVE key, never its localId column: a row the backfill has not reached names
        // no id, and the rescue must still look under its legacy address — that is precisely the row
        // whose file is still there. It is also the key `filePath` was built from, so the lock the
        // migration takes is the same one the placement below takes.
        if (migration.materializeFromLegacyAddress(target.row, target.progressKey, filePath)) {
            L.i { "[DownloadAttachmentJob] served from legacy address, rowKey=${target.progressKey}" }
            updateAttachmentStatus(AttachmentStatus.SUCCESS.code)
            FileUtil.emitProgressUpdate(progressKey(), 100)
            return
        }

        val fileHashBytes: ByteArray = CryptoUtil.sha256(fileKey)
        val fileHash: String = Base64.encodeBytes(fileHashBytes)
        val buffer = ByteArray(8192)

        val encryptFile = File("$filePath.encrypt")
        // Stream into a transient name and rename into place only after the byte-count and MAC checks
        // pass. A partial ciphertext must never be visible under the real name: the structural read
        // check accepts any 16-aligned length, so a rebind mid-download (or after a process death)
        // would treat the truncated file as downloaded and persist SUCCESS for it.
        val downloadTempFile = File("$filePath.encrypt.tmp")
        downloadTempFile.parentFile?.mkdirs()
        downloadTempFile.delete()

        try {
            val fileShareRepo = EntryPointAccessors.fromApplication<EntryPoint>(context).fileShareRepo
            val response = fileShareRepo.download(DownloadReq((globalServices.userManager.getUserData()?.microToken ?: ""), authorizedId, fileHash, "")).execute()

            if (!response.isSuccessful) {
                throw Exception("[DownloadAttachmentJob] download attachment fail: ${response.message()}")
            }

            // Check response status code
            val responseStatus = response.body()?.status
                ?: throw Exception("[DownloadAttachmentJob] download response body is null, messageId: $messageId")
            when (responseStatus) {
                0 -> {
                    // OK, continue with download
                }
                2 -> {
                    // NO_PERMISSION - File has expired
                    L.w { "[DownloadAttachmentJob] file has expired (status code: 2)" }
                    updateAttachmentStatus(AttachmentStatus.EXPIRED.code)
                    FileUtil.emitProgressUpdate(progressKey(), -2)
                    return
                }
                else -> {
                    // Handle other error status codes
                    val errorMessage = when (responseStatus) {
                        1 -> "INVALID_PARAMETER"
                        9 -> "NO_SUCH_FILE"
                        12 -> "INVALID_FILE"
                        99 -> "OTHER_ERROR"
                        else -> "UNKNOWN_ERROR (status: $responseStatus)"
                    }
                    throw Exception("[DownloadAttachmentJob] download failed with status code $responseStatus: $errorMessage")
                }
            }

            // 获取URL列表，优先使用urls数组，回退到单个url
            val downloadData = response.body()?.data
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
                                // Update every 50ms or when progress changes by >=5%
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

                    // Guard against a silently-truncated stream (early EOF on a dropped connection):
                    // an incomplete .encrypt would later decrypt to garbage. Treat it as a failure so
                    // we fall through to the next URL / retry instead of persisting a corrupt file.
                    if (contentLength > 0 && totalBytesRead != contentLength) {
                        throw IOException("[DownloadAttachmentJob] incomplete download: $totalBytesRead/$contentLength bytes, messageId: $messageId")
                    }

                    L.i { "[DownloadAttachmentJob] Download successful with URL ${index + 1}/${urlsToTry.size}, messageId: $messageId, url: ${url.sanitizeUrl()}" }
                    downloadSuccess = true
                    break

                } catch (e: Exception) {
                    L.e { "[DownloadAttachmentJob] Download exception ${e::class.simpleName} ${index + 1}/${urlsToTry.size} messageId=$messageId url=${url.sanitizeUrl()}\n${e.stackTraceToString().sanitizeUrl()}" }
                    lastDownloadException = e
                    // Discard the partial transfer before the next URL attempt.
                    downloadTempFile.delete()
                }
            }

            if (!downloadSuccess) {
                throw lastDownloadException ?: Exception("All download URLs failed")
            }

            // The migration may be moving this same copy's legacy directory onto the address below,
            // so the placement runs under the copy's row lock — the one the migration itself takes.
            // Keyed by the row key `filePath` was built from, not by `localId`, which is null for a
            // row the backfill has not reached and would silently take no lock at all.
            // Blocking IO only: no suspension point may enter this block, or the lock would be
            // released on a different thread than it was taken on.
            migration.withAttachmentRowLock(target.progressKey) {
                // Every type is encrypted at rest (see EncryptedAttachmentAccess): keep the ciphertext
                // on disk and decrypt on demand. Verify integrity ONCE now so consumers can read later
                // without re-verifying, then promote the verified bytes to the final name.
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

            // Auto save to photos if enabled
            // Note: autoSave decision is made at call site considering:
            // 1. Attachment type (image/video only)
            // 2. Confidential mode
            // 3. Conversation-level saveToPhotos setting
            // 4. Global saveToPhotos setting
            if (autoSave) {
                L.i { "[DownloadAttachmentJob] auto save to photos: $filePath" }
                if (FileUtil.canWriteToMediaStore()) {
                    val plainExists = File(filePath).exists()
                    val encryptedExists = EncryptedAttachmentAccess.hasEncrypted(filePath)
                    if (plainExists || encryptedExists) {
                        // Prefer the durable ciphertext (content uri) when present; only fall back to
                        // the plaintext file for legacy plaintext-only data. Reading the .encrypt on
                        // demand avoids racing with any concurrent plaintext deletion.
                        val fileUri = if (encryptedExists) {
                            // Segment comes from the path we just wrote, so a sibling copy sharing
                            // the server-side id can never be the one that gets exported.
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

            updateAttachmentStatus(AttachmentStatus.SUCCESS.code)
            FileUtil.emitProgressUpdate(progressKey(), 100)
        } catch (e: Exception) {
            L.w { "[DownloadAttachmentJob] download attachment fail: ${e.stackTraceToString()}" }
            downloadTempFile.delete()
            // Don't mark FAILED here — let onFailure() handle it after all retries exhausted.
            // Marking FAILED during retry would briefly show retry button to user.
            throw e
        }
    }

    override fun onShouldRetry(e: Exception): Boolean = e is IOException

    class Factory : Job.Factory<DownloadAttachmentJob> {
        override fun create(parameters: Parameters, data: Data): DownloadAttachmentJob {
            // Absent for a job persisted before this key existed; onRun recovers it from the row.
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
        private const val KEY_LOCAL_ID = "local_id"
        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_ATTACHMENT_ID = "attachment_id"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_AUTHORIZED_ID = "authorized_id"
        private const val KEY_FILE_KEY = "file_key"
        private const val KEY_AUTO_SAVE = "auto_save"
    }
}
