package com.difft.android.chat.messages

import android.content.Context
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.call.LChatToCallController
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.ContactsUpdater
import com.difft.android.chat.contacts.WeakContactReconciler
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.focus.FocusSnapshot
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.group.GroupUpdater
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.sync.ConversationSettingsApplier
import com.difft.android.chat.setting.sync.ServerConfigSnapshot
import com.difft.android.chat.setting.sync.SnapshotSource
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.websocket.api.messages.ConversationVerdict
import com.difft.android.websocket.api.messages.NotifyConversation
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.messages.crossCheckConversation
import com.difft.android.websocket.api.util.mapToMessageId
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import com.difft.android.websocket.api.util.toKotlinDataOrNull
import com.difft.android.websocket.api.util.toKotlinEnum
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.QuotedAttachment
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.RealSource
import difft.android.messageserialization.model.ScreenShot
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SharedContactAvatar
import difft.android.messageserialization.model.SharedContactEmail
import difft.android.messageserialization.model.SharedContactName
import difft.android.messageserialization.model.SharedContactPhone
import difft.android.messageserialization.model.SharedContactPostalAddress
import difft.android.messageserialization.model.TextMessage
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.delete
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.wcdb
import com.difft.android.chat.util.MediaUtil
import com.difft.android.chat.util.MessageNotificationUtil
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton


const val ENVELOPE_TAG_FCM = "fcm"






@Suppress("BlockingWcdbInSuspend")
@Singleton
class MessageContentProcessor @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val dbRoomStore: DBRoomStore,
    private val messageStore: MessageStore,
    private val asyncMessageJobsManager: AsyncMessageJobsManager,
    private val contactsUpdater: ContactsUpdater,
    private val groupUpdater: GroupUpdater,
    private val messageArchiveManager: MessageArchiveManager,
    private val lCallManagerProvider: Provider<LChatToCallController>,
    private val receiptMessageHelper: ReceiptMessageHelper,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val conversationSettingsManager: ConversationSettingsManager,
    private val settingsApplier: ConversationSettingsApplier,
    private val localMessageCreator: LocalMessageCreator,
    private val groupCryptoRepo: com.difft.android.chat.crypto.GroupCryptoRepo,
    private val groupUtil: com.difft.android.chat.group.GroupUtil,
    private val weakContactReconciler: WeakContactReconciler,
    private val gson: Gson,
    private val conversationUnreadMarker: com.difft.android.chat.message.ConversationUnreadMarker,
    private val criticalAlertHighlightWriter: CriticalAlertHighlightWriter,
    private val groupFocusRepository: GroupFocusRepository,
) {

    










    suspend fun process(content: SignalServiceDataClass, tag: String, admission: MessagingTicket): Message? {
        L.i { "[Message][${tag}] process message -> timestamp:${content.signalServiceEnvelope.timestamp}  device:${content.signalServiceEnvelope.sourceDevice}  stamp:${content.envelopeConversation?.id ?: "absent"}" }
        return handleMessage(content, tag, admission)
    }

    private suspend fun handleMessage(content: SignalServiceDataClass, tag: String, admission: MessagingTicket): Message? {
        if (content.signalCustomNotifyMessage != null) {
            handleNotifyMessage(content, tag, admission)
        } else if (content.signalServiceContent != null) {
            val serviceContent: SignalServiceProtos.Content = content.signalServiceContent ?: return null
            if (serviceContent.hasGroupKeyMessage()) {
                return handleGroupKeyMessage(content)
            } else if (serviceContent.hasNotifyMessage()) {
                return handleClientNotifyMessage(content, tag)
            } else if (serviceContent.hasDataMessage()) {
                if (isCrossConversationInjection(content, tag)) return null 
                return handleDataMessage(content, isSyncMessage = false, tag = tag)
            } else if (serviceContent.hasSyncMessage()) {
                if (content.senderId != globalServices.myId) {
                    L.w { "[Message][${tag}] received sync message from another id, senderId:${content.senderId}." }
                    return null
                }
                if (serviceContent.syncMessage.hasSent()) {
                    return handleDataMessage(
                        content,
                        isSyncMessage = true,
                        tag = tag,
                    )
                } else if (serviceContent.syncMessage.readCount > 0) {
                    L.i { "[Message][${tag}] process sync read message -> timestamp:${content.signalServiceEnvelope.timestamp}  device:${content.signalServiceEnvelope.sourceDevice}" }
                    val firstReadMessage = serviceContent.syncMessage.readList[0]
                    if (firstReadMessage.messageMode == SignalServiceProtos.Mode.CONFIDENTIAL) {
                        
                        serviceContent.syncMessage.readList.forEach { readMessage ->
                            val originalMessage = wcdb.message.getFirstObject(DBMessageModel.timeStamp.eq(readMessage.timestamp)) ?: run {
                                L.i { "[Message] sync confidential delete, can't find message, timestamp:${readMessage.timestamp}" }
                                messageStore.savePendingMessage(content.messageId, readMessage.timestamp, content.signalServiceEnvelope.toByteArray())
                                return@forEach
                            }
                            L.i { "[Message][${tag}] delete sync read confidential message -> timestamp:${readMessage.timestamp}" }
                            originalMessage.delete()
                        }
                    } else {
                        var forWhat: For? = null
                        if (firstReadMessage.readPosition.hasGroupId() && firstReadMessage.readPosition.groupId.isEmpty.not()) {
                            forWhat = For.Group(String(firstReadMessage.readPosition.groupId.toByteArray()))
                        } else if (!firstReadMessage.sender.isNullOrEmpty()) {
                            forWhat = For.Account(firstReadMessage.sender)
                        }
                        if (forWhat != null) {
                            setReadMark(
                                forWhat,
                                firstReadMessage.readPosition.maxServerTime,
                                firstReadMessage.readPosition.maxSequenceId
                            )
                            
                            
                            val resolvedReadAt = SyncReadTimeResolver.resolveSyncReadAt(
                                payloadReadAt = firstReadMessage.readPosition.readAt,
                                envelopeServerTimestamp = content.signalServiceEnvelope.systemShowTimestamp,
                                fallback = firstReadMessage.timestamp
                            )
                            messageStore.updateMessageReadTime(
                                forWhat.id,
                                firstReadMessage.readPosition.maxServerTime,
                                resolvedReadAt
                            )
                        }
                    }
                } else if (serviceContent.syncMessage.hasActivityNoticeSync()) {
                    
                    L.i { "[Message][${tag}] process activity notice sync -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                    return handleActivityNoticeSync(content, serviceContent.syncMessage.activityNoticeSync, tag)
                } else if (serviceContent.syncMessage.hasForwardNoticeSync()) {
                    L.i { "[Message][${tag}] process forward notice sync -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                    return handleForwardNoticeSync(content, serviceContent.syncMessage.forwardNoticeSync, tag)
                }
                return if (serviceContent.syncMessage.hasMarkAsUnread()) handleMarkAsUnreadSync(content, serviceContent.syncMessage.markAsUnread, tag) else null
            } else if (serviceContent.hasReceiptMessage()) {
                L.d { "[Message][${tag}] process receipt message -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                receiptMessageHelper.handleReceiptMessage(serviceContent.receiptMessage, content, admission)
            } else if (serviceContent.hasCallMessage()) {
                L.i { "[Message][${tag}] process call message -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                LCallManager.removePendingMessage(content.signalServiceEnvelope.source, content.signalServiceEnvelope.timestamp.toString())
                lCallManagerProvider.get().handleCallMessage(content, admission, fromServerPush = tag == ENVELOPE_TAG_FCM)
            } else if (serviceContent.hasActivityNotice()) {
                if (isCrossConversationInjection(content, tag)) return null 
                
                
                L.i { "[Message][${tag}] process activity notice -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                return handleActivityNoticeMessage(
                    content = content,
                    activityNotice = serviceContent.activityNotice,
                    operatorId = content.signalServiceEnvelope.source,
                    conversation = content.conversation,
                    tag = tag
                )
            } else if (serviceContent.hasForwardNotice()) {
                if (isCrossConversationInjection(content, tag)) return null 
                L.i { "[Message][${tag}] process forward notice -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                return handleForwardNoticeMessage(
                    content = content,
                    forwardNotice = serviceContent.forwardNotice,
                    operatorId = content.signalServiceEnvelope.source,
                    conversation = content.conversation,
                    tag = tag
                )
            }
        }
        return null
    }

    






    private fun isCrossConversationInjection(content: SignalServiceDataClass, tag: String): Boolean {
        val reject = crossCheckConversation(
            contentConversation = content.conversation,
            envelopeConversation = content.envelopeConversation,
            isSyncOrSelf = content.senderId == content.myId,
        ) == ConversationVerdict.REJECT
        if (reject) {
            L.w {
                "[Message][${tag}] conversation cross-check REJECT drop " +
                    "claimedConv=${content.conversation.id} sender=${content.senderId} " +
                    "ts=${content.signalServiceEnvelope.timestamp}"
            }
        }
        return reject
    }

    











    private suspend fun handleForwardNoticeMessage(
        content: SignalServiceDataClass,
        forwardNotice: SignalServiceProtos.ForwardNoticeMessage,
        operatorId: String,
        conversation: For,
        tag: String
    ): Message? {
        val envelop = content.signalServiceEnvelope
        
        
        
        val sceneKt = forwardNotice.scene.toKotlinEnum() ?: run {
            L.w {
                "[Message][${tag}] forwardNotice has unknown/unset scene=${forwardNotice.scene}, drop"
            }
            return null
        }
        val noticeData = ForwardNoticeData(
            scene = sceneKt,
            
            
            
            
            sourceAuthorIds = forwardNotice.sourceAuthorIdsList.toList(),
            
            
            
            messageCount = maxOf(1, forwardNotice.messageCount),
            combinedForwardMode = forwardNotice.combinedForwardMode.toKotlinEnum(),
        )
        L.i {
            "[Message][${tag}] handle forward notice -> operator=$operatorId, " +
                "conversation=${conversation.id}, scene=${noticeData.scene}, " +
                "count=${noticeData.messageCount}, authors=${noticeData.sourceAuthorIds.size}"
        }
        return localMessageCreator.createForwardNoticeMessage(
            operatorId = operatorId,
            forWhat = conversation,
            noticeData = noticeData,
            systemShowTimestamp = envelop.systemShowTimestamp.takeIf { it > 0 } ?: envelop.timestamp,
            timestamp = envelop.timestamp,
            sourceDevice = envelop.sourceDevice
        )
    }

    




    private suspend fun handleMarkAsUnreadSync(
        content: SignalServiceDataClass,
        markAsUnread: SignalServiceProtos.SyncMessage.MarkAsUnread,
        tag: String
    ): Message? {
        
        
        val conv = markAsUnread.takeIf { it.hasConversation() }?.conversation
        val hasValidConv = conv != null &&
            (conv.hasGroupId() || (conv.hasNumber() && conv.number.isNotEmpty()))
        if (!hasValidConv) {
            L.w { "[Message][${tag}] markAsUnread sync without conversation, dropping -> timestamp:${content.signalServiceEnvelope.timestamp}" }
            return null
        }
        L.i { "[Message][${tag}] process markAsUnread sync -> timestamp:${content.signalServiceEnvelope.timestamp} flag:${markAsUnread.flag.number} conversationId:${content.conversation.id}" }
        
        
        val envelope = content.signalServiceEnvelope
        val markedAt = envelope.systemShowTimestamp.takeIf { it > 0 } ?: envelope.timestamp
        conversationUnreadMarker.onIncomingSync(content.conversation, markAsUnread.flag.number, markedAt)
        return null
    }

    









    private suspend fun handleForwardNoticeSync(
        content: SignalServiceDataClass,
        forwardNotice: SignalServiceProtos.ForwardNoticeMessage,
        tag: String
    ): Message? {
        val conv = forwardNotice.takeIf { it.hasConversation() }?.conversation
        val hasValidConv = conv != null && (
            conv.hasGroupId() || (conv.hasNumber() && conv.number.isNotEmpty())
        )
        if (!hasValidConv) {
            L.w { "[Message][${tag}] forwardNoticeSync missing or empty conversation, drop" }
            return null
        }
        return handleForwardNoticeMessage(
            content = content,
            forwardNotice = forwardNotice,
            operatorId = globalServices.myId,
            conversation = content.conversation,
            tag = tag
        )
    }

    











    private suspend fun handleActivityNoticeMessage(
        content: SignalServiceDataClass,
        activityNotice: SignalServiceProtos.MessageActivityNotice,
        operatorId: String,
        conversation: For,
        tag: String
    ): Message? {
        val envelop = content.signalServiceEnvelope

        val noticeData = activityNotice.toKotlinDataOrNull()
            ?: run {
                L.w {
                    "[Message][${tag}] activityNotice unknown/unset typeData_case=${activityNotice.typeDataCase}, drop"
                }
                return null
            }

        L.i {
            "[Message][${tag}] handle activity notice -> operator=$operatorId, " +
                "conversation=${conversation.id}, type=${noticeData.type}, " +
                "count=${noticeData.messageCount}, authors=${noticeData.sourceAuthorIds.size}"
        }
        return localMessageCreator.createActivityNoticeMessage(
            operatorId = operatorId,
            forWhat = conversation,
            noticeData = noticeData,
            systemShowTimestamp = envelop.systemShowTimestamp.takeIf { it > 0 } ?: envelop.timestamp,
            timestamp = envelop.timestamp,
            sourceDevice = envelop.sourceDevice
        )
    }

    





    private suspend fun handleActivityNoticeSync(
        content: SignalServiceDataClass,
        activityNotice: SignalServiceProtos.MessageActivityNotice,
        tag: String
    ): Message? {
        val conv = activityNotice.takeIf { it.hasConversation() }?.conversation
        val hasValidConv = conv != null && (
            conv.hasGroupId() || (conv.hasNumber() && conv.number.isNotEmpty())
        )
        if (!hasValidConv) {
            L.w { "[Message][${tag}] activityNoticeSync missing or empty conversation, drop" }
            return null
        }
        return handleActivityNoticeMessage(
            content = content,
            activityNotice = activityNotice,
            operatorId = globalServices.myId,
            conversation = content.conversation,
            tag = tag
        )
    }

    private suspend fun handleDataMessage(
        content: SignalServiceDataClass,
        isSyncMessage: Boolean,
        tag: String,
    ): Message? {
        val (envelop, _, _) = content
        val message = if (isSyncMessage) content.signalServiceContent?.syncMessage?.sent?.message else content.signalServiceContent?.dataMessage
        if (message == null) return null
        val fromWho: For = For.Account(content.senderId)
        if (isSyncMessage) {
            L.i { "[Message][${tag}] process sync message -> timestamp:${envelop.timestamp} conversationId:${content.conversation.id}" }
        } else {
            L.i { "[Message][${tag}] process data message -> timestamp:${envelop.timestamp}  device:${envelop.sourceDevice}  senderId:${content.senderId}  conversationId:${content.conversation.id}" }
        }

        if (content.conversation is For.Group) {
            asyncMessageJobsManager.makeSureGroupExist(content.conversation.id)

            
            if (message.hasGroup() && message.group.hasGroupRootKey()) {
                val saved = groupCryptoRepo.saveOrRotateRGroup(
                    content.conversation.id,
                    message.group.groupRootKey.toByteArray(),
                    message.group.keyVersion 
                )
                if (saved) {
                    L.i { "[GE] Fallback key extracted from group message for ${content.conversation.id}" }
                    
                    
                    
                    groupUtil.fetchAndSaveSingleGroupInfo(content.conversation.id, true)
                }
            }
        }

        val body = if (!message.body.isNullOrEmpty()) message.body else ""

        return handleTextMessage(
            content,
            message,
            fromWho,
            body,
            isSyncMessage,
            tag,
        )
    }

    private suspend fun handleTextMessage(
        content: SignalServiceDataClass,
        message: SignalServiceProtos.DataMessage,
        fromWho: For,
        messageBody: String,
        isSyncMessage: Boolean,
        tag: String,
    ): Message? {
        L.i {
            "[Message][${tag}] handle text message -> " +
                    "timestamp:${content.signalServiceEnvelope.timestamp}, " +
                    "device:${content.signalServiceEnvelope.sourceDevice}, " +
                    "senderId:${fromWho.id}, " +
                    "conversationId:${content.conversation.id}, " +
                    "msgType=${content.signalServiceEnvelope.msgType}, " +
                    "isRecall=${message.hasRecall()}, " +
                    "isReaction=${message.hasReaction()}, " +
                    "hasQuote=${message.hasQuote()}, " +
                    "hasForward=${message.hasForwardContext()}, " +
                    "hasScreenShot=${message.hasScreenShot()}, " +
                    "hasBody=${messageBody.isNotEmpty()}, " +
                    "attachmentCount=${message.attachmentsCount}, " +
                    "contactCount=${message.contactCount}, " +
                    "mentionCount=${message.mentionsCount}, " +
                    "isSyncMessage=$isSyncMessage, " +
                    "requiredVersion=${message.requiredProtocolVersion}, " +
                    "currentVersion=${SignalServiceProtos.DataMessage.ProtocolVersion.CURRENT_VALUE}"
        }

        val systemShowTimestamp = content.signalServiceEnvelope.systemShowTimestamp

        
        val requiredVersion = message.requiredProtocolVersion
        val currentVersion = SignalServiceProtos.DataMessage.ProtocolVersion.CURRENT_VALUE
        if (requiredVersion > currentVersion) {
            L.w { "[Message][${tag}] Unsupported protocol version: required=$requiredVersion, current=$currentVersion" }
            return TextMessage(
                id = content.messageId,
                fromWho = fromWho,
                forWhat = content.conversation,
                systemShowTimestamp = systemShowTimestamp,
                timeStamp = content.signalServiceEnvelope.timestamp,
                receivedTimeStamp = System.currentTimeMillis(),
                sendType = SendType.Sent.rawValue,
                expiresInSeconds = message.expireTimer,
                notifySequenceId = content.signalServiceEnvelope.notifySequenceId,
                sequenceId = content.sequenceId,
                mode = message.messageMode.number,
                text = null,
                isUnsupported = true
            )
        }

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
            
            val localId = UUID.randomUUID().toString()
            val attachmentPath = AttachmentPathResolver.stagingFileFor(localId, fileName)
            val attachment = createAttachmentFormPointer(attachmentPointer, fileName, attachmentPath, localId = localId)
            attachmentList.add(attachment)
        }

        val mentions = ArrayList<Mention>()
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
                        var fileName = if (attachment1.fileName.isNullOrEmpty().not()) attachment1.fileName else attachment1.id.toString()
                        fileName = if (FileUtil.isFileNameValid(fileName)) {
                            fileName
                        } else {
                            L.e { "Illegal file name: $fileName" }
                            attachment1.id.toString()
                        }
                        val filePath = "${FileUtil.getFilePath(FileUtil.FILE_DIR_AVATAR)}$fileName"
                        createAttachmentFormPointer(attachment1, fileName, filePath)
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
            val quotedAttachments = quoteMessage.attachmentsList.map { protoQa ->
                val thumbnailAttachment = if (protoQa.hasThumbnail()) {
                    createQuoteThumbnailAttachment(protoQa.thumbnail)
                } else null
                QuotedAttachment(
                    contentType = protoQa.contentType,
                    fileName = protoQa.fileName,
                    thumbnail = thumbnailAttachment,
                    flags = protoQa.flags
                )
            }
            var text = quoteMessage.text
            if (TextUtils.isEmpty(quoteMessage.text)) {
                
                
                
                val qa = quotedAttachments.firstOrNull()
                text = context.getString(MediaUtil.quoteTypeLabelRes(qa?.contentType, qa?.flags ?: 0))
            }
            quote = Quote(quoteMessage.id, quoteMessage.author, text, quotedAttachments.ifEmpty { null })
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
            
            
            if (forwardContactIds.isNotEmpty()) {
                asyncMessageJobsManager.needFetchSpecifiedContactors(forwardContactIds.toList())
            }
        }
        
        val screenShot: ScreenShot? = if (message.hasScreenShot()) {
            L.d { "[Message][${tag}] Found screenshot in handle text message" }
            val source = message.screenShot.source
            ScreenShot(RealSource(source.source, source.sourceDevice, source.timestamp, source.serverTimestamp))
        } else null

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
            return null
        } else {
            
            val isEmptyMessage = TextUtils.isEmpty(messageBody)
                    && forwardContext == null
                    && quote == null
                    && attachmentList.isEmpty()
                    && sharedContacts.isEmpty()
                    && screenShot == null

            if (isEmptyMessage) {
                L.w { "[Message][${tag}] Empty message detected, skipping." }
                return null
            }

            
            val contentText = if (screenShot != null) {
                val screenshotSource = screenShot.realSource?.source
                val screenshotUserName = if (screenshotSource == globalServices.myId) {
                    context.getString(R.string.you)
                } else if (screenshotSource != null) {
                    wcdb.getContactorFromAllTable(screenshotSource)?.getDisplayNameForUI()
                        ?: screenshotSource.formatBase58Id()
                } else {
                    ""
                }
                context.getString(R.string.chat_took_a_screen_shot, screenshotUserName)
            } else {
                messageBody
            }

            var receiverIds: String? = null
            if (content.senderId == globalServices.myId && content.conversation is For.Group) {
                val receiverIdList = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.gid.eq(content.conversation.id)).map { it.id } - globalServices.myId
                receiverIds = gson.toJson(receiverIdList)
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
                mentions,
                message.atPersons,
                null,
                screenShot,
                sharedContacts,
                playStatus = AudioMessageManager.PLAY_STATUS_NOT_PLAY,
                receiverIds = receiverIds
            )
            return textMessage
        }
    }

    private suspend fun handleGroupKeyMessage(content: SignalServiceDataClass): Message? {
        val groupKeyMessage = content.signalServiceContent?.groupKeyMessage ?: return null
        val gid = groupKeyMessage.groupId.toByteArray().transformGroupIdFromServerToLocal()
        val rGroup = groupKeyMessage.groupRootKey.toByteArray()
        L.i { "[GE] Received GroupKeyMessage for group $gid from ${content.senderId}" }
        val saved = groupCryptoRepo.saveOrRotateRGroup(
            gid,
            rGroup,
            groupKeyMessage.keyVersion 
        )
        if (saved) {
            
            
            
            groupUtil.fetchAndSaveSingleGroupInfo(gid, true)
        }
        return null 
    }

    private suspend fun handleClientNotifyMessage(signalServiceDataClass: SignalServiceDataClass, tag: String): Message? {
        val notifyMessage = signalServiceDataClass.signalServiceContent?.notifyMessage
        L.i { "[Message][${tag}] handleClientNotifyMessage -> timestamp:${signalServiceDataClass.messageId}" }
        if (notifyMessage != null) {
        }
        return null
    }

    
    private suspend fun handleFocusNotify(message: TTNotifyMessage, tag: String) {
        val focusJson = message.data?.focus
        val snapshot = focusJson?.let { runCatching { gson.fromJson(it, FocusSnapshot::class.java) }.getOrNull() }
        if (snapshot == null) {
            L.w { "[Message][${tag}] focus notify without a readable snapshot, skip" }
            return
        }
        groupFocusRepository.apply(snapshot, message.data?.trigger)
    }

    private suspend fun handleNotifyMessage(
        content: SignalServiceDataClass,
        tag: String,
        admission: MessagingTicket,
    ) {
        val (envelop, _, notifyMessageContent) = content
        if (notifyMessageContent != null) {
            val message = notifyMessageContent
            L.i { "[Message][${tag}] process notify message -> timestamp:${envelop.timestamp}  device:${envelop.sourceDevice}  data:${message.notifyType}" }
            if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CONVERSATION_SHARE_SETTING) {
                
                message.data?.let { data ->
                    updateDisappearingTime(content.conversation, data.messageExpiry, data.messageClearAnchor, admission)
                    conversationSettingsManager.emitConversationSettingUpdate(
                        conversationId = content.conversation.id,
                        messageExpiry = data.messageExpiry.toLong(),
                        messageClearAnchor = data.messageClearAnchor
                    )
                }
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING) {
                
                val data = message.data
                data?.conversation?.let {
                    kotlin.runCatching {
                        val notifyConversation = gson.fromJson(it.toString(), NotifyConversation::class.java)
                        
                        
                        settingsApplier.applyServerSnapshot(
                            ServerConfigSnapshot.fromNotify(notifyConversation),
                            SnapshotSource.Push(
                                serverTimestamp = data.serverTimestamp.takeIf { ts -> ts > 0 } ?: envelop.timestamp,
                                changeType = data.changeType,
                                operator = data.operator,
                                operatorDeviceId = data.operatorDeviceId,
                            )
                        )
                        
                        if (notifyConversation.remark != null) {
                            ContactorUtil.updateRemark(notifyConversation.conversation, notifyConversation.remark)
                        }
                        if (notifyConversation.remarkAvatar != null) {
                            ContactorUtil.updateRemarkAvatar(notifyConversation.conversation, notifyConversation.remarkAvatar)
                        }
                    }.onFailure {
                        L.e { "[Message][${tag}] handle conversation setting notify message fail: ${it.stackTraceToString()}" }
                    }
                }
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_GROUP) {
                groupUpdater.handleGroupNotifyMessage(message, content, admission)
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_UPDATE_CONTACT) {
                contactsUpdater.updateBySignalNotifyMessage(message, admission)
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_ADD_FRIEND) {
                message.data?.let { data ->
                    when (data.actionType) {
                        TTNotifyMessage.NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST ->
                            data.operatorInfo?.operatorId?.let { id ->
                                dbRoomStore.createRoomIfNotExist(For.Account(id))
                                ContactorUtil.updateContactRequestStatus(id)
                                ContactorUtil.getContactWithID(context, id)
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
                        lCallManagerProvider.get().handleCallEndNotification(roomId)
                    }
                }
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_RESET_IDENTITY_KEY) {
                val data = message.data ?: return
                val operator = data.operator ?: return
                L.i { "[Message][${tag}] process reset identity key notify message -> operator=$operator, resetTime=${data.resetIdentityKeyTime}" }
                messageArchiveManager.archiveMessagesByResetIdentityKey(operator, data.resetIdentityKeyTime)
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CRITICAL_ALERT || message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CRITICAL_ALERT_V2) {
                val data = message.data ?: return
                L.d { "[Message][${tag}] process critical alert notify message -> notifyType=${message.notifyType}, source=${data.source}, showCriticalAlert=${data.showCriticalAlert}" }

                val serverTimestamp = envelop.systemShowTimestamp
                if (!messageNotificationUtil.isCriticalAlertTimestampValid(serverTimestamp)) {
                    L.w { "[Message][${tag}] critical alert notify message expired, skip. serverTimestamp=$serverTimestamp" }
                    return
                }

                if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CRITICAL_ALERT && !data.showCriticalAlert) {
                    L.i { "[Message][${tag}] critical alert notify message 20 is not show" }
                    return
                }

                if (data.source == globalServices.myId && data.sourceDevice == DEFAULT_DEVICE_ID) {
                    L.i { "[Message][${tag}] critical alert notify message is from myself" }
                    return
                }

                val conversationId = data.conversation?.asString ?: return
                val source = data.source ?: return
                val forWhat = if (ValidatorUtil.isGid(conversationId)) {
                    For.Group(conversationId)
                } else {
                    For.Account(conversationId)
                }
                val (title, content) = LCallManager.getCriticalAlertNotificationContent(conversationId, source)
                val timestamp = data.timestamp
                L.i { "[Message][${tag}] handle notify critical alert: conversationId=$conversationId, timestamp=$timestamp, serverTimestamp=$serverTimestamp" }
                if (data.source != globalServices.myId && data.showCriticalAlert) {
                    messageNotificationUtil.showCriticalAlert(forWhat, title, content, timestamp, data.roomId)
                } else {
                    L.i { "[Message][${tag}] critical alert notification not shown (source=myself or showCriticalAlert=false)" }
                }

                createCriticalAlertMessage(serverTimestamp, timestamp, source, forWhat, data.showCriticalAlert, data.sourceDevice, data.roomId)

            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_FOCUS) {
                handleFocusNotify(message, tag)
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_WEAK_CONTACT) {
                val data = message.data
                val uid = data?.uid
                if (uid.isNullOrEmpty()) {
                    L.w { "[Message][${tag}] weakContact missing uid, skip. changeType=${data?.changeType}" }
                    return
                }
                
                
                L.i { "[Message][${tag}] weakContact notify uid=$uid changeType=${data.changeType} reason=${data.reason} expireTime=${data.expireTime}" }
                when (data.changeType) {
                    0 -> {
                        val snapshot = ContactorModel().also {
                            it.id = uid
                            it.name = data.name
                            it.avatar = data.avatar
                        }
                        
                        
                        val deleteTime = data.deleteTime.takeIf { it > 0 } ?: data.serverTimestamp
                        weakContactReconciler.enterWeak(uid, data.expireTime, data.reason, deleteTime, snapshot)
                    }
                    
                    1 -> weakContactReconciler.removeWeak(uid)

                    else -> L.w { "[Message][${tag}] weakContact unknown changeType=${data.changeType} uid=$uid" }
                }

            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_SECONDARY_DEVICE_KEPT) {
                
                val data = message.data
                L.i {
                    "[Message][${tag}] secondary devices kept newDeviceNamed=${!data?.newDeviceName.isNullOrEmpty()} " +
                        "kept=${data?.keptDeviceIds?.size ?: 0} keptTime=${data?.keptTime}"
                }
            } else {
                L.w { "[Message][${tag}] Unknown notifyType: ${message.notifyType}, timestamp: ${envelop.timestamp}" }
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
                
                
                val localId = UUID.randomUUID().toString()
                val attachmentPath = AttachmentPathResolver.stagingFileFor(localId, fileName)
                val attachment = createAttachmentFormPointer(
                    attachmentPointer,
                    fileName,
                    attachmentPath,
                    localId = localId,
                    isForwardCopy = true
                )
                attachments.add(attachment)
            }
        }
        if (forward.forwardsCount > 0) {
            for (forward1 in forward.forwardsList) {
                forwards.add(createForward(messageId, forward1, forwardContactIds, source, conversationId, forward1.serverTimestamp))
            }
        }
        if (forward.mentionsCount > 0) {
            for (mention in forward.mentionsList) {
                mentions.add(Mention(mention.start, mention.length, mention.uid, mention.type.number))
            }
        }

        return Forward(forward.id, forward.type, forward.isFromGroup, forward.author, forward.text, attachments, forwards, mentions, serverTimestamp)
    }

    








    private fun createAttachmentFormPointer(
        attachmentPointer: SignalServiceProtos.AttachmentPointer,
        fileName: String?,
        attachmentPath: String,
        localId: String = UUID.randomUUID().toString(),
        isForwardCopy: Boolean = false
    ) = Attachment(
        "",
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
        AttachmentStatus.LOADING.code,
        localId = localId,
        isForwardCopy = isForwardCopy
    )

    






    internal fun createQuoteThumbnailAttachment(pointer: SignalServiceProtos.AttachmentPointer): Attachment {
        val thumbBytes = pointer.thumbnail?.toByteArray()?.takeIf { it.isNotEmpty() }
        return Attachment(
            id = "",
            authorityId = 0L,
            contentType = pointer.contentType,
            key = null,
            size = thumbBytes?.size ?: 0,
            thumbnail = thumbBytes,
            digest = null,
            fileName = pointer.fileName.takeIf { pointer.hasFileName() },
            flags = pointer.flags,
            width = pointer.width,
            height = pointer.height,
            path = null,
            status = AttachmentStatus.SUCCESS.code
        )
    }

    private fun updateDisappearingTime(forWhat: For, messageExpiry: Int, messageClearAnchor: Long, admission: MessagingTicket) {
        messageArchiveManager.updateLocalArchiveTime(forWhat, messageExpiry.toLong(), messageClearAnchor, admission)
    }

    








    private suspend fun createCriticalAlertMessage(serverTimestamp: Long, timestamp: Long, source: String, forWhat: For, showCriticalAlert: Boolean, sourceDevice: Int, roomId: String?) {
        localMessageCreator.createCriticalAlertMessage(serverTimestamp, timestamp, For.Account(source), forWhat, sourceDevice)
        
        if (source != globalServices.myId && showCriticalAlert) {
            
            criticalAlertHighlightWriter.highlightIfUnread(forWhat.id, roomId, serverTimestamp)
        }
    }
}