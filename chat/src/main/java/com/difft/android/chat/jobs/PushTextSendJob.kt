package com.difft.android.chat.jobs

import android.text.TextUtils
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.RecallResultTracker
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.common.SendType
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.OutgoingPushMessage
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import com.google.gson.Gson
import com.tencent.wcdb.base.Value
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_ACTIVE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAttachmentMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.difft.app.database.delete
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
    messagingGate: MessagingGate,
) : PushSendJob(parameters ?: buildParameters(textMessage.forWhat, textMessage.isAttachmentMessage()), messagingGate) {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val fileShareRepo: FileShareRepo
        val attachmentUploadHelper: com.difft.android.chat.gif.favorite.AttachmentUploadHelper
    }

    private val attachmentUploadHelper: com.difft.android.chat.gif.favorite.AttachmentUploadHelper
        get() = EntryPointAccessors.fromApplication<EntryPoint>(context).attachmentUploadHelper

    
    private val attachmentIdentity = AttachmentSendIdentity(textMessage.id)

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
            if (textMessage.isAttachmentMessage()) {
                L.i { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Starting attachment upload, RetryCount: $runAttempt" }
                uploadAttachment()
                L.i { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Attachment upload completed, RetryCount: $runAttempt" }
            }

            
            
            
            
            
            
            
            textMessage.forwardContext?.let {
                ForwardAttachmentReupload.repairMissingAuthorizations(
                    forwardContext = it,
                    messageId = textMessage.id,
                    
                    
                    
                    recipients = { sendRecipients(textMessage.forWhat, groupUtil) },
                    uploadHelper = attachmentUploadHelper,
                    identity = attachmentIdentity
                )
            }

            startExecuteTime = System.currentTimeMillis()

            val dataMessage = dataMessageCreator.createFrom(textMessage)

            if (notification == null) {
                notification = createNotification()
            }

            
            
            
            val admission = admitSend() ?: return

            L.i { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Send text message to-> ${textMessage.forWhat.id}, RetryCount: $runAttempt" }
            val result = newSignalServiceMessageSender.sendDataMessage(
                textMessage.forWhat,
                textMessage.forWhat,
                dataMessage,
                notification?.toNewNotification(),
                admission,
            )

            result.success?.let {
                if (textMessage.forWhat is For.Group && textMessage.recall == null && textMessage.reactions.isNullOrEmpty()) {
                    groupUtil.clearExpiry(textMessage.forWhat.id, "sent")
                }
                if (textMessage.recall != null) {
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
            
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            L.e { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Send message exception, Exception: ${e.stackTraceToString()}, RetryCount: $runAttempt" }

            if (e is NonSuccessfulResponseCodeException) {
                val shouldReturn = when (e.code) {
                    430 -> true

                    432 -> { 
                        appScope.launch { localMessageCreator.createNonFriendLimitMessage(textMessage.forWhat) }
                        true
                    }

                    404 -> {
                        if (e is AccountOfflineException) {
                            when (e.status) {
                                10105 -> appScope.launch {
                                    localMessageCreator.createOfflineMessage(textMessage.forWhat, TTNotifyMessage.NOTIFY_ACTION_TYPE_OFFLINE)
                                }

                                10110 -> appScope.launch {
                                    localMessageCreator.createOfflineMessage(textMessage.forWhat, TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED)
                                }
                            }
                        } else {
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

            if (onShouldRetry(e)) {
                throw e
            } else {
                L.w { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Exception no need to retry, failing directly, RetryCount: $runAttempt, Exception: ${e.stackTraceToString()}" }
                markSendFailed()
            }
        }
        reportSendCostTime()
    }

    private fun createNotification(): OutgoingPushMessage.Notification = buildTextMessageNotification(textMessage, gson)

    
    private fun updateMessage(status: Int) {
        
        if (textMessage.recall != null || textMessage.reactions?.isNotEmpty() == true) {
            return
        }

        L.i { "[Message] Updating message ${textMessage.id} - " + "roomId: ${textMessage.forWhat.id}, " + "systemShowTimestamp: ${textMessage.systemShowTimestamp}, " + "status: $status" }

        textMessage.sendType = status

        messageStore.putWhenNonExist(textMessage)

        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        writeMessageStatusRow(status)
        if (status == MessageModel.SEND_TYPE_FAILED) {
            wcdb.writeRoomSendStatus(textMessage.forWhat.id, ROOM_SEND_STATUS_FAILED)
        }
        if (status == MessageModel.SEND_TYPE_SENDING) {
            wcdb.writeRoomSendingStatus(textMessage.forWhat.id, ROOM_SENDING_STATUS_ACTIVE)
        }
        RoomChangeTracker.trackRoom(textMessage.forWhat.id, RoomChangeType.MESSAGE)
    }

    
    private fun writeMessageStatusRow(status: Int) {
        wcdb.message.updateRow(
            arrayOf(
                Value(textMessage.systemShowTimestamp),
                Value(textMessage.notifySequenceId),
                Value(textMessage.sequenceId),
                Value(status),
                Value(textMessage.systemShowTimestamp)  
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

    private suspend fun uploadAttachment() {
        val attachment = textMessage.attachments?.firstOrNull() ?: return

        
        
        
        
        
        
        val progressKey = attachmentIdentity.localIdOf(attachment)
            ?: attachmentIdentity.recoverLocalId(attachment)
            ?: textMessage.id

        
        
        
        
        
        
        
        val path = try {
            attachmentIdentity.uploadSourcePath(attachment) ?: return
        } catch (e: Exception) {
            L.e { "[PushTextSendJob] upload source unresolvable, messageId=${textMessage.id}: ${e.stackTraceToString()}" }
            attachment.status = AttachmentStatus.FAILED.code
            attachmentIdentity.updateRow(attachment)
            FileUtil.emitProgressUpdate(progressKey, -1)
            throw e
        }

        attachment.status = AttachmentStatus.LOADING.code
        attachmentIdentity.updateRow(attachment)
        FileUtil.emitProgressUpdate(progressKey, 0)

        val file = File(path)
        
        
        
        val encryptPath = "$path.encrypt"

        val recipientIds = sendRecipients(textMessage.forWhat, groupUtil)
        val attachmentType = attachmentUploadType(attachment)

        
        var lastEmitTime = System.currentTimeMillis()
        var lastEmitProgress = 0

        try {
            val uploaded = attachmentUploadHelper.encryptAndUpload(
                file = file,
                recipients = recipientIds,
                attachmentType = attachmentType,
                encryptPath = encryptPath,
                
                
                deleteEncryptFile = false,
                onProgress = { progress ->
                    val now = System.currentTimeMillis()
                    if ((now - lastEmitTime >= 50) || (progress - lastEmitProgress >= 5)) {
                        FileUtil.emitProgressUpdate(progressKey, progress)
                        lastEmitTime = now
                        lastEmitProgress = progress
                    }
                }
            )

            attachment.digest = uploaded.digest
            attachment.authorityId = uploaded.authorizeId
            attachment.key = uploaded.key
            attachment.status = AttachmentStatus.SUCCESS.code
            attachmentIdentity.updateRow(attachment)

            
            
            file.delete()
            FileUtil.emitProgressUpdate(progressKey, 100)
        } catch (e: Exception) {
            L.e { "[PushTextSendJob] Upload failed, RetryCount: $runAttempt, Exception: ${e.stackTraceToString()}" }
            attachment.status = AttachmentStatus.FAILED.code
            attachmentIdentity.updateRow(attachment)
            FileUtil.emitProgressUpdate(progressKey, -1)
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
                .setMaxAttempts(3)
                .build()
        }
    }
}
