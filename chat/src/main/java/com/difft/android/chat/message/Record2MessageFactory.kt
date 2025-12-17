package com.difft.android.chat.message

import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import org.difft.app.database.attachment
import org.difft.app.database.card
import org.difft.app.database.forwardContext
import org.difft.app.database.getContactorFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.base.utils.globalServices
import org.difft.app.database.mentions
import org.difft.app.database.quote
import org.difft.app.database.reactions
import org.difft.app.database.sharedContacts
import org.difft.app.database.speechToTextData
import org.difft.app.database.translateData
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.NotifyMessage
import difft.android.messageserialization.model.ReadInfoOfDb
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAttachmentMessage
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import com.google.common.reflect.TypeToken
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import com.google.protobuf.ByteString
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.ReadInfoModel
import com.difft.android.websocket.api.messages.TTNotifyMessage
import org.difft.app.database.models.MessageModel
import org.whispersystems.signalservice.internal.push.DataMessageKt.forward
import org.whispersystems.signalservice.internal.push.DataMessageKt.mention
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.attachmentPointer
import org.whispersystems.signalservice.internal.push.card


val READ_INFO_TYPE = object : TypeToken<Map<String, ReadInfoOfDb>>() {}.type

fun parseReadInfo(readInfo: String?): Map<String, ReadInfoOfDb>? {
    if (readInfo.isNullOrEmpty()) return null
    return try {
        globalServices.gson.fromJson<Map<String, ReadInfoOfDb>>(readInfo, READ_INFO_TYPE)
    } catch (e: Exception) {
        L.e { "Failed to parse readInfo: ${e.stackTraceToString()}" }
        null
    }
}

val RECEIVER_ID_TYPE = object : TypeToken<List<String>>() {}.type

fun parseReceiverIds(receiverIds: String?): List<String>? {
    if (receiverIds.isNullOrEmpty()) return null
    return try {
        globalServices.gson.fromJson<List<String>>(receiverIds, RECEIVER_ID_TYPE)
    } catch (e: Exception) {
        L.e { "Failed to parse ReceiverIds: ${e.stackTraceToString()}" }
        null
    }
}

fun generateMessageTwo(
    forWhat: For,
    record: MessageModel,
    contactor: List<ContactorModel>,
    readInfoList: List<ReadInfoModel>?,
    isLargeGroup: Boolean = false
): ChatMessage? {
    val myId = globalServices.myId
    val isFromMySelf = globalServices.myId == record.fromWho
    val authorId = record.fromWho
    val author =
        contactor.firstOrNull { it.id == record.fromWho } ?: ContactorModel().also {
            it.id = record.fromWho
        }
    return if (record.type == 0 || record.type == 1) {
        TextChatMessage().apply {
            this.id = record.id
            this.authorId = authorId
            this.isMine = isFromMySelf
            this.sendStatus = record.sendType
            this.timeStamp = record.timeStamp
            this.systemShowTimestamp = record.systemShowTimestamp
            this.notifySequenceId = record.notifySequenceId
            this.readMaxSId = record.sequenceId
            this.mode = record.mode
            this.message = if (record.forwardContext() != null) "" else record.messageText
            this.attachment = record.attachment()
            this.quote = record.quote()
            this.forwardContext = record.forwardContext()
            this.card = record.card()
            this.mentions = record.mentions()
            this.reactions = record.reactions()
            this.sharedContacts = record.sharedContacts()
            if (record.fromWho == globalServices.myId) {
                if (forWhat is For.Account) {
                    if (forWhat.id == globalServices.myId) {
                        this.readStatus = 1
                    } else {
                        val readInfo = readInfoList?.firstOrNull()
                        this.readStatus = if ((readInfo?.readPosition ?: 0) >= systemShowTimestamp) 1 else 0
                    }
                } else {
                    // 群聊消息的已读状态处理
                    if (record.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                        // 机密消息的已读处理
                        val readInfos = parseReadInfo(record.readInfo)
                        this.readContactNumber = readInfos?.size ?: 0
                    } else {
                        // 使用传入的 isLargeGroup 参数判断
                        if (isLargeGroup) {
                            // 大群：直接标记为已读
                            val receiverIds = parseReceiverIds(record.receiverIds)
                            this.readContactNumber = receiverIds?.size ?: 0
                            this.readStatus = 1
                        } else {
                            // 普通群：计算实际已读状态
                            val readInfos = readInfoList?.filter { it.readPosition >= systemShowTimestamp }
                            val receiverIds = parseReceiverIds(record.receiverIds)

                            if (receiverIds == null || readInfos == null) {
                                this.readContactNumber = 0
                                this.readStatus = 0
                            } else {
                                val readContactIds = readInfos.map { it.uid }.toSet()
                                // 计算已读人数：统计有多少接收者已经读了消息
                                this.readContactNumber = receiverIds.count { receiverId -> readContactIds.contains(receiverId) }
                                // 设置已读状态：所有接收者都已读则为1，否则为0
                                this.readStatus = if (readContactIds.containsAll(receiverIds)) 1 else 0
                            }
                        }
                    }
                }
            }
            this.playStatus = record.playStatus
            this.translateData = record.translateData()
            this.speechToTextData = record.speechToTextData()
            this.criticalAlertType = record.criticalAlertType
        }
    } else if (record.type == 2) {
        NotifyChatMessage().apply {
            this.id = record.id
            this.authorId = author.id
            this.isMine = isFromMySelf
            this.sendStatus = record.sendType
            this.timeStamp = record.timeStamp
            this.systemShowTimestamp = record.systemShowTimestamp
            this.notifySequenceId = record.notifySequenceId
            this.readMaxSId = record.sequenceId
            this.notifyMessage =
                Gson().fromJson(record.messageText, TTNotifyMessage::class.java)
        }
    } else {
        L.e { "generateMessage message can't find type! ${record.timeStamp}  type:${record.type}" }
        FirebaseCrashlytics.getInstance().recordException(Exception("generateMessage message can't find type! ${record.timeStamp}  type:${record.type}"))
        null
    }
}

