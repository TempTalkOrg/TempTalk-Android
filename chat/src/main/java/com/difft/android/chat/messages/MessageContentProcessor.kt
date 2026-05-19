package com.difft.android.chat.messages

import android.content.Context
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.chat.call.LChatToCallController
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.ContactsUpdater
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupUpdater
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.websocket.api.messages.NotifyConversation
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.difft.android.websocket.api.util.mapToMessageId
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
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
import org.difft.app.database.isGroupMember
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.wcdb
import com.difft.android.chat.util.MessageNotificationUtil
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Takes data about a decrypted message, transforms it into user-presentable data, and writes that
 * data to our data stores.
 */
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
    private val localMessageCreator: LocalMessageCreator,
    private val groupCryptoRepo: com.difft.android.chat.crypto.GroupCryptoRepo,
    private val groupUtil: com.difft.android.chat.group.GroupUtil,
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
            if (serviceContent.hasGroupKeyMessage()) {
                return handleGroupKeyMessage(content)
            } else if (serviceContent.hasNotifyMessage()) {
                return handleClientNotifyMessage(content)
            } else if (serviceContent.hasDataMessage()) {
                return handleDataMessage(
                    content,
                    isSyncMessage = false
                )
            } else if (serviceContent.hasSyncMessage()) {
                if (content.senderId != globalServices.myId) {
                    L.w { "[Message][${tag}] received sync message from another id, senderId:${content.senderId}." }
                    return null
                }
                if (serviceContent.syncMessage.hasSent()) {
                    return handleDataMessage(
                        content,
                        isSyncMessage = true
                    )
                } else if (serviceContent.syncMessage.readCount > 0) {
                    L.i { "[Message][${tag}] process sync read message -> timestamp:${content.signalServiceEnvelope.timestamp}  device:${content.signalServiceEnvelope.sourceDevice}" }
                    val firstReadMessage = serviceContent.syncMessage.readList[0]
                    if (firstReadMessage.messageMode == SignalServiceProtos.Mode.CONFIDENTIAL) {
                        // Sync: confidential message read on another device, delete locally
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
                            messageStore.updateMessageReadTime(forWhat.id, firstReadMessage.timestamp)
                        }
                    }
                } else if (serviceContent.syncMessage.hasForwardNoticeSync()) {
                    L.i { "[Message][${tag}] process forward notice sync -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                    return handleForwardNoticeSync(content, serviceContent.syncMessage.forwardNoticeSync, tag)
                } else return null
            } else if (serviceContent.hasReceiptMessage()) {
                L.i { "[Message][${tag}] process receipt message -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                receiptMessageHelper.handleReceiptMessage(serviceContent.receiptMessage, content)
            } else if (serviceContent.hasCallMessage()) {
                L.i { "[Message][${tag}] process call message -> timestamp:${content.signalServiceEnvelope.timestamp}" }
                LCallManager.removePendingMessage(content.signalServiceEnvelope.source, content.signalServiceEnvelope.timestamp.toString())
                lCallManagerProvider.get().handleCallMessage(content)
            } else if (serviceContent.hasForwardNotice()) {
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

    /**
     * Handle a top-level `Content.forwardNotice` (primary path: from peer / group
     * / self-as-NTS). Self-sync uses a separate path via SyncMessage.forwardNoticeSync
     * — see [handleForwardNoticeSync].
     *
     * Delegates the "resolve names → render showContent → persist NotifyMessage"
     * pipeline to [LocalMessageCreator.createForwardNoticeMessage].
     *
     * Uses the function parameter [tag] for logging — `this.tag` is a mutable
     * `var` and may be overwritten by a concurrent call on the @Singleton.
     */
    private suspend fun handleForwardNoticeMessage(
        content: SignalServiceDataClass,
        forwardNotice: SignalServiceProtos.ForwardNoticeMessage,
        operatorId: String,
        conversation: For,
        tag: String
    ): Message? {
        val envelop = content.signalServiceEnvelope
        // Scene guard: proto2-lite decodes both "unset" and "unknown future value"
        // as the first declared enum value (= UNKNOWN). `toKotlinEnum()` maps that
        // to null; we drop rather than silently rendering as a wrong scene.
        val sceneKt = forwardNotice.scene.toKotlinEnum() ?: run {
            L.w {
                "[Message][${tag}] forwardNotice has unknown/unset scene=${forwardNotice.scene}, drop"
            }
            return null
        }
        // Cross-conversation injection guard: if resolved conversation is a group,
        // verify the envelope sender is actually a member of that group. Without
        // this check, a peer could craft payload.conversation.groupId pointing at
        // any group the victim is in and inject a fake "forwarded" system message
        // via a 1v1 envelope.
        if (conversation is For.Group) {
            val senderId = envelop.source
            if (!wcdb.isGroupMember(conversation.id, senderId)) {
                L.w {
                    "[Message][${tag}] forwardNotice group=${conversation.id} " +
                        "envelope.source=$senderId is NOT a member of that group, " +
                        "drop (cross-conversation injection attempt)"
                }
                return null
            }
        }
        val noticeData = ForwardNoticeData(
            scene = sceneKt,
            // Pass the full list through — the renderer owns display truncation /
            // de-duplication, and only the rendered string is persisted. Contact
            // lookup in LocalMessageCreator is a single batch query so list length
            // has no N+1 cost.
            sourceAuthorIds = forwardNotice.sourceAuthorIdsList.toList(),
            // Protocol-violation degrade: coerce to >= 1 so plurals always renders.
            // Do NOT raise to authorIds.size — a peer could craft a payload with
            // messageCount=1 and 100 authors to inflate the displayed count.
            messageCount = maxOf(1, forwardNotice.messageCount)
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

    /**
     * Handle a self-sync `SyncMessage.forwardNoticeSync` (a ForwardNoticeMessage).
     * The outer `handleMessage` has already verified `senderId == myId`; the
     * Drops silently (returns null + warn log) if the payload is missing a
     * well-formed `conversation` field. Without this guard the upstream
     * conversation lazy would fall back to `For.Account(senderId=myId)` and
     * silently route the notice into Note-to-Self instead of the real peer.
     *
     * Operator is always me (sender of the sync).
     */
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

    private suspend fun handleDataMessage(
        content: SignalServiceDataClass,
        isSyncMessage: Boolean
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

            // Fallback: extract R_group from group message if present
            if (message.hasGroup() && message.group.hasGroupRootKey()) {
                val saved = groupCryptoRepo.saveRGroupIfNeeded(
                    content.conversation.id,
                    message.group.groupRootKey.toByteArray()
                )
                if (saved) {
                    L.i { "[GE] Fallback key extracted from group message for ${content.conversation.id}" }
                    // Key just arrived — refresh group info so decrypted name/avatar
                    // replaces the placeholder in DB. Without this, the group stays
                    // showing "Encrypted Group" until the next explicit fetch.
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

        // Check protocol version first - if message requires newer version, save as unsupported message
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
            // Fetch contactor info for forward authors if not already cached
            // Note: AsyncMessageJobsManager uses in-memory cache to skip already confirmed contactors
            if (forwardContactIds.isNotEmpty()) {
                asyncMessageJobsManager.needFetchSpecifiedContactors(forwardContactIds.toList())
            }
        }
        // Handle screenshot
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
            // Check for empty message - don't save to database if no valid content
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

            // Set contentText for screenshot messages directly for display
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
        groupCryptoRepo.saveRGroupIfNeeded(gid, rGroup)
        // Always refresh to ensure decrypted fields are up to date
        groupUtil.fetchAndSaveSingleGroupInfo(gid, true)
        return null // Not displayed in UI
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
                // Type 5: messageExpiry, messageClearAnchor
                message.data?.let { data ->
                    updateDisappearingTime(content.conversation, data.messageExpiry, data.messageClearAnchor)
                    // 通知 ViewModel 更新 (携带具体值)
                    conversationSettingsManager.emitConversationSettingUpdate(
                        conversationId = content.conversation.id,
                        messageExpiry = data.messageExpiry.toLong(),
                        messageClearAnchor = data.messageClearAnchor
                    )
                }
            } else if (message.notifyType == TTNotifyMessage.NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING) {
                // Type 4: muteStatus, blockStatus, confidentialMode
                message.data?.conversation?.let {
                    kotlin.runCatching {
                        val notifyConversation = Gson().fromJson(it.toString(), NotifyConversation::class.java)
                        // 批量更新配置到数据库
                        dbRoomStore.updateConversationSettings(
                            roomId = notifyConversation.conversation,
                            muteStatus = notifyConversation.muteStatus,
                            blockStatus = notifyConversation.blockStatus,
                            confidentialMode = notifyConversation.confidentialMode
                        )
                        // Gate on `!= null`: empty string means "clear", null means "not in this notify".
                        if (notifyConversation.remark != null) {
                            ContactorUtil.updateRemark(notifyConversation.conversation, notifyConversation.remark)
                        }
                        if (notifyConversation.remarkAvatar != null) {
                            ContactorUtil.updateRemarkAvatar(notifyConversation.conversation, notifyConversation.remarkAvatar)
                        }
                        conversationSettingsManager.emitConversationSettingUpdate(
                            conversationId = notifyConversation.conversation,
                            muteStatus = notifyConversation.muteStatus,
                            blockStatus = notifyConversation.blockStatus,
                            confidentialMode = notifyConversation.confidentialMode
                        )
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

                // 忽略自己的消息，避免重复提醒
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

                // 本地生成 critical alert 文本消息
                createCriticalAlertMessage(serverTimestamp, timestamp, source, forWhat, data.showCriticalAlert, data.sourceDevice)

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
        if (forward.mentionsCount > 0) {
            for (mention in forward.mentionsList) {
                mentions.add(Mention(mention.start, mention.length, mention.uid, mention.type.number))
            }
        }

        return Forward(forward.id, forward.type, forward.isFromGroup, forward.author, forward.text, attachments, forwards, mentions, serverTimestamp)
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

    /**
     * 创建本地 Critical Alert 文本消息
     * @param serverTimestamp 服务器时间戳
     * @param source 消息发送者ID
     * @param forWhat 消息所属会话
     * @param showCriticalAlert 控制会话的 Critical Alert 高亮状态
     * @param sourceDevice 消息所属设备类型
     */
    private suspend fun createCriticalAlertMessage(serverTimestamp: Long, timestamp: Long, source: String, forWhat: For, showCriticalAlert: Boolean, sourceDevice: Int) {
        localMessageCreator.createCriticalAlertMessage(serverTimestamp, timestamp, For.Account(source), forWhat, sourceDevice)
        // 设置会话列表高亮（仅当消息未读时）
        if (source != globalServices.myId && showCriticalAlert) {
            dbRoomStore.setCriticalAlertIfUnread(forWhat.id, serverTimestamp)
        }
    }
}