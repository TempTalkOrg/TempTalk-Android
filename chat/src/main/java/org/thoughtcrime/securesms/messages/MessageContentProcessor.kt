package org.thoughtcrime.securesms.messages

import android.content.Context
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.delete
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.LChatToCallController
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.ContactsUpdater
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUpdater
import com.difft.android.chat.setting.ChatSettingUtils
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.widget.AudioMessageManager
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Card
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.QuotedAttachment
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.RealSource
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SharedContactAvatar
import difft.android.messageserialization.model.SharedContactEmail
import difft.android.messageserialization.model.SharedContactName
import difft.android.messageserialization.model.SharedContactPhone
import difft.android.messageserialization.model.SharedContactPostalAddress
import difft.android.messageserialization.model.TextMessage
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.rx3.await
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBMessageModel
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import com.difft.android.websocket.api.messages.NotifyConversation
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.util.mapToMessageId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Takes data about a decrypted message, transforms it into user-presentable data, and writes that
 * data to our data stores.
 */
@Singleton
class MessageContentProcessor @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val dbRoomStore: DBRoomStore,
    private val messageStore: MessageStore,
    private val asyncMessageJobsManager: AsyncMessageJobsManager,
    private val contactsUpdater: ContactsUpdater,
    private val groupUpdater: GroupUpdater,
    private val messageArchiveManager: MessageArchiveManager,
    private val userManager: UserManager,
    private val wcdb: WCDB,
    private val lCallManager: LChatToCallController,
    private val receiptMessageHelper: ReceiptMessageHelper,
    private val messageNotificationUtil: MessageNotificationUtil
) {

    private var tag: String = ""

    /**
     * Given the details about a message decryption, this will insert the proper message content into
     * the database.
     *
     *
     * This is super-stateful, and it's recommended that this be run in a transaction so that no
     * intermediate results are persisted to the database if the app were to crash.
     */
    suspend fun process(content: SignalServiceDataClass, tag: String): Message? {
        L.i { "[Message][${tag}] process message -> timestamp:${content.signalServiceEnvelope.timestamp}  device:${content.signalServiceEnvelope.sourceDevice}" }
        this.tag = tag
        return handleMessage(content)
    }

    private suspend fun handleMessage(content: SignalServiceDataClass): Message? {
        if (content.signalCustomNotifyMessage != null) {
            handleNotifyMessage(content)
        } else if (content.signalServiceContent != null) {
            val serviceContent: SignalServiceProtos.Content = content.signalServiceContent ?: return null
            if (content.signalServiceEnvelope.msgType?.number == SignalServiceProtos.Envelope.MsgType.MSG_SCHEDULE_NORMAL_VALUE) {//SCHEDULE message
                return handleDataMessage(
                    content,
                    isSyncMessage = false,
                    isScheduleMessage = true
                )
            } else if (serviceContent.hasNotifyMessage()) {
                return handleClientNotifyMessage(content)
            } else if (serviceContent.hasDataMessage()) {
                return handleDataMessage(
                    content,
                    isSyncMessage = false,
                    isScheduleMessage = false
                )
            } else if (serviceContent.hasSyncMessage()) {
                if(content.senderId != globalServices.myId) {
                    L.w { "[Message][${tag}] received sync message from another id, senderId:${content.senderId}." }
                    return null
                }
                if (serviceContent.syncMessage.hasSent()) {
                    return handleDataMessage(
                        content,
                        isSyncMessage = true,
                        isScheduleMessage = false
                    )
                } else if (serviceContent.syncMessage.readCount > 0) {
                    L.i { "[Message][${tag}] process sync read message -> timestamp:${content.signalServiceEnvelope.timestamp}  device:${content.signalServiceEnvelope.sourceDevice}" }
                    val readMessage = serviceContent.syncMessage.readList[0]
                    if (readMessage.messageMode == SignalServiceProtos.Mode.CONFIDENTIAL) {
                        val originalMessage = wcdb.message.getFirstObject(DBMessageModel.timeStamp.eq(readMessage.timestamp)) ?: run {
//                            val originalMessageId = "${readMessage.timestamp}${readMessage.sender.replace("+", "")}$DEFAULT_DEVICE_ID"
                            L.i { "[Message] delete sync read confidential message, can't find the original message, message timestamp:${readMessage.timestamp}" }
                            messageStore.savePendingMessage(content.messageId, readMessage.timestamp, content.signalServiceEnvelope.toByteArray())
                            return null
                        }
                        L.i { "[Message][${tag}] delete sync read confidential message -> timestamp:${readMessage.timestamp}" }
                        originalMessage.delete()
                    } else {
                        var forWhat: For? = null
                        if (readMessage.readPosition.hasGroupId() && readMessage.readPosition.groupId.isEmpty.not()) {
                            forWhat = For.Group(String(readMessage.readPosition.groupId.toByteArray()))
                        } else if (!readMessage.sender.isNullOrEmpty()) {
                            forWhat = For.Account(readMessage.sender)
                        }
                        if (forWhat != null) {
                            setReadMark(
                                forWhat,
                                readMessage.readPosition.maxServerTime,
                                readMessage.readPosition.maxSequenceId
                            )
                            messageStore.updateMessageReadTime(forWhat.id, readMessage.timestamp).await()
                        }
                    }
                } else return null
            } else if (serviceContent.hasReceiptMessage()) {
                L.i { "[Message][${tag}] process receipt message -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                receiptMessageHelper.handleReceiptMessage(serviceContent.receiptMessage, content)
            } else if (serviceContent.hasCallMessage()) {
                L.i { "[Message][${tag}] process call message -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                LCallManager.removePendingMessage(content.signalServiceEnvelope.source, content.signalServiceEnvelope.timestamp.toString())
                lCallManager.handleCallMessage(content)
            }
        }
        return null
    }

    private suspend fun handleDataMessage(
        content: SignalServiceDataClass,
        isSyncMessage: Boolean,
        isScheduleMessage: Boolean
    ): Message? {
        val (envelop, _, _) = content
        val message = if (isSyncMessage) content.signalServiceContent?.syncMessage?.sent?.message else content.signalServiceContent?.dataMessage
        if (message == null) return null
        val fromWho: For = For.Account(content.senderId)
        if (isScheduleMessage) {
            L.i { "[Message][${tag}] process schedule message -> timestamp:${envelop.timestamp}  device:${envelop.sourceDevice}  senderId:${content.senderId}  conversationId:${content.conversation.id}" }
        } else if (isSyncMessage) {
            L.i { "[Message][${tag}] process sync message -> timestamp:${envelop.timestamp} conversationId:${content.conversation.id}" }
        } else {
            L.i { "[Message][${tag}] process data message -> timestamp:${envelop.timestamp}  device:${envelop.sourceDevice}  senderId:${content.senderId}  conversationId:${content.conversation.id}" }
        }

        if (content.conversation is For.Group) {
            asyncMessageJobsManager.makeSureGroupExist(content.conversation.id)
        }

        val body = if (!message.body.isNullOrEmpty()) message.body else ""

        return handleTextMessage(
            content,
            message,
            fromWho,
            body,
            isSyncMessage
        )
    }

    private suspend fun handleTextMessage(
        content: SignalServiceDataClass,
        message: SignalServiceProtos.DataMessage,
        fromWho: For,
        messageBody: String,
        isSyncMessage: Boolean,
    ): Message? {
        L.i { "[Message][${tag}] handle text message -> timestamp:${content.signalServiceEnvelope.timestamp}  device:${content.signalServiceEnvelope.sourceDevice}  senderId:${fromWho.id}  conversationId:${content.conversation.id}" }
        val attachmentList: MutableList<Attachment> = ArrayList()
        if (message.attachmentsCount > 0) {
            L.d { "[Message][${tag}] Found attachments in handle text message" }
            val attachmentPointer = message.attachmentsList[0]
            var fileName = if (attachmentPointer.fileName.isNullOrEmpty().not()) attachmentPointer.fileName else attachmentPointer.id.toString()
            fileName = if (FileUtil.isFileNameValid(fileName)) {
                fileName
            } else {
                L.e { "Illegal file name: $fileName" }
                attachmentPointer.id.toString()
            }
            val attachmentPath = FileUtil.getMessageAttachmentFilePath(content.messageId) + fileName
            val attachment = createAttachmentFormPointer(content.messageId, attachmentPointer, fileName, attachmentPath)
            attachmentList.add(attachment)
        }

        val mentions: ArrayList<Mention> = ArrayList()
        if (message.mentionsCount > 0) {
            L.d { "[Message][${tag}] Found mentions in handle text message" }
            message.mentionsList.forEach {
                mentions.add(Mention(it.start, it.length, it.uid, it.type.number))
            }
        }
        val sharedContacts: ArrayList<SharedContact> = ArrayList()
        if (message.contactCount > 0) {
            L.d { "[Message][${tag}] Found shared contacts in handle text message" }
            message.contactList.forEach {
                val name = it.name?.let { name1 ->
                    SharedContactName(
                        name1.givenName,
                        name1.familyName,
                        name1.prefix,
                        name1.suffix,
                        name1.middleName,
                        name1.displayName
                    )
                }
                val avatar = if (it.hasAvatar()) {
                    val attachment: Attachment = it.avatar.avatar.let { attachment1 ->
                        val fileName = if (attachment1.fileName.isNullOrEmpty().not()) attachment1.fileName else attachment1.id.toString()
                        val filePath = "${FileUtil.getFilePath(FileUtil.FILE_DIR_AVATAR)}$fileName"
                        createAttachmentFormPointer(attachment1.id.toString() + "", attachment1, fileName, filePath)
                    }
                    SharedContactAvatar(attachment, it.avatar.isProfile)
                } else null
                val phone = if (it.numberCount > 0) {
                    mutableListOf<SharedContactPhone>().apply {
                        it.numberList.forEach { phone1 ->
                            this.add(SharedContactPhone(phone1.value, phone1.type.number, phone1.label))
                        }
                    }

                } else null
                val email = if (it.emailCount > 0) {
                    mutableListOf<SharedContactEmail>().apply {
                        it.emailList.forEach { email1 ->
                            this.add(SharedContactEmail(email1.value, email1.type.number, email1.label))
                        }
                    }

                } else null
                val address = if (it.addressCount > 0) {
                    mutableListOf<SharedContactPostalAddress>().apply {
                        it.addressList.forEach { address1 ->
                            this.add(
                                SharedContactPostalAddress(
                                    address1.type.number,
                                    address1.label,
                                    address1.street,
                                    address1.pobox,
                                    address1.neighborhood,
                                    address1.city,
                                    address1.region,
                                    address1.postcode,
                                    address1.country
                                )
                            )
                        }
                    }
                } else null

                sharedContacts.add(SharedContact(name, phone, avatar, email, address, it.organization))
            }
        }
        var quote: Quote? = null
        if (message.hasQuote()) {
            L.d { "[Message][${tag}] Found quote in handle text message" }
            val quoteMessage = message.quote
            val quotedAttachments: List<QuotedAttachment> = ArrayList()
            var text = quoteMessage.text
            if (TextUtils.isEmpty(quoteMessage.text)) {
                text = context.getString(R.string.chat_message_attachment)
            }
            quote = Quote(quoteMessage.id, quoteMessage.author, text, quotedAttachments)
        }
        var forwardContext: ForwardContext? = null
        if (message.hasForwardContext()) {
            L.d { "[Message][${tag}] Found forward context in handle text message" }
            val forwardContactIds = hashSetOf<String>()
            val list: MutableList<Forward> = ArrayList()
            val forwards = message.forwardContext.forwardsList
            for (forward in forwards) {
                val forward1 = createForward(
                    content.messageId,
                    forward,
                    forwardContactIds,
                    fromWho.id,
                    content.conversation.id,
                    forward.serverTimestamp
                )
                list.add(forward1)
            }
            forwardContext = ForwardContext(list, message.forwardContext.isFromGroup)

            asyncMessageJobsManager.needFetchSpecifiedContactors(forwardContactIds.toList())
        }
        var card: Card? = null
        if (message.hasCard()) {
            L.d { "[Message][${tag}] Found card in handle text message" }
            val extraLatestCard = content.extraLatestCard
            if (extraLatestCard != null) {
                L.i { "[Message][${tag}] Found extraLatestCard -> ${extraLatestCard.version} in handle card message" }
            }
            val card1 = message.card
            card = Card(
                card1.cardId,
                card1.appId,
                extraLatestCard?.version ?: card1.version,
                card1.creator,
                card1.timestamp,
                extraLatestCard?.content ?: card1.content,
                card1.contentType,
                card1.type,
                card1.fixedWidth,
                fromWho.id,
                content.conversation.id
            )
        }

        if (message.hasRecall() && message.recall.hasSource()) {
            if (message.recall.source.source != content.senderId) {
                L.i { "[Message][${tag}] recall message failed, sender does not match, realSource:${message.recall.source.source}, senderId:${content.senderId}" }
                return null
            }
            val originalMessageId = message.recall.source.mapToMessageId().idValue
            L.i { "[Message][${tag}] process recall message -> timestamp:${content.signalServiceEnvelope.timestamp}  device:${content.signalServiceEnvelope.sourceDevice}  realMessageId:$originalMessageId" }
            val originalMessage = wcdb.message.getFirstObject(DBMessageModel.id.eq(originalMessageId))
            if (originalMessage != null) {
                L.i { "[Message][${tag}] delete recall message -> timestamp:${content.signalServiceEnvelope.timestamp}  device:${content.signalServiceEnvelope.sourceDevice}  realMessageId:$originalMessageId" }
                originalMessage.delete()
                messageNotificationUtil.showNotificationSuspend(context, originalMessage.convertToTextMessage(), content.conversation, isRecall = true)
            } else {
                L.i { "[Message][${tag}] Can't process recall message because can't find the message to recall, messageId:$originalMessageId" }
                messageStore.savePendingMessage(content.messageId, message.recall.source.timestamp, content.signalServiceEnvelope.toByteArray())
            }
            return null
        } else if (message.hasReaction()) {
            val reaction1 = message.reaction
            val reaction = Reaction(
                reaction1.emoji,
                fromWho.id,
                reaction1.remove,
                message.timestamp,
                RealSource(
                    reaction1.source.source,
                    reaction1.source.sourceDevice,
                    reaction1.source.timestamp,
                    reaction1.source.serverTimestamp
                )
            )
            messageStore.updateMessageReaction(content.conversation.id, reaction, content.messageId, content.signalServiceEnvelope.toByteArray())
        } else if (message.hasScreenShot()) {
            return ContactorUtil.createScreenShotMessageNew(
                content.conversation,
                content.messageId,
                message.screenShot.source.source,
                content.signalServiceEnvelope.notifySequenceId,
                content.sequenceId,
            )
        } else {
            val contentText = if ((TextUtils.isEmpty(messageBody)
                        && forwardContext == null
                        && card == null
                        && quote == null
                        && attachmentList.isEmpty()
                        && sharedContacts.isEmpty())
                || messageBody == "[Unsupported message type]"
            ) {
                context.getString(R.string.unsupported_message_type)
            } else {
                messageBody
            }

            val systemShowTimestamp = if (isSyncMessage && content.conversation.id != content.myId) {
                content.signalServiceContent?.syncMessage?.sent?.serverTimestamp ?: content.signalServiceEnvelope.systemShowTimestamp
            } else {
                content.signalServiceEnvelope.systemShowTimestamp
            }

            var receiverIds: String? = null
            if (isSyncMessage && content.conversation is For.Group) {
                val receiverIdList = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.gid.eq(content.conversation.id)).map { it.id } - globalServices.myId
                receiverIds = globalServices.gson.toJson(receiverIdList)
            }

            if (content.senderId != globalServices.myId) {
                receiptMessageHelper.updateReadInfo(content.conversation.id, content.senderId, systemShowTimestamp)
            }

            val textMessage = TextMessage(
                content.messageId,
                fromWho,
                content.conversation,
                systemShowTimestamp,
                content.signalServiceEnvelope.timestamp,
                System.currentTimeMillis(),
                SendType.Sent.rawValue,
                message.expireTimer,
                content.signalServiceEnvelope.notifySequenceId,
                content.sequenceId,
                message.messageMode.number,
                contentText,
                attachmentList,
                quote,
                forwardContext,
                null,
                card,
                mentions,
                message.atPersons,
                null,
                null,
                sharedContacts,
                playStatus = AudioMessageManager.PLAY_STATUS_NOT_PLAY,
                receiverIds = receiverIds
            )
            return textMessage
        }

        return null
    }

    private suspend fun handleClientNotifyMessage(signalServiceDataClass: SignalServiceDataClass): Message? {
        val notifyMessage = signalServiceDataClass.signalServiceContent?.notifyMessage
        L.i { "[Message][${tag}] handleClientNotifyMessage -> timestamp:${signalServiceDataClass.messageId}" }
        if (notifyMessage != null) {
        }
        return null
    }

    private suspend fun handleNotifyMessage(
        content: SignalServiceDataClass
    ) {
        val (envelop, _, notifyMessageContent) = content
        if (notifyMessageContent != null) {
            val message = notifyMessageContent
            L.i { "[Message][${tag}] process notify message -> timestamp:${envelop.timestamp}  device:${envelop.sourceDevice}  data:${message.notifyType}" }
            if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CONVERSATION_SHARE_SETTING) {
                message.data?.let {
                    updateDisappearingTime(content.conversation, it.messageExpiry, it.messageClearAnchor)
                }
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING) {
                message.data?.conversation?.let {
                    kotlin.runCatching {
                        val notifyConversation = Gson().fromJson(it.toString(), NotifyConversation::class.java)
                        dbRoomStore.updateMuteStatus(notifyConversation.conversation, notifyConversation.muteStatus).await()
                        ContactorUtil.updateRemark(notifyConversation.conversation, notifyConversation.remark)
                        ChatSettingUtils.emitConversationSettingUpdate(notifyConversation.conversation)
                    }.onFailure {
                        L.e { "[Message][${tag}] handle conversation setting notify message fail: ${it.stackTraceToString()}" }
                    }
                }
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_GROUP) {
                groupUpdater.handleGroupNotifyMessage(message, content)
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_UPDATE_CONTACT) {
                contactsUpdater.updateBySignalNotifyMessage(message)
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_ADD_FRIEND) {
                message.data?.let { data ->
                    when (data.actionType) {
                        TTNotifyMessage.NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST ->
                            data.operatorInfo?.operatorId?.let { id ->
                                dbRoomStore.createRoomIfNotExist(For.Account(id))
                                ContactorUtil.updateContactRequestStatus(id)
                                ContactorUtil.getContactWithID(context, id).await()
                                ContactorUtil.emitContactsUpdate(listOf(id))
                            }

                        TTNotifyMessage.NOTIFY_ACTION_TYPE_ADD_FRIEND_ACCEPT -> {
                            ContactorUtil.fetchAndSaveContactors()
                        }

                        else -> {}
                    }
                }
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CALL_END) {
                message.data?.let {
                    val roomId = it.roomId
                    if (!roomId.isNullOrEmpty()) {
                        lCallManager.handleCallEndNotification(roomId)
                    }
                }
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_RESET_IDENTITY_KEY) {
                L.i { "[Message][${tag}] process reset identity key notify message -> data:${message.data}" }
                val data = message.data ?: return
                val operator = data.operator ?: return
                messageArchiveManager.archiveMessagesByResetIdentityKey(operator, data.resetIdentityKeyTime)
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CRITICAL_ALERT) {
                L.i { "[Message][${tag}] process critical alert notify message -> timestamp:${message.data?.timestamp}" }
                val data = message.data ?: return
                if(messageNotificationUtil.isNotificationPolicyAccessGranted()) {
                    data.source?.let { uid ->
                        val alertTitle = data.alertTitle ?: ResUtils.getString(R.string.notification_critical_alert_title_default)
                        val alertContent = data.alertBody ?: ResUtils.getString(R.string.notification_critical_alert_content_default)
                        val timestamp = data.timestamp
                        L.i { "[Call] handle notify message critical alert: uid = $uid, timestamp = $timestamp" }
                        messageNotificationUtil.showCriticalAlertNotification(For.Account(uid), alertTitle, alertContent, timestamp)
                    }
                } else {
                    L.i { "[Call] From notify critical alert message is not shown because notification policy access is denied, uid = ${data.source}, timestamp = ${data.timestamp}" }
                }
            }
        }
    }

    private suspend fun setReadMark(room: For, readPosition: Long, readMaxSid: Long) {
        dbRoomStore.updateMessageReadPosition(room, readPosition)
    }

    private fun createForward(
        messageId: String,
        forward: SignalServiceProtos.DataMessage.Forward,
        forwardContactIds: HashSet<String>,
        source: String,
        conversationId: String,
        serverTimestamp: Long
    ): Forward {
        forwardContactIds.add(forward.author)
        val attachments: MutableList<Attachment> = ArrayList()
        val forwards: MutableList<Forward> = ArrayList()
        var card: Card? = null
        val mentions: MutableList<Mention> = ArrayList()
        if (forward.attachmentsCount > 0) {
            for (attachmentPointer in forward.attachmentsList) {
                var fileName = if (attachmentPointer.fileName.isNullOrEmpty().not()) attachmentPointer.fileName else attachmentPointer.id.toString()
                fileName = if (FileUtil.isFileNameValid(fileName)) {
                    fileName
                } else {
                    L.e { "Illegal file name: $fileName" }
                    attachmentPointer.id.toString()
                }
                val attachmentPath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
                val attachment = createAttachmentFormPointer(attachmentPointer.id.toString() + "", attachmentPointer, fileName, attachmentPath)
                attachments.add(attachment)
            }
        }
        if (forward.forwardsCount > 0) {
            for (forward1 in forward.forwardsList) {
                forwards.add(createForward(messageId, forward1, forwardContactIds, source, conversationId, forward1.serverTimestamp))
            }
        }
        if (forward.hasCard()) {
            val card1 = forward.card
            card = Card(card1.cardId, card1.appId, card1.version, card1.creator, card1.timestamp, card1.content, card1.contentType, card1.type, card1.fixedWidth, source, conversationId)
        }
        if (forward.mentionsCount > 0) {
            for (mention in forward.mentionsList) {
                mentions.add(Mention(mention.start, mention.length, mention.uid, mention.type.number))
            }
        }

        return Forward(forward.id, forward.type, forward.isFromGroup, forward.author, forward.text, attachments, forwards, card, mentions, serverTimestamp)
    }

    private fun createAttachmentFormPointer(
        id: String,
        attachmentPointer: SignalServiceProtos.AttachmentPointer,
        fileName: String?,
        attachmentPath: String
    ) = Attachment(
        id,
        attachmentPointer.id,
        attachmentPointer.contentType,
        attachmentPointer.key.toByteArray(),
        attachmentPointer.size,
        null,
        attachmentPointer.digest.toByteArray(),
        fileName,
        attachmentPointer.flags,
        attachmentPointer.width,
        attachmentPointer.height,
        attachmentPath,
        AttachmentStatus.LOADING.code
    )

    private fun updateDisappearingTime(forWhat: For, messageExpiry: Int, messageClearAnchor: Long) {
        messageArchiveManager.updateLocalArchiveTime(forWhat, messageExpiry.toLong(), messageClearAnchor)
    }
}