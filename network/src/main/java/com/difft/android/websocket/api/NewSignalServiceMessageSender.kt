package com.difft.android.websocket.api

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.globalServices
import com.difft.android.websocket.api.messages.DetailMessageType
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.messages.SendMessageResult
import com.difft.android.websocket.api.push.exceptions.NoValidRecipientKeysException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.UnregisteredUserException
import com.difft.android.websocket.api.services.NewMessagingService
import com.difft.android.websocket.internal.ServiceResponseProcessor
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_CURRENT_VERSION
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_MINIMUM_SUPPORTED_VERSION
import com.difft.android.websocket.api.util.RustEncryptionException
import com.difft.android.websocket.api.util.SignalServiceContentCreator
import com.difft.android.websocket.api.util.transformGroupIdFromLocalToServer
import com.difft.android.websocket.api.util.toOutgoingReadPositionEntity
import com.difft.android.websocket.api.websocket.WebSocketUnavailableException
import com.difft.android.network.signal.MessageSendRepository
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import com.difft.android.websocket.internal.push.exceptions.InvalidUnidentifiedAccessHeaderException
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException
import com.difft.android.base.utils.Base64
import com.google.protobuf.ByteString
import difft.android.messageserialization.For
import org.signal.libsignal.protocol.InvalidKeyException
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage
import org.whispersystems.signalservice.internal.push.SyncMessageKt
import org.whispersystems.signalservice.internal.push.content
import org.whispersystems.signalservice.internal.push.conversationId
import org.whispersystems.signalservice.internal.push.encryptContent
import org.whispersystems.signalservice.internal.push.syncMessage
import java.io.IOException
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NewSignalServiceMessageSender @Inject constructor(
    private val messagingService: NewMessagingService,
    @param:Named("message_sender_max_envelope_size")
    private val maxEnvelopeSize: Long,
    private val messageEncryptor: INewMessageContentEncryptor,
    private val conversationManager: ConversationManager,
    private val messageSendRepository: MessageSendRepository,
) {
    companion object {
        private const val TAG = "SignalServiceMessageSender"
        private const val RETRY_COUNT = 3
    }

    




    private fun requireAdmitted(admission: MessagingTicket, what: String) {
        if (admission.kind != AdmissionKind.SEND || admission.isClosed) {
            L.e { "[MigrationPause] event=send_without_ticket what=$what kind=${admission.kind} closed=${admission.isClosed}" }
            error("send without an open send ticket: $what")
        }
    }

    private val localAddress: For = For.Account(globalServices.myId)
    private val contentCreator: SignalServiceContentCreator = SignalServiceContentCreator(maxEnvelopeSize)

    








    suspend fun sendDataMessage(
        recipient: For,
        room: For,
        message: SignalServiceProtos.DataMessage,
        notification: NewOutgoingPushMessage.Notification?,
        admission: MessagingTicket,
    ): SendMessageResult {
        requireAdmitted(admission, "data")
        L.i { "[Message] [${message.timestamp}] Sending a data message." }

        val timestamp = message.timestamp
        val content = contentCreator.createFrom(message)

        
        val syncContent: String? = if (recipient is For.Account) {
            if (conversationManager.hasPublicKeyInfoData(room).not()) {
                L.i { "[Message] generateSyncContent: updating public key info for room ${room.id}" }
                conversationManager.updatePublicKeyInfoData(room)
            }
            generateSyncContent(content, room, recipient.id, timestamp)
        } else {
            null
        }

        val result: SendMessageResult = sendMessage(
            recipient,
            room,
            timestamp,
            content,
            false,
            Optional.empty(),
            Optional.empty(),
            notification,
            syncContent
        )
        L.i { "[Message] sendDataMessage result is Success: ${result.success}" }
        
        return result
    }

    




    suspend fun sendGroupKeyMessage(
        recipient: For,
        room: For,
        groupKeyMessage: SignalServiceProtos.GroupKeyMessage,
        admission: MessagingTicket,
    ): SendMessageResult {
        requireAdmitted(admission, "groupKey")
        val timestamp = System.currentTimeMillis()
        val content = contentCreator.createFrom(groupKeyMessage)
        L.i { "[Message] [$timestamp] Sending group key message to ${recipient.id}" }
        if (conversationManager.hasPublicKeyInfoData(room).not()) {
            L.i { "[Message] sendGroupKeyMessage: updating public key info for room ${room.id}" }
            conversationManager.updatePublicKeyInfoData(room)
        }
        return sendMessage(
            recipient, room, timestamp, content, false,
            Optional.empty(), Optional.empty(), null, null
        )
    }

    



    private suspend fun generateSyncContent(
        content: Content,
        room: For,
        recipientId: String,
        timestamp: Long
    ): String? {
        return try {
            val syncMessageContent = createMultiDeviceSentTranscriptContent(
                content,
                Optional.of(recipientId),
                timestamp
            )

            
            val publicKeyInfos = conversationManager.getPublicKeyInfos(room)
            val myPublicKey = publicKeyInfos.firstOrNull { it.uid == localAddress.id }?.identityKey

            if (myPublicKey.isNullOrBlank()) {
                L.w { "[Message] generateSyncContent: my public key not found in room ${room.id}, skip syncContent" }
                return null
            }

            val encryptedMessage = messageEncryptor.encryptOneToOneMessage(
                syncMessageContent.toByteArray(),
                myPublicKey
            )

            val encryptedMessageContent = encryptContent {
                version = MESSAGE_CURRENT_VERSION
                cipherText = ByteString.copyFrom(encryptedMessage.cipherText)
                eKey = ByteString.copyFrom(encryptedMessage.eKey)
                identityKey = ByteString.copyFrom(encryptedMessage.identityKey)
                signedEKey = ByteString.copyFrom(encryptedMessage.signedEKey)
            }

            val syncContentString = Base64.encodeBytes(
                byteArrayOf(
                    intsToByteHigh(
                        MESSAGE_CURRENT_VERSION,
                        MESSAGE_MINIMUM_SUPPORTED_VERSION
                    )
                ) + encryptedMessageContent.toByteArray()
            )

            L.i { "[Message] generateSyncContent: successfully generated syncContent for room ${room.id}" }
            syncContentString
        } catch (e: Exception) {
            L.e { "[Message] generateSyncContent failed: ${e.stackTraceToString()}" }
            null
        }
    }

    private fun createMultiDeviceSentTranscriptContent(
        content: Content?,
        recipient: Optional<String>,
        timestamp: Long,
        serverTimestamp: Long? = null,
        sequenceId: Long? = null
    ): Content {
        val dataMessage =
            if (content != null && content.hasDataMessage()) content.dataMessage else null
        return content {
            syncMessage = syncMessage {
                sent = SyncMessageKt.sent {
                    this.timestamp = timestamp
                    serverTimestamp?.let { this.serverTimestamp = it }
                    sequenceId?.let { this.sequenceId = it }
                    if (recipient.isPresent) {
                        this.destination = recipient.get()
                    }
                    if (dataMessage != null) {
                        this.message = dataMessage
                        if (dataMessage.expireTimer > 0) {
                            this.expirationStartTimestamp = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
    }


    private fun createMultiDeviceNotifyContent(content: Content?): Content {
        val notifyMessage = if (content != null && content.hasNotifyMessage()) content.notifyMessage else null
        return content {
            this.syncMessage = syncMessage {
                if (notifyMessage != null) {
                }
            }
        }
    }

    private suspend fun sendMessage(
        recipient: For,
        room: For,
        timestamp: Long,
        content: Content,
        readReceipt: Boolean,
        readPositionEntity: Optional<NewOutgoingPushMessage.ReadPositionEntity>,
        realSourceEntity: Optional<NewOutgoingPushMessage.RealSourceEntity>,
        notification: NewOutgoingPushMessage.Notification?,
        syncContent: String? = null,  
    ): SendMessageResult {
        val startTime = System.currentTimeMillis()

        for (i in 0 until RETRY_COUNT) {
            try {
                val newOutgoingMessage = createNewOutgoingPushMessage(
                    recipient,
                    room,
                    content,
                    notification,
                    readReceipt,
                    readPositionEntity.orElse(null),
                    realSourceEntity.orElse(null),
                    syncContent,
                    timestamp = timestamp,
                )
                try {
                    val response = if (recipient is For.Group) {
                        ServiceResponseProcessor.DefaultProcessor(
                            messagingService.sendToGroup(
                                newOutgoingMessage,
                                recipient.id,
                            )
                        ).resultOrThrow
                    } else {
                        ServiceResponseProcessor.DefaultProcessor(
                            messagingService.send(
                                newOutgoingMessage,
                                recipient.id,
                            )
                        ).resultOrThrow
                    }
                    if (response.status == 11001 || response.data!!.missing.isNullOrEmpty().not() || response.data!!.stale.isNullOrEmpty().not()) {
                        L.w {
                            "[Message] [sendMessage][$timestamp] retry(ws) status=${response.status} " +
                                "missing=${response.data!!.missing?.size ?: 0} " +
                                "stale=${response.data!!.stale?.size ?: 0} " +
                                "recipient=${recipient.id} roomType=${if (room is For.Group) "group" else "dm"}"
                        }
                        conversationManager.updateConversationMemberData(room)
                        val flaggedUids = response.data!!.missing.orEmpty().mapNotNull { it.uid } +
                                response.data!!.stale.orEmpty().mapNotNull { it.uid }
                        if (flaggedUids.isNotEmpty()) {
                            
                            
                            
                            val staleUids = (flaggedUids +
                                    listOfNotNull((recipient as? For.Account)?.id)).distinct()
                            L.i { "[Message] [sendMessage][$timestamp] retry(ws) narrowed staleUids size=${staleUids.size}" }
                            conversationManager.updatePublicKeyInfoData(staleUids)
                        } else {
                            
                            
                            
                            
                            if (recipient is For.Group) {
                                L.i { "[Message] [sendMessage][$timestamp] retry(ws) fallback=group_for_keyed_refresh gid=${recipient.id}" }
                                conversationManager.updatePublicKeyInfoData(room)
                            } else {
                                L.i { "[Message] [sendMessage][$timestamp] retry(ws) fallback=account_narrow uid=${recipient.id}" }
                                conversationManager.updatePublicKeyInfoData(listOf(recipient.id))
                            }
                        }
                        continue 
                    } else {
                        return SendMessageResult.success(
                            recipient.id,
                            response.data!!.isNeedsSync,
                            System.currentTimeMillis() - startTime,
                            response.data!!.systemShowTimestamp,
                            response.data!!.notifySequenceId,
                            response.data!!.sequenceId
                        )
                    }
                } catch (e: InvalidUnidentifiedAccessHeaderException) {
                    L.e { "[Message] [sendMessage][$timestamp] InvalidUnidentifiedAccessHeaderException (${e.stackTraceToString()})" }
                    throw e
                } catch (e: UnregisteredUserException) {
                    L.e { "[Message] [sendMessage][$timestamp] UnregisteredUserException (${e.stackTraceToString()})" }
                    throw e
                } catch (e: MismatchedDevicesException) {
                    L.e { "[Message] [sendMessage][$timestamp] MismatchedDevicesException (${e.stackTraceToString()})" }
                    throw e
                } catch (e: StaleDevicesException) {
                    L.e { "[Message] [sendMessage][$timestamp] StaleDevicesException (${e.stackTraceToString()})" }
                    throw e
                } catch (e: WebSocketUnavailableException) {
                    L.w { "[Message][$timestamp] pipe unavailable, falling back to http: ${e.javaClass.simpleName}: ${e.message}" }
                } catch (e: IOException) {
                    L.w { "[Message][$timestamp] pipe failed, falling back to http: ${e.javaClass.simpleName}: ${e.message}" }
                }
                val response = messageSendRepository.sendMessage(newOutgoingMessage, recipient)
                if (response.status == 11001 || response.data!!.missing.isNullOrEmpty().not() || response.data!!.stale.isNullOrEmpty().not()) {
                    L.w {
                        "[Message] [sendMessage][$timestamp] retry(http) status=${response.status} " +
                            "missing=${response.data!!.missing?.size ?: 0} " +
                            "stale=${response.data!!.stale?.size ?: 0} " +
                            "recipient=${recipient.id} roomType=${if (room is For.Group) "group" else "dm"}"
                    }
                    conversationManager.updateConversationMemberData(room)
                    val flaggedUids = response.data!!.missing.orEmpty().mapNotNull { it.uid } +
                            response.data!!.stale.orEmpty().mapNotNull { it.uid }
                    if (flaggedUids.isNotEmpty()) {
                        val staleUids = (flaggedUids +
                                listOfNotNull((recipient as? For.Account)?.id)).distinct()
                        L.i { "[Message] [sendMessage][$timestamp] retry(http) narrowed staleUids size=${staleUids.size}" }
                        conversationManager.updatePublicKeyInfoData(staleUids)
                    } else {
                        
                        if (recipient is For.Group) {
                            L.i { "[Message] [sendMessage][$timestamp] retry(http) fallback=group_for_keyed_refresh gid=${recipient.id}" }
                            conversationManager.updatePublicKeyInfoData(room)
                        } else {
                            L.i { "[Message] [sendMessage][$timestamp] retry(http) fallback=account_narrow uid=${recipient.id}" }
                            conversationManager.updatePublicKeyInfoData(listOf(recipient.id))
                        }
                    }
                    continue 
                } else {
                    return SendMessageResult.success(
                        recipient.id,
                        response.data!!.isNeedsSync,
                        System.currentTimeMillis() - startTime,
                        response.data!!.systemShowTimestamp,
                        response.data!!.notifySequenceId,
                        response.data!!.sequenceId
                    )
                }
            } catch (afe: NonSuccessfulResponseCodeException) {
                L.e { "[Message] [sendMessage][$timestamp] NonSuccessfulResponseCodeException. (${afe.stackTraceToString()})" }
                throw afe
            } catch (ike: InvalidKeyException) {
                L.e { "[Message] [sendMessage][$timestamp] Invalid key exception. (${ike.stackTraceToString()})" }
            } catch (mde: MismatchedDevicesException) {
                L.e { "[Message] [sendMessage][$timestamp] Handling mismatched devices.(${mde.stackTraceToString()})" }
            } catch (ste: StaleDevicesException) {
                L.e { "[Message] [sendMessage][$timestamp] Handling stale devices.(${ste.stackTraceToString()})" }
            } catch (re: RustEncryptionException) {
                val errorMessage = re.stackTraceToString()
                val isKeyLengthError = errorMessage.contains("KeyDataLengthException", ignoreCase = true)

                if (isKeyLengthError) {
                    L.e { "[Message] [sendMessage][$timestamp] Rust encryption key length exception. (${errorMessage})" }
                    L.w { "[Message] [sendMessage][$timestamp] Forcing public key update (attempt ${i + 1}/$RETRY_COUNT)" }

                    conversationManager.updateConversationMemberData(room)
                    
                    
                    if (recipient is For.Group) {
                        L.i { "[Message] [sendMessage][$timestamp] retry(rust) path=group_for_keyed_refresh gid=${recipient.id}" }
                        conversationManager.updatePublicKeyInfoData(room)
                    } else {
                        L.i { "[Message] [sendMessage][$timestamp] retry(rust) path=account_narrow uid=${recipient.id}" }
                        conversationManager.updatePublicKeyInfoData(listOf(recipient.id))
                    }
                } else {
                    L.e { "[Message] [sendMessage][$timestamp] Rust encryption exception. (${errorMessage})" }
                }
            }
        }

        throw IOException("Failed to resolve conflicts after $RETRY_COUNT attempts!")
    }

    private suspend fun createNewOutgoingPushMessage(
        recipient: For,
        room: For,
        content: Content,
        notification: NewOutgoingPushMessage.Notification?,
        readReceipt: Boolean,
        readPositionEntity: NewOutgoingPushMessage.ReadPositionEntity?,
        realSourceEntity: NewOutgoingPushMessage.RealSourceEntity?,
        syncContent: String? = null,  
        
        
        timestamp: Long,
    ): NewOutgoingPushMessage {
        if (conversationManager.hasPublicKeyInfoData(room).not()) {
            
            
            
            when (conversationManager.updatePublicKeyInfoDataResult(room)) {
                PublicKeyUpdateResult.Updated -> {  }

                PublicKeyUpdateResult.EntityInvalid ->
                    throw NoValidRecipientKeysException(
                        "recipient keys unavailable (group invalid, status != 0) room=${room.id}"
                    )

                PublicKeyUpdateResult.ServerEmpty,
                PublicKeyUpdateResult.FetchFailed,
                PublicKeyUpdateResult.Unresolved ->
                    throw IOException("Failed to update public key info data (transient) room=${room.id}")
            }.exhaustive  
        } else {
            L.i { "[Message] public key info is ready" }
        }
        val publicKeyInfos: List<PublicKeyInfo> = conversationManager.getPublicKeyInfos(room)
            .filter { info ->
                val isValid = info.identityKey.isNotBlank()
                if (!isValid) {
                    L.w { "[Message] Filtering out PublicKeyInfo with empty identityKey for uid: ${info.uid}" }
                }
                isValid
            }

        if (publicKeyInfos.isEmpty()) {
            
            
            when (conversationManager.classifyEmptyKeys(room)) {
                PublicKeyUpdateResult.EntityInvalid ->
                    throw NoValidRecipientKeysException(
                        "no valid public key info after filtering, group invalid (status != 0) room=${room.id}"
                    )

                PublicKeyUpdateResult.Updated,
                PublicKeyUpdateResult.ServerEmpty,
                PublicKeyUpdateResult.FetchFailed,
                PublicKeyUpdateResult.Unresolved ->
                    throw IOException("No valid public key info available after filtering (transient) room=${room.id}")
            }.exhaustive  
        }

        val msgType = getMsgType(content)

        val detailMessageType: DetailMessageType = if (content.hasDataMessage()) {
            if (content.dataMessage.hasForwardContext()) DetailMessageType.Forward
            else if (content.dataMessage.contactCount > 0) DetailMessageType.Contact
            else if (content.dataMessage.hasRecall()) DetailMessageType.Recall
            else if (content.dataMessage.hasReaction()) DetailMessageType.Reaction
            else if (content.dataMessage.hasScreenShot()) DetailMessageType.Screenshot
            else if (content.dataMessage.messageMode == SignalServiceProtos.Mode.CONFIDENTIAL) DetailMessageType.Confidential
            else DetailMessageType.Unknown
        } else DetailMessageType.Unknown

        val encryptedMessage = if (recipient is For.Group) {
            val groupPublicKeys: Map<String, String> = publicKeyInfos.associate { it.uid to it.identityKey }
            messageEncryptor.encryptGroupMessage(
                content.toByteArray(),
                groupPublicKeys
            )
        } else {
            val oneToOnePublicKey: String = publicKeyInfos.first { it.uid == recipient.id }.identityKey
            messageEncryptor.encryptOneToOneMessage(
                content.toByteArray(),
                oneToOnePublicKey
            )
        }
        val encryptedMessageContent = encryptContent {
            version = MESSAGE_CURRENT_VERSION
            cipherText = ByteString.copyFrom(encryptedMessage.cipherText)
            eKey = ByteString.copyFrom(encryptedMessage.eKey)
            identityKey = ByteString.copyFrom(encryptedMessage.identityKey)
            signedEKey = ByteString.copyFrom(encryptedMessage.signedEKey)
        }
        val encryptContentString = Base64.encodeBytes(
            byteArrayOf(
                intsToByteHigh(
                    MESSAGE_CURRENT_VERSION,
                    MESSAGE_MINIMUM_SUPPORTED_VERSION
                )
            ) + encryptedMessageContent.toByteArray()
        )

        val recipients = if (recipient is For.Group) {
            publicKeyInfos.map {
                if (it.registrationId == 0) {
                    val error = "For uid: ${it.uid} Registration ID is 0!"
                    L.e { error }
                }
                NewOutgoingPushMessage.Recipient(
                    it.uid,
                    it.registrationId,
                    Base64.encodeBytes(
                        encryptedMessage.ermKeys?.get(it.uid)!!
                    )
                )
            }
        } else {
            listOf(publicKeyInfos.first { it.uid == recipient.id }.let {
                if (it.registrationId == 0) {
                    val error = "For uid: ${it.uid} Registration ID is 0!"
                    L.e { error }
                }
                NewOutgoingPushMessage.Recipient(
                    it.uid,
                    it.registrationId,
                    ""
                )
            })
        }

        val conversation = NewOutgoingPushMessage.Conversation(
            if (room is For.Account) room.id else null,
            if (room is For.Group) room.id else null,
        )

        L.i {
            "[Message] Creating outgoing push message. " +
                    "timestamp=$timestamp, " +
                    "msgType=$msgType (${Envelope.MsgType.forNumber(msgType)?.name ?: "UNKNOWN"}), " +
                    "detailMessageType=${detailMessageType.value} (${detailMessageType.name}), " +
                    "recipientCount=${recipients.size}, " +
                    "conversationType=${if (room is For.Group) "group" else "dm"}, " +
                    "hasSyncContent=${syncContent != null}"
        }

        return NewOutgoingPushMessage.Builder()
            .type(Envelope.Type.ENCRYPTEDTEXT_VALUE)
            .msgType(msgType)
            .detailMessageType(detailMessageType.value)
            .content(encryptContentString)
            .recipients(recipients)
            .realSource(realSourceEntity)
            .conversation(conversation)
            .readReceipt(readReceipt)
            .readPositions(readPositionEntity?.let { listOf() } ?: emptyList())
            .timestamp(timestamp)
            .notification(notification)
            .syncContent(syncContent)  
            .build()
    }

    private fun getMsgType(content: Content): Int {
        var msgType = Envelope.MsgType.MSG_NORMAL_VALUE
        if (content.hasSyncMessage()) {
            msgType = Envelope.MsgType.MSG_SYNC_VALUE
            if (content.syncMessage.readList != null) {
                msgType = Envelope.MsgType.MSG_SYNC_READ_RECEIPT_VALUE
            }
        } else if (content.hasReceiptMessage()) {
            if (content.receiptMessage
                    .type == ReceiptMessage.Type.READ
            ) {
                msgType = Envelope.MsgType.MSG_READ_RECEIPT_VALUE
            } else if (content.receiptMessage
                    .type == ReceiptMessage.Type.DELIVERY
            ) {
                msgType = Envelope.MsgType.MSG_DELIVERY_RECEIPT_VALUE
            }
        } else if (content.hasDataMessage()) {
            if (content.dataMessage.hasRecall()) {
                msgType = Envelope.MsgType.MSG_RECALL_VALUE
            }
        } else if (content.hasGroupKeyMessage()) {
            msgType = Envelope.MsgType.MSG_GROUP_KEY_VALUE
        } else if (content.hasNotifyMessage()) {
            msgType = Envelope.MsgType.MSG_CLIENT_NOTIFY_VALUE
        } else if (content.hasForwardNotice()) {
            msgType = Envelope.MsgType.MSG_FORWARD_NOTICE_VALUE
        } else if (content.hasActivityNotice()) {
            
            msgType = Envelope.MsgType.MSG_ACTIVITY_NOTICE_VALUE
        }
        return msgType
    }

    








    suspend fun sendReceipt(
        recipient: For,
        room: For,
        message: ReceiptMessage,
        readMessages: List<SyncMessage.Read>,
        sendReceiptToSender: Boolean = true,
        sendSyncToSelf: Boolean = true,
        admission: MessagingTicket,
    ) {
        requireAdmitted(admission, "receipt")
        val sendTimeStamp = System.currentTimeMillis()

        if (sendReceiptToSender) {
            val content: Content = createReceiptContent(message)
            val receiptResult = sendMessage(
                recipient,
                room,
                sendTimeStamp,
                content,
                message.type == ReceiptMessage.Type.READ,
                Optional.ofNullable(message.readPosition.toOutgoingReadPositionEntity()),
                Optional.ofNullable(null),
                null
            )
            L.i { "[Message] sendReceiptToSender result success is ${receiptResult.isSuccess()}" }
        } else {
            L.i { "[Message] Skip sending read receipt to sender" }
        }

        if (sendSyncToSelf) {
            val syncMessageContent = createMultiDeviceReadContent(readMessages)
            val syncResult = sendMessage(
                localAddress,
                room,
                sendTimeStamp,
                syncMessageContent,
                message.type == ReceiptMessage.Type.READ,
                Optional.of(message.readPosition.toOutgoingReadPositionEntity()),
                Optional.ofNullable(null),
                null
            )
            L.i { "[Message] sendSyncToSelf result is " + syncResult.isSuccess() }
        } else {
            L.i { "[Message] Skip sending sync message to self" }
        }
    }

    private fun createReceiptContent(message: ReceiptMessage): Content {
        val container = Content.newBuilder()
        return container.setReceiptMessage(message).build()
    }

    




    suspend fun sendMarkAsUnread(
        room: For,
        flag: SyncMessage.MarkAsUnread.Flag,
        sendTimestamp: Long,
        admission: MessagingTicket,
    ): SendMessageResult {
        requireAdmitted(admission, "markAsUnread")
        L.i { "[Message] [$sendTimestamp] Sending markAsUnread sync flag=${flag.name} room=${room.id}" }
        val syncContent = content {
            syncMessage = syncMessage {
                markAsUnread = SyncMessageKt.markAsUnread {
                    conversation = conversationId {
                        when (room) {
                            is For.Group -> groupId = ByteString.copyFrom(room.id.transformGroupIdFromLocalToServer())
                            is For.Account -> number = room.id
                        }
                    }
                    this.flag = flag
                }
            }
        }
        return sendMessage(
            localAddress,
            room,
            sendTimestamp,
            syncContent,
            false,
            Optional.empty(),
            Optional.empty(),
            null
        )
    }

    private fun createMultiDeviceReadContent(readMessages: List<SyncMessage.Read>): Content {
        return content {
            syncMessage = syncMessage {
                this.read.addAll(readMessages)
            }
        }
    }

    private fun intsToByteHigh(highValue: Int, lowValue: Int): Byte {
        return ((highValue shl 4 or lowValue) and 0xFF).toByte()
    }

    suspend fun sendClientNotifyMessage(
        recipient: For,
        room: For,
        message: SignalServiceProtos.NotifyMessage,
        admission: MessagingTicket,
    ): SendMessageResult {
        requireAdmitted(admission, "clientNotify")
        L.i { "[Message] Sending a client notify message." }

        val timestamp = System.currentTimeMillis()
        val content = contentCreator.createFrom(message)

        val result: SendMessageResult = sendMessage(
            recipient,
            room,
            timestamp,
            content,
            false,
            Optional.empty(),
            Optional.empty(),
            null
        )
        L.i { "[Message] sendClientNotifyMessage result is Success: ${result.isSuccess()}" }
        if (result.success != null) {
            val syncMessageContent = createMultiDeviceNotifyContent(content)
            val result1 = sendMessage(
                localAddress,
                room,
                timestamp,
                syncMessageContent,
                false,
                Optional.empty(),
                Optional.empty(),
                null
            )
            L.i { "[Message] sendSyncClientNotifyMessage send sync message is Success: ${result1.isSuccess()}" }
        }
        return result
    }

    











    suspend fun sendForwardNoticeMessage(
        recipient: For,
        room: For,
        message: SignalServiceProtos.ForwardNoticeMessage,
        sendSyncToSelf: Boolean,
        sendTimestamp: Long,
        admission: MessagingTicket,
    ): SendMessageResult {
        requireAdmitted(admission, "forwardNotice")
        L.i { "[Message] [$sendTimestamp] Sending a forward notice message to ${recipient.id} (sync=$sendSyncToSelf)" }
        
        
        val primaryContent = contentCreator.createFrom(message)

        val syncContent: String? = if (sendSyncToSelf && recipient is For.Account) {
            if (conversationManager.hasPublicKeyInfoData(room).not()) {
                L.i { "[Message] sendForwardNoticeMessage: updating public key info for room ${room.id}" }
                conversationManager.updatePublicKeyInfoData(room)
            }
            generateForwardNoticeSyncContent(message, room)
        } else {
            null
        }

        val result = sendMessage(
            recipient,
            room,
            sendTimestamp,
            primaryContent,
            false,
            Optional.empty(),
            Optional.empty(),
            null,
            syncContent
        )
        L.i { "[Message] sendForwardNoticeMessage success=${result.isSuccess()} syncCarried=${syncContent != null}" }
        return result
    }

    





    private suspend fun generateForwardNoticeSyncContent(
        message: SignalServiceProtos.ForwardNoticeMessage,
        room: For
    ): String? {
        return try {
            val syncMessageContent = createMultiDeviceForwardNoticeContent(message)

            val publicKeyInfos = conversationManager.getPublicKeyInfos(room)
            val myPublicKey = publicKeyInfos.firstOrNull { it.uid == localAddress.id }?.identityKey
            if (myPublicKey.isNullOrBlank()) {
                L.w { "[Message] generateForwardNoticeSyncContent: my public key not found in room ${room.id}, skip syncContent" }
                return null
            }

            val encryptedMessage = messageEncryptor.encryptOneToOneMessage(
                syncMessageContent.toByteArray(),
                myPublicKey
            )

            val encryptedMessageContent = encryptContent {
                version = MESSAGE_CURRENT_VERSION
                cipherText = ByteString.copyFrom(encryptedMessage.cipherText)
                eKey = ByteString.copyFrom(encryptedMessage.eKey)
                identityKey = ByteString.copyFrom(encryptedMessage.identityKey)
                signedEKey = ByteString.copyFrom(encryptedMessage.signedEKey)
            }

            Base64.encodeBytes(
                byteArrayOf(
                    intsToByteHigh(MESSAGE_CURRENT_VERSION, MESSAGE_MINIMUM_SUPPORTED_VERSION)
                ) + encryptedMessageContent.toByteArray()
            )
        } catch (e: Exception) {
            L.e { "[Message] generateForwardNoticeSyncContent failed: ${e.stackTraceToString()}" }
            null
        }
    }

    



    internal fun createMultiDeviceForwardNoticeContent(
        message: SignalServiceProtos.ForwardNoticeMessage
    ): Content = content {
        syncMessage = syncMessage {
            forwardNoticeSync = message
        }
    }

    




    suspend fun sendActivityNoticeMessage(
        recipient: For,
        room: For,
        message: SignalServiceProtos.MessageActivityNotice,
        sendSyncToSelf: Boolean,
        sendTimestamp: Long,
        admission: MessagingTicket,
    ): SendMessageResult {
        requireAdmitted(admission, "activityNotice")
        L.i { "[Message] [$sendTimestamp] Sending an activity notice message to ${recipient.id} (sync=$sendSyncToSelf)" }
        val primaryContent = contentCreator.createFrom(message)

        val syncContent: String? = if (sendSyncToSelf && recipient is For.Account) {
            if (conversationManager.hasPublicKeyInfoData(room).not()) {
                L.i { "[Message] sendActivityNoticeMessage: updating public key info for room ${room.id}" }
                conversationManager.updatePublicKeyInfoData(room)
            }
            generateActivityNoticeSyncContent(message, room)
        } else {
            null
        }

        val result = sendMessage(
            recipient,
            room,
            sendTimestamp,
            primaryContent,
            false,
            Optional.empty(),
            Optional.empty(),
            null,
            syncContent
        )
        L.i { "[Message] sendActivityNoticeMessage success=${result.isSuccess()} syncCarried=${syncContent != null}" }
        return result
    }

    




    private suspend fun generateActivityNoticeSyncContent(
        message: SignalServiceProtos.MessageActivityNotice,
        room: For
    ): String? {
        return try {
            val syncMessageContent = createMultiDeviceActivityNoticeContent(message)

            val publicKeyInfos = conversationManager.getPublicKeyInfos(room)
            val myPublicKey = publicKeyInfos.firstOrNull { it.uid == localAddress.id }?.identityKey
            if (myPublicKey.isNullOrBlank()) {
                L.w { "[Message] generateActivityNoticeSyncContent: my public key not found in room ${room.id}, skip syncContent" }
                return null
            }

            val encryptedMessage = messageEncryptor.encryptOneToOneMessage(
                syncMessageContent.toByteArray(),
                myPublicKey
            )

            val encryptedMessageContent = encryptContent {
                version = MESSAGE_CURRENT_VERSION
                cipherText = ByteString.copyFrom(encryptedMessage.cipherText)
                eKey = ByteString.copyFrom(encryptedMessage.eKey)
                identityKey = ByteString.copyFrom(encryptedMessage.identityKey)
                signedEKey = ByteString.copyFrom(encryptedMessage.signedEKey)
            }

            Base64.encodeBytes(
                byteArrayOf(
                    intsToByteHigh(MESSAGE_CURRENT_VERSION, MESSAGE_MINIMUM_SUPPORTED_VERSION)
                ) + encryptedMessageContent.toByteArray()
            )
        } catch (e: Exception) {
            L.e { "[Message] generateActivityNoticeSyncContent failed: ${e.stackTraceToString()}" }
            null
        }
    }

    



    internal fun createMultiDeviceActivityNoticeContent(
        message: SignalServiceProtos.MessageActivityNotice
    ): Content = content {
        syncMessage = syncMessage {
            activityNoticeSync = message
        }
    }
}





private val <T> T.exhaustive: T get() = this