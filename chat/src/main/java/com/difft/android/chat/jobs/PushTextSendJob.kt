package com.difft.android.chat.jobs

import android.text.TextUtils
import android.util.Base64
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.MD5Utils
import com.difft.android.base.utils.RecallResultTracker
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.common.SendType
import com.difft.android.chat.fileshare.AttachmentUploadType
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.NotificationType
import com.difft.android.websocket.internal.push.OutgoingPushMessage
import com.difft.android.websocket.internal.push.OutgoingPushMessage.PassThrough
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import com.google.gson.Gson
import com.tencent.wcdb.base.Value
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_ACTIVE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAttachmentMessage
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.keepEncryptedAtRest
import kotlinx.coroutines.launch
import org.difft.app.database.delete
import org.difft.app.database.members
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.wcdb
import org.difft.app.database.writeRoomSendStatus
import org.difft.app.database.writeRoomSendingStatus
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.util.DataMessageCreator
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
class PushTextSendJob @AssistedInject constructor(
    @Assisted
    parameters: Parameters? = null,
    @Assisted
    private val textMessage: TextMessage,
    @Assisted
    private var notification: OutgoingPushMessage.Notification? = null,
    private val gson: Gson,
    private val newSignalServiceMessageSender: NewSignalServiceMessageSender,
    private val dataMessageCreator: DataMessageCreator,
    private val localMessageCreator: LocalMessageCreator,
    private val messageStore: difft.android.messageserialization.MessageStore,
    private val groupUtil: GroupUtil,
) : PushSendJob(parameters ?: buildParameters(textMessage.forWhat, textMessage.isAttachmentMessage())) {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val fileShareRepo: FileShareRepo
        val attachmentUploadHelper: com.difft.android.chat.gif.favorite.AttachmentUploadHelper
    }

    private val attachmentUploadHelper: com.difft.android.chat.gif.favorite.AttachmentUploadHelper
        get() = EntryPointAccessors.fromApplication<EntryPoint>(context).attachmentUploadHelper

    private var startExecuteTime by Delegates.notNull<Long>()

    override fun serialize(): Data {
        val builder = Data.Builder()
            .putString(KEY_MESSAGE_OUT, gson.toJson(textMessage))
        if (notification != null) {
            builder.putString(KEY_NOTIFICATION, gson.toJson(notification))
        } else {
            builder.putString(KEY_NOTIFICATION, "")
        }
        return builder.build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    override fun onAdded() {
        if (textMessage.forWhat is For.Group) {
            val receiverIds = wcdb.groupMemberContactor
                .getAllObjects(DBGroupMemberContactorModel.gid.eq(textMessage.forWhat.id))
                .asSequence()
                .map { it.id }
                .filter { it != globalServices.myId }
                .toMutableSet()
            if (receiverIds.isNotEmpty()) {
                textMessage.receiverIds = gson.toJson(receiverIds)
            }
        }
        updateMessage(SendType.Sending.rawValue)
    }

    public override suspend fun onPushSend() {
        try {
            // 如果有附件，先上传附件
            if (textMessage.isAttachmentMessage()) {
                L.i { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Starting attachment upload, RetryCount: $runAttempt" }
                uploadAttachment()
                L.i { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Attachment upload completed, RetryCount: $runAttempt" }
            }

            startExecuteTime = System.currentTimeMillis()

            val dataMessage = dataMessageCreator.createFrom(textMessage)

            if (notification == null) {
                notification = createNotification()
            }

            L.i { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Send text message to-> ${textMessage.forWhat.id}, RetryCount: $runAttempt" }
            val result = newSignalServiceMessageSender.sendDataMessage(
                textMessage.forWhat,
                textMessage.forWhat,
                dataMessage,
                notification?.toNewNotification(),
            )

            result.success?.let {
                if (textMessage.recall != null) {// recall消息成功，需要删除对应消息
                    wcdb.message.getFirstObject(DBMessageModel.id.eq(textMessage.id))?.delete()
                    RecallResultTracker.emitResult(textMessage.id, true)
                } else {
                    textMessage.systemShowTimestamp = it.systemShowTimestamp
                    textMessage.notifySequenceId = it.notifySequenceId
                    textMessage.sequenceId = it.sequenceId
                }
                updateMessage(SendType.Sent.rawValue)
            } ?: {
                markSendFailed()
            }
        } catch (e: Exception) {
            L.e { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Send message exception, Exception: ${e.stackTraceToString()}, RetryCount: $runAttempt" }

            // 处理已知的特定异常，这些异常不需要重试
            if (e is NonSuccessfulResponseCodeException) {
                val shouldReturn = when (e.code) {
                    430 -> true

                    432 -> { // 非好友限制为每天最多发送三条消息
                        appScope.launch { localMessageCreator.createNonFriendLimitMessage(textMessage.forWhat) }
                        true
                    }

                    404 -> {
                        if (e is AccountOfflineException) {
                            when (e.status) {
                                10105 -> appScope.launch { // 对方离线
                                    localMessageCreator.createOfflineMessage(textMessage.forWhat, TTNotifyMessage.NOTIFY_ACTION_TYPE_OFFLINE)
                                }

                                10110 -> appScope.launch { // 对方账号注销
                                    localMessageCreator.createOfflineMessage(textMessage.forWhat, TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED)
                                }
                            }
                        } else { // 账号不可用
                            appScope.launch { localMessageCreator.createOfflineMessage(textMessage.forWhat, TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED) }
                        }
                        true
                    }
                    
                    else -> false
                }
                
                if (shouldReturn) {
                    markSendFailed()
                    return
                }
            }

            // 对于其他异常，检查是否在重试范围内
            if (onShouldRetry(e)) {
                throw e
            } else {
                L.w { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Exception no need to retry, failing directly, RetryCount: $runAttempt, Exception: ${e.stackTraceToString()}" }
                markSendFailed()
            }
        }
        reportSendCostTime()
    }

    private fun createNotification(): OutgoingPushMessage.Notification {
        val collapseId = MD5Utils.md5AndHexStr(textMessage.timeStamp.toString() + textMessage.fromWho.id + DEFAULT_DEVICE_ID)
        val conversationId = if (textMessage.forWhat is For.Group) {
            Base64.encodeToString(textMessage.forWhat.id.toByteArray(), Base64.NO_WRAP)
        } else {
            textMessage.fromWho.id
        }
        val passThrough = PassThrough(conversationId)
        var mentionedPersons: Array<String>? = null
        val type: Int = if (textMessage.forWhat is For.Group) {
            if (textMessage.recall != null) {
                NotificationType.RECALL_MSG.code
            } else if (!textMessage.mentions.isNullOrEmpty()) {
                if (textMessage.mentions?.firstOrNull()?.uid == MENTIONS_ALL_ID) {
                    NotificationType.GROUP_MENTIONS_ALL.code
                } else {
                    mentionedPersons = textMessage.mentions?.mapNotNull { it.uid }?.toTypedArray()
                    NotificationType.GROUP_MENTIONS_DESTINATION.code
                }
            } else {
                if (textMessage.isAttachmentMessage()) {
                    NotificationType.GROUP_FILE.code
                } else {
                    NotificationType.GROUP_NORMAL.code
                }
            }
        } else {
            if (textMessage.recall != null) {
                NotificationType.RECALL_MSG.code
            } else {
                if (textMessage.isAttachmentMessage()) {
                    NotificationType.PERSONAL_FILE.code
                } else {
                    NotificationType.PERSONAL_NORMAL.code
                }
            }
        }

        return OutgoingPushMessage.Notification(
            OutgoingPushMessage.Args(
                if (textMessage.forWhat is For.Group) textMessage.forWhat.id else "",
                collapseId,
                gson.toJson(passThrough),
                mentionedPersons
            ),
            type
        )
    }

    /**
     * Update message with server timestamps and send status.
     * This method replaces the old updateSendStatus approach by:
     * 1. First trying to insert the message if it doesn't exist (putWhenNonExist)
     * 2. Then updating timestamps and status in one database operation
     */
    private fun updateMessage(status: Int) {
        // Recall and legacy reaction jobs have no DBMessageModel row.
        if (textMessage.recall != null || textMessage.reactions?.isNotEmpty() == true) {
            return
        }

        L.i { "[Message] Updating message ${textMessage.id} - " + "roomId: ${textMessage.forWhat.id}, " + "systemShowTimestamp: ${textMessage.systemShowTimestamp}, " + "status: $status" }

        textMessage.sendType = status

        // Try to insert the message if it doesn't exist
        messageStore.putWhenNonExist(textMessage)

        // The room-level aggregate is only ever ESCALATED at the source. The recompute in
        // WCDBUpdateService is clear-only (it skips rooms stored as NONE), so without the write
        // below a failure would never surface as a conversation-list tag.
        //
        // ORDERING IS LOAD-BEARING: the message row must be committed BEFORE the room row, so the
        // message write MUST stay ABOVE the room write. The clear side is a single conditional
        // UPDATE that re-checks "no failed message" (clearRoomSendStatusIfNoFailure); with
        // message-before-room, no interleaving of the two can lose this FAILED. Emitting the room
        // write first opens a window where a concurrent clear sees no failed message, writes NONE,
        // and the failure is silently lost with no self-heal.
        //
        // That ordering — not atomicity — is what carries the correctness, so the two writes are
        // NOT wrapped in a transaction: a failure in between costs this room its tag only, and the
        // room's next failure restores it. Sent needs no room write at all (the gated clears
        // handle it). SENDING escalates its own independent column under the same ordering rule;
        // the two aggregates never write each other's cell, which is what keeps a concurrent
        // failure-write and sending-clear race-free without any cross-column reasoning.
        writeMessageStatusRow(status)
        if (status == MessageModel.SEND_TYPE_FAILED) {
            wcdb.writeRoomSendStatus(textMessage.forWhat.id, ROOM_SEND_STATUS_FAILED)
        }
        if (status == MessageModel.SEND_TYPE_SENDING) {
            wcdb.writeRoomSendingStatus(textMessage.forWhat.id, ROOM_SENDING_STATUS_ACTIVE)
        }
        RoomChangeTracker.trackRoom(textMessage.forWhat.id, RoomChangeType.MESSAGE)
    }

    /** Update both timestamps and send status in one operation. */
    private fun writeMessageStatusRow(status: Int) {
        wcdb.message.updateRow(
            arrayOf(
                Value(textMessage.systemShowTimestamp),
                Value(textMessage.notifySequenceId),
                Value(textMessage.sequenceId),
                Value(status),
                Value(textMessage.systemShowTimestamp)  // readTime = systemShowTimestamp for self-sent messages
            ),
            arrayOf(
                DBMessageModel.systemShowTimestamp,
                DBMessageModel.notifySequenceId,
                DBMessageModel.sequenceId,
                DBMessageModel.sendType,
                DBMessageModel.readTime
            ),
            DBMessageModel.id.eq(textMessage.id)
        )
    }

    /**
     * Update attachment with all relevant fields (authorityId, key, digest, status).
     * Uses updateRow instead of DELETE + INSERT to avoid WCDB soft-delete issues.
     */
    private fun updateAttachment(attachment: Attachment) {
        wcdb.attachment.updateRow(
            arrayOf(
                Value(attachment.authorityId),
                Value(attachment.key),
                Value(attachment.digest),
                Value(attachment.status)
            ),
            arrayOf(
                DBAttachmentModel.authorityId,
                DBAttachmentModel.key,
                DBAttachmentModel.digest,
                DBAttachmentModel.status
            ),
            DBAttachmentModel.id.eq(attachment.id).and(DBAttachmentModel.messageId.eq(textMessage.id))
        )
    }

    private suspend fun uploadAttachment() {
        val attachment = textMessage.attachments?.firstOrNull() ?: return
        val path = attachment.path ?: return

        attachment.status = AttachmentStatus.LOADING.code
        updateAttachment(attachment)
        FileUtil.emitProgressUpdate(textMessage.id, 0)

        val file = File(path)
        val isAudio = attachment.isAudioMessage()
        // Encrypted-at-rest types (audio + images) keep the .encrypt file on disk (read on demand via
        // the EncryptedAttachmentProvider) and delete the plaintext original. Non-at-rest types
        // (video / generic files) delete the ciphertext after upload and keep the plaintext. The
        // ciphertext path stays "<path>.encrypt".
        val keepEncrypted = textMessage.attachments?.firstOrNull()?.keepEncryptedAtRest() == true
        val encryptPath = "$path.encrypt"

        val recipientIds = ArrayList<String>()
        if (textMessage.forWhat is For.Account) {
            recipientIds.add(textMessage.forWhat.id)
            recipientIds.add(globalServices.myId)
        } else {
            val group = groupUtil.getSingleGroupInfo(textMessage.forWhat.id, false)
            group?.members?.forEach { member -> member.id?.let { recipientIds.add(it) } }
        }

        val attachmentType = when {
            isAudio -> AttachmentUploadType.VOICE
            attachment.size > 200 * 1024 * 1024 -> AttachmentUploadType.LARGE
            else -> AttachmentUploadType.NORMAL
        }

        // Progress throttling (every 50ms or >=5% delta) preserved from the original inline path.
        var lastEmitTime = System.currentTimeMillis()
        var lastEmitProgress = 0

        try {
            val uploaded = attachmentUploadHelper.encryptAndUpload(
                file = file,
                recipients = recipientIds,
                attachmentType = attachmentType,
                encryptPath = encryptPath,
                // At-rest types (audio + images) must RETAIN the ciphertext on disk for on-demand
                // decryption; only non-at-rest types delete it after upload. (Deleting it here for
                // images left neither ciphertext nor plaintext -> broken image on reopen.)
                deleteEncryptFile = !keepEncrypted,
                onProgress = { progress ->
                    val now = System.currentTimeMillis()
                    if ((now - lastEmitTime >= 50) || (progress - lastEmitProgress >= 5)) {
                        FileUtil.emitProgressUpdate(textMessage.id, progress)
                        lastEmitTime = now
                        lastEmitProgress = progress
                    }
                }
            )

            attachment.digest = uploaded.digest
            attachment.authorityId = uploaded.authorizeId
            attachment.key = uploaded.key
            attachment.status = AttachmentStatus.SUCCESS.code
            updateAttachment(attachment)

            if (keepEncrypted) {
                // Keep the .encrypt file for on-demand decryption (bubble, preview, share, save to gallery); delete the plaintext original.
                file.delete()
            }
            FileUtil.emitProgressUpdate(textMessage.id, 100)
        } catch (e: Exception) {
            L.e { "[PushTextSendJob] Upload failed, RetryCount: $runAttempt, Exception: ${e.stackTraceToString()}" }
            attachment.status = AttachmentStatus.FAILED.code
            updateAttachment(attachment)
            FileUtil.emitProgressUpdate(textMessage.id, -1)
            throw e
        }
    }

    override fun onFailure() {
        L.w { "[Message] Job finally failed - MessageID: ${textMessage.id}, Target: ${textMessage.forWhat.id}, RetryCount: $runAttempt, JobID: $id" }
        markSendFailed()
    }

    class Factory : Job.Factory<PushTextSendJob> {

        @dagger.hilt.EntryPoint
        @InstallIn(SingletonComponent::class)
        interface EntryPoint {
            fun getPushTextJobFactory(): PushTextSendJobFactory
            val gson: Gson
        }

        override fun create(parameters: Parameters, data: Data): PushTextSendJob {
            val entryPoint = EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                EntryPoint::class.java
            )
            val gson = entryPoint.gson
            val textMessage = gson.fromJson(
                data.getString(KEY_MESSAGE_OUT),
                TextMessage::class.java
            )
            val notification: OutgoingPushMessage.Notification? =
                data.getString(KEY_NOTIFICATION).takeIf { !TextUtils.isEmpty(it) }
                    ?.let { gson.fromJson(it, OutgoingPushMessage.Notification::class.java) }
            return entryPoint.getPushTextJobFactory()
                .create(parameters, textMessage, notification)
        }
    }

    private fun markSendFailed() {
        if (textMessage.recall != null) {
            RecallResultTracker.emitResult(textMessage.id, false)
        }
        updateMessage(SendType.SentFailed.rawValue)
    }

    private fun reportSendCostTime() {
        L.i { "[Message] send text cost time totally: ${System.currentTimeMillis() - parameters.createTime}, the actually cost time: ${System.currentTimeMillis() - startExecuteTime}" }
    }

    companion object {
        const val KEY = "PushTextSendJob"
        private const val KEY_MESSAGE_OUT = "message_out"
        private const val KEY_NOTIFICATION = "notification"
        private fun buildParameters(forWhat: For, isAttachment: Boolean): Parameters {
            return Parameters.Builder()
                .setQueue("[$KEY::${forWhat.id}]" + (if (isAttachment) "::MEDIA" else ""))
                .setLifespan(TimeUnit.DAYS.toMillis(1))
                .setMaxAttempts(3)  //最大重试次数
//                .addConstraint(NetworkConstraint.KEY)
                .build()
        }
    }
}
