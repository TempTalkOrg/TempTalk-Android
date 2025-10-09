package org.thoughtcrime.securesms.jobs

import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.MD5Utils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import org.difft.app.database.delete
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import org.difft.app.database.wcdb
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.fileshare.FileExistReq
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.chat.fileshare.UploadInfoReq
import com.difft.android.chat.group.GroupUtil
import difft.android.messageserialization.For
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAttachmentMessage
import difft.android.messageserialization.model.isAudioMessage
import com.difft.android.network.requests.ProgressListener
import com.difft.android.network.requests.ProgressRequestBody
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.RequestBody
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBMessageModel
import util.FileUtils
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobmanager.Data
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.util.DataMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.NotificationType
import com.difft.android.websocket.internal.push.OutgoingPushMessage
import com.difft.android.websocket.internal.push.OutgoingPushMessage.PassThrough
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.toAttachmentModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.properties.Delegates

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
) : PushSendJob(parameters ?: buildParameters(textMessage.forWhat, textMessage.isAttachmentMessage())) {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val fileShareRepo: FileShareRepo
    }

    private var startExecuteTime by Delegates.notNull<Long>()

    override fun serialize(): Data {
        val builder = Data.Builder()
            .putString(KEY_MESSAGE_OUT, gson.toJson(textMessage))
        if (notification != null) {
            builder.putString(KEY_NOTIFICATION, Gson().toJson(notification))
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
                textMessage.receiverIds = globalServices.gson.toJson(receiverIds)
            }
        }
        updateSendStatus(SendType.Sending.rawValue)
    }

    public override fun onPushSend() {
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
                } else if (!textMessage.reactions.isNullOrEmpty()) { // emoji reaction消息成功，更新数据
                    textMessage.reactions?.firstOrNull()?.let { reaction ->
                        ApplicationDependencies.getMessageStore().updateMessageReaction(textMessage.forWhat.id, reaction, null, null)
                    }
                } else {
                    textMessage.systemShowTimestamp = it.systemShowTimestamp
                    textMessage.notifySequenceId = it.notifySequenceId
                    textMessage.sequenceId = it.sequenceId
                }
                updateSendStatus(SendType.Sent.rawValue)
            } ?: {
                updateSendStatus(SendType.SentFailed.rawValue)
            }
        } catch (e: Exception) {
            L.e { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Send message exception, Exception: ${e.stackTraceToString()}, RetryCount: $runAttempt" }

            // 处理已知的特定异常，这些异常不需要重试
            if (e is NonSuccessfulResponseCodeException) {
                when (e.code) {
                    430 -> {
                        updateSendStatus(SendType.SentFailed.rawValue)
                        return // 不重试，直接返回
                    }

                    432 -> { // 非好友限制为每天最多发送三条消息
                        ContactorUtil.createNonFriendLimitMessage(textMessage.forWhat)
                        updateSendStatus(SendType.SentFailed.rawValue)
                        return // 不重试，直接返回
                    }

                    404 -> {
                        if (e is AccountOfflineException) {
                            when (e.status) {
                                10105 -> { // 对方离线
                                    ContactorUtil.createOfflineMessage(
                                        textMessage.forWhat,
                                        TTNotifyMessage.NOTIFY_ACTION_TYPE_OFFLINE
                                    )
                                }

                                10110 -> { // 对方账号注销
                                    ContactorUtil.createOfflineMessage(
                                        textMessage.forWhat,
                                        TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED
                                    )
                                }
                            }
                        } else { // 账号不可用
                            ContactorUtil.createOfflineMessage(
                                textMessage.forWhat,
                                TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED
                            )
                        }
                        updateSendStatus(SendType.SentFailed.rawValue)
                        return // 不重试，直接返回
                    }
                }
            }

            // 对于其他异常，检查是否在重试范围内
            if (onShouldRetry(e)) {
                throw e
            } else {
                L.w { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} Exception no need to retry, failing directly, RetryCount: $runAttempt, Exception: ${e.stackTraceToString()}" }
                updateSendStatus(SendType.SentFailed.rawValue)
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

    private fun updateSendStatus(status: Int) {
        if (textMessage.recall != null) return
        if (textMessage.screenShot != null) return
        if (textMessage.reactions?.isNotEmpty() == true) return

        ApplicationDependencies.getMessageStore().updateSendStatus(textMessage, status).blockingAwait()
    }

    private fun updateAttachmentStatus(status: Int) {
        val attachment = textMessage.attachments?.firstOrNull() ?: return
        attachment.status = status

        val newAttachment = attachment.toAttachmentModel(textMessage.id)
        wcdb.attachment.deleteObjects(DBAttachmentModel.id.eq(attachment.id).and(DBAttachmentModel.messageId.eq(textMessage.id)))
        wcdb.attachment.insertObject(newAttachment)
    }

    private fun uploadAttachment() {
        val attachment = textMessage.attachments?.firstOrNull() ?: return
        attachment.path ?: return

        updateAttachmentStatus(AttachmentStatus.LOADING.code)
        FileUtil.emitProgressUpdate(textMessage.id, 0)

        val buffer = ByteArray(8192)
        var bytesRead: Int

        val file = File(attachment.path ?: "")
        var inputStream: FileInputStream? = null
        val encryptFile = File(attachment.path + ".encrypt")
        val encryptOutputStream = FileOutputStream(encryptFile)
        val encryptInputStream = FileInputStream(encryptFile)

        var cipherInputStream: CipherInputStream? = null

        val fileShareRepo = EntryPointAccessors.fromApplication<EntryPoint>(context).fileShareRepo

        try {
            val digest512 = MessageDigest.getInstance("SHA-512")
            inputStream = FileInputStream(file)
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest512.update(buffer, 0, bytesRead)
            }

            val originKey = digest512.digest()
            val digest256 = MessageDigest.getInstance("SHA-256")
            digest256.update(originKey)
            val fileHashByte = digest256.digest()
            val fileHash = com.difft.android.websocket.util.Base64.encodeBytes(fileHashByte)

            val iv = ByteArray(16)
            Random().nextBytes(iv)
            val ivParameterSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val aesKeySpec = SecretKeySpec(originKey, 0, 32, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, aesKeySpec, ivParameterSpec)
            val mac = Mac.getInstance("HmacSHA256")
            val macKeySpec = SecretKeySpec(originKey, 32, 32, "HmacSHA256")
            mac.init(macKeySpec)
            mac.update(iv)

            encryptOutputStream.write(iv)

            inputStream = FileInputStream(file)
            cipherInputStream = CipherInputStream(inputStream, cipher)

            while (cipherInputStream.read(buffer).also { bytesRead = it } != -1) {
                encryptOutputStream.write(buffer, 0, bytesRead)
                mac.update(buffer, 0, bytesRead)
            }

            val macDigest = mac.doFinal()
            encryptOutputStream.write(macDigest)

            val fileSize = Math.toIntExact(attachment.size.toLong())
            val recipientIds = ArrayList<String>()
            if (textMessage.forWhat is For.Account) {
                recipientIds.add(textMessage.forWhat.id)
                recipientIds.add(globalServices.myId)
            } else {
                val group = GroupUtil.getSingleGroupInfo(context, textMessage.forWhat.id, false).blockingFirst()
                if (group.isPresent) {
                    group.get().members.forEach { member ->
                        recipientIds.add(member.id)
                    }
                }
            }

            var lastEmitTime = System.currentTimeMillis()
            val fileExistResponse = fileShareRepo.isExist(FileExistReq(SecureSharedPrefsUtil.getToken(), fileHash, recipientIds)).execute()
            if (fileExistResponse.isSuccessful) {
                fileExistResponse.body()?.data?.let { res ->
                    if (!res.exists) {
                        val urlString = res.url
                        val body: RequestBody = ProgressRequestBody(encryptFile, null, object : ProgressListener {
                            override fun onProgress(bytesRead: Long, contentLength: Long, progress: Int) {
                                val currentTime = System.currentTimeMillis()
                                if ((currentTime - lastEmitTime >= 200)) {
                                    FileUtil.emitProgressUpdate(textMessage.id, progress)
                                    lastEmitTime = currentTime
                                }
                            }
                        })
                        val uploadToOSSCallResponse = fileShareRepo.uploadToOSS(urlString, body).execute()
                        if (!uploadToOSSCallResponse.isSuccessful) {
                            throw IOException("uploadToOSSCall execute fail" + uploadToOSSCallResponse.message)
                        }

                        val md5 = MessageDigest.getInstance("md5")
                        while (encryptInputStream.read(buffer).also { bytesRead = it } != -1) {
                            md5.update(buffer, 0, bytesRead)
                        }
                        attachment.digest = md5.digest()

                        val uploadInfoCallResponse = fileShareRepo.uploadInfo(
                            UploadInfoReq(
                                SecureSharedPrefsUtil.getToken(), recipientIds,
                                res.attachmentId, fileHash,
                                FileUtils.bytesToHex(attachment.digest), "MD5", "SHA-256", "SHA-512", "AES-CBC-256", fileSize
                            )
                        ).execute()
                        if (uploadInfoCallResponse.isSuccessful) {
                            attachment.authorityId = uploadInfoCallResponse.body()?.data?.authorizeId ?: 0
                        } else {
                            L.w { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} upload attachment fail${uploadInfoCallResponse.message()}" }
                            throw IOException("upload attachment fail" + uploadInfoCallResponse.message())
                        }
                    } else {
                        attachment.digest = FileUtils.decodeDigestHex(res.cipherHash)
                        attachment.authorityId = res.authorizeId
                    }
                }
            } else {
                L.w { "[Message][PushTextSendJob] timeStamp:${textMessage.timeStamp} upload attachment fail${fileExistResponse.message()}" }
                throw IOException("check attachment is exist fail" + fileExistResponse.message())
            }
            attachment.key = originKey

            updateAttachmentStatus(AttachmentStatus.SUCCESS.code)

            //语音消息不要删除加密文件
            if (textMessage.attachments?.firstOrNull()?.isAudioMessage() != true) {
                encryptFile.delete()
            }
            FileUtil.emitProgressUpdate(textMessage.id, 100)
        } catch (e: Exception) {
            L.e { "[PushTextSendJob] Upload failed, RetryCount: $runAttempt, Exception: ${e.stackTraceToString()}" }
            updateAttachmentStatus(AttachmentStatus.FAILED.code)
            FileUtil.emitProgressUpdate(textMessage.id, -1)
            throw e
        } finally {
            inputStream?.close()
            cipherInputStream?.close()
            encryptOutputStream.close()
            encryptInputStream.close()
        }
    }

    override fun onFailure() {
        L.w { "[Message] Job最终失败 - MessageID: ${textMessage.id}, Target: ${textMessage.forWhat.id}, 最终重试次数: ${getRunAttempt()}, JobID: ${getId()}" }
        updateSendStatus(SendType.SentFailed.rawValue)
    }

    class Factory : Job.Factory<PushTextSendJob> {

        @dagger.hilt.EntryPoint
        @InstallIn(dagger.hilt.components.SingletonComponent::class)
        interface EntryPoint {
            fun getPushTextJobFactory(): PushTextSendJobFactory
        }

        override fun create(parameters: Parameters, data: Data): PushTextSendJob {
            val valueTypeAdapter = RuntimeTypeAdapterFactory.of(For::class.java)
                .registerSubtype(For.Account::class.java)
                .registerSubtype(For.Group::class.java)
            val gson = GsonBuilder().registerTypeAdapterFactory(valueTypeAdapter).create()
            val textMessage = gson.fromJson(
                data.getString(KEY_MESSAGE_OUT),
                TextMessage::class.java
            )
            val gson2 = Gson()
            var notification: OutgoingPushMessage.Notification? = null
            val notificationJson = data.getString(KEY_NOTIFICATION)
            if (!TextUtils.isEmpty(notificationJson)) {
                notification =
                    gson2.fromJson(notificationJson, OutgoingPushMessage.Notification::class.java)
            }
            return EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                EntryPoint::class.java
            ).getPushTextJobFactory().create(parameters, textMessage, notification)
        }
    }

    private fun reportSendCostTime() {
        L.i { "[Message] send text cost time totally: ${System.currentTimeMillis() - parameters.createTime}, the actually cost time: ${System.currentTimeMillis() - startExecuteTime}" }
        Bundle().apply {
            putLong(MESSAGE_SENT_COST_TIME, System.currentTimeMillis() - parameters.createTime)
            putLong(MESSAGE_SENT_COST_TIME_ACTUALLY, System.currentTimeMillis() - startExecuteTime)
        }.let {
            FirebaseAnalytics.getInstance(context).logEvent(MESSAGE_SENT_COST_TIME, it)
        }
    }

    companion object {
        const val KEY = "PushTextSendJob"
        private const val KEY_MESSAGE_OUT = "message_out"
        private const val KEY_NOTIFICATION = "notification"
        private const val MESSAGE_SENT_COST_TIME = "message_sent_cost_time"
        private const val MESSAGE_SENT_COST_TIME_ACTUALLY = "message_sent_cost_time_actually"

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