fun createForward(forward: Forward): SignalServiceProtos.DataMessage.Forward {
    val attachments1 = mutableListOf<SignalServiceProtos.AttachmentPointer>().apply {
        forward.attachments?.let { attachments ->
            attachments.forEach { attachment ->
                this.add(
                    attachmentPointer {
                        cdnNumber = 0
                        id = attachment.authorityId
                        contentType = attachment.contentType
                        key = ByteString.copyFrom(attachment.key)
                        size = attachment.size
                        width = attachment.width
                        height = attachment.height
                        digest = ByteString.copyFrom(attachment.digest)
                        attachment.fileName?.let(::fileName::set)
                        uploadTimestamp = System.currentTimeMillis()
                        flags = attachment.flags
                    }
                )
            }
        }
    }

    val forwards1 = mutableListOf<SignalServiceProtos.DataMessage.Forward>().apply {
        forward.forwards?.let { forward ->
            forward.forEach { forward1 ->
                this.add(createForward(forward1))
            }
        }
    }

    val card1 =
        forward.card?.let {
            card {
                it.appId?.let { appId = it }
                it.cardId?.let { cardId = it }
                version = it.version
                it.creator?.let { creator = it }
                timestamp = it.timestamp
                it.content?.let { content = it }
                contentType = it.contentType
                type = it.type
                fixedWidth = it.fixedWidth
            }
        }

    val mentions1 = mutableListOf<SignalServiceProtos.DataMessage.Mention>().apply {
        forward.mentions?.let { mentions ->
            mentions.forEach { mention ->
                this.add(
                    mention {
                        start = mention.start
                        length = mention.length
                        mention.uid?.let { uid = it }
                        type = SignalServiceProtos.DataMessage.Mention.Type.valueOf(mention.type)
                    }
                )
            }
        }
    }

    return forward {
        id = forward.id
        type = forward.type
        isFromGroup = forward.isFromGroup
        author = forward.author
        forward.text?.let(::text::set)
        if (attachments1.isNotEmpty()) {
            attachments.addAll(attachments1)
        }
        if (forwards1.isNotEmpty()) {
            forwards.addAll(forwards1)
        }
        card1?.let { card = it }
        if (mentions1.isNotEmpty()) {
            mentions.addAll(mentions1)
        }
        serverTimestamp = forward.serverTimestamp
    }
}

fun getRecordMessageContentTwo(record: Message?, isGroup: Boolean, messageSenderName: String?): String {
    val senderName = if (isGroup && !TextUtils.isEmpty(messageSenderName)) "$messageSenderName: " else ""
    return if (record?.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
        senderName + ResUtils.getString(R.string.chat_message_confidential_message)
    } else {
        when (record) {
            is TextMessage -> {
                if (record.isAttachmentMessage()) {
                    if (record.attachments?.firstOrNull()?.isImage() == true) {
                        senderName + ResUtils.getString(R.string.chat_message_image)
                    } else if (record.attachments?.firstOrNull()?.isVideo() == true) {
                        senderName + ResUtils.getString(R.string.chat_message_video)
                    } else if (record.attachments?.firstOrNull()?.isAudioMessage() == true || record.attachments?.firstOrNull()?.isAudioFile() == true) {
                        senderName + ResUtils.getString(R.string.chat_message_audio)
                    } else {
                        senderName + ResUtils.getString(R.string.chat_message_attachment)
                    }
                } else if (!record.sharedContact.isNullOrEmpty()) {
                    senderName + ResUtils.getString(R.string.chat_message_contact_card)
                } else if (record.forwardContext != null) {
                    senderName + "[" + ResUtils.getString(R.string.chat_history) + "]"
                } else {
                    senderName + record.text
                }
            }

            is NotifyMessage -> {
                val notifyMessage = Gson().fromJson(record.notifyContent, TTNotifyMessage::class.java)
                notifyMessage?.showContent ?: ""
            }

            else -> ""
        }
    }
}


fun generateMessageFromForward(record: Forward, mode: Int = 0): ChatMessage {
    return TextChatMessage().apply {
        val attachmentID = if (!record.attachments.isNullOrEmpty()) {
            record.attachments?.firstOrNull()?.authorityId.toString()
        } else if (!record.forwards.isNullOrEmpty()) {
            val forward = record.forwards?.firstOrNull()
            if (forward?.attachments?.isNotEmpty() == true) {
                forward.attachments?.firstOrNull()?.authorityId.toString()
            } else ""
        } else ""
        this.id = if (!TextUtils.isEmpty(attachmentID)) attachmentID else System.currentTimeMillis().toString()
        this.authorId = record.author
        this.isMine = false
        this.sendStatus = SendType.Sent.rawValue
        this.timeStamp = record.id
        this.systemShowTimestamp = record.serverTimestampForUI
        this.notifySequenceId = 0
        this.readMaxSId = 0
        this.message = record.text
        this.attachment = record.attachments?.firstOrNull()
        this.mode = mode
        if (!record.forwards.isNullOrEmpty()) {
            var isFromGroup = false
            record.forwards?.forEach { forward ->
                isFromGroup = forward.isFromGroup
            }
            this.forwardContext = ForwardContext(record.forwards, isFromGroup)
        }
        this.card = record.card
        this.mentions = record.mentions
    }
}