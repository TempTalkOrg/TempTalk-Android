package com.difft.android.chat.message

import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import org.difft.app.database.attachment
import org.difft.app.database.card
import org.difft.app.database.forwardContext
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.screenShot
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
import com.difft.android.base.R
import com.difft.android.chat.common.SendType
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.NotifyMessage
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

data class ReadStatusResult(val readStatus: Int, val readContactNumber: Int)

fun calculateReadStatus(
    forWhat: For,
    record: MessageModel,
    readInfoList: List<ReadInfoModel>?,
    isLargeGroup: Boolean,
    systemShowTimestamp: Long
): ReadStatusResult {
    if (record.fromWho != globalServices.myId) return ReadStatusResult(0, 0)

    if (forWhat is For.Account) {
        return if (forWhat.id == globalServices.myId) {
            ReadStatusResult(1, 0)
        } else {
            val readInfo = readInfoList?.firstOrNull()
            ReadStatusResult(if ((readInfo?.readPosition ?: 0) >= systemShowTimestamp) 1 else 0, 0)
        }
    }

    // Group
    if (isLargeGroup) {
        val receiverIds = parseReceiverIds(record.receiverIds)
        return ReadStatusResult(1, receiverIds?.size ?: 0)
    }

    val readInfos = readInfoList?.filter { it.readPosition >= systemShowTimestamp }
    val receiverIds = parseReceiverIds(record.receiverIds)

    return when {
        readInfos == null -> ReadStatusResult(0, 0)
        receiverIds == null -> {
            L.w { "Group message receiverIds is null, messageId: ${record.id}, systemShowTimestamp: $systemShowTimestamp" }
            val readContactIds = readInfos.map { it.uid }.filter { it != globalServices.myId }.toSet()
            ReadStatusResult(0, readContactIds.size)
        }
        else -> {
            val readContactIds = readInfos.map { it.uid }.toSet()
            ReadStatusResult(
                if (readContactIds.containsAll(receiverIds)) 1 else 0,
                receiverIds.count { it in readContactIds }
            )
        }
    }
}

fun generateMessageTwo(
    forWhat: For,
    record: MessageModel,
    contactor: List<ContactorModel>,
    readInfoList: List<ReadInfoModel>?,
    isLargeGroup: Boolean = false
): ChatMessage? {
    val isFromMySelf = globalServices.myId == record.fromWho
    val authorId = record.fromWho
    val author =
        contactor.firstOrNull { it.id == record.fromWho } ?: ContactorModel().also {
            it.id = record.fromWho
        }
    return if (record.type == MessageModel.TYPE_TEXT || record.type == MessageModel.TYPE_ATTACHMENT || record.type == MessageModel.TYPE_UNSUPPORTED) {
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
            // For unsupported messages, show a placeholder text
            this.message = if (record.type == MessageModel.TYPE_UNSUPPORTED) {
                ResUtils.getString(R.string.chat_message_unsupported)
            } else if (record.forwardContext() != null) {
                ""
            } else {
                record.messageText
            }
            this.attachment = record.attachment()
            this.quote = record.quote()
            this.forwardContext = record.forwardContext()
            this.card = record.card()
            this.mentions = record.mentions()
            this.reactions = record.reactions()
            this.sharedContacts = record.sharedContacts()
            val readResult = calculateReadStatus(forWhat, record, readInfoList, isLargeGroup, systemShowTimestamp)
            this.readStatus = readResult.readStatus
            this.readContactNumber = readResult.readContactNumber
            this.playStatus = record.playStatus
            this.translateData = record.translateData()
            this.speechToTextData = record.speechToTextData()
            this.criticalAlertType = record.criticalAlertType
            this.isScreenShotMessage = record.screenShot() != null
        }
    } else if (record.type == MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER) {
        ConfidentialPlaceholderChatMessage().apply {
            this.id = record.id
            this.authorId = authorId
            this.isMine = isFromMySelf
            this.sendStatus = record.sendType
            this.timeStamp = record.timeStamp
            this.systemShowTimestamp = record.systemShowTimestamp
            this.notifySequenceId = record.notifySequenceId
            this.readMaxSId = record.sequenceId
            this.mode = record.mode
        }
    } else if (record.type == MessageModel.TYPE_NOTIFY) {
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

    // Check for unsupported message first
    if (record is TextMessage && record.isUnsupported) {
        return senderName + ResUtils.getString(R.string.chat_message_unsupported)
    }

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