package com.difft.android.websocket.api

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.websocket.api.messages.DetailMessageType
import com.difft.android.websocket.api.messages.PublicKeyInfo
import com.difft.android.websocket.api.messages.SendMessageResult
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.UnregisteredUserException
import com.difft.android.websocket.api.services.NewMessagingService
import com.difft.android.websocket.internal.ServiceResponseProcessor
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_CURRENT_VERSION
import com.difft.android.websocket.api.util.INewMessageContentEncryptor.Companion.MESSAGE_MINIMUM_SUPPORTED_VERSION
import com.difft.android.websocket.api.util.RustEncryptionException
import com.difft.android.websocket.api.util.SignalServiceContentCreator
import com.difft.android.websocket.api.util.toOutgoingReadPositionEntity
import com.difft.android.websocket.api.websocket.WebSocketUnavailableException
import com.difft.android.websocket.internal.configuration.ServiceConfig
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import com.difft.android.websocket.internal.push.PushServiceSocket
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
import org.whispersystems.signalservice.internal.push.encryptContent
import org.whispersystems.signalservice.internal.push.syncMessage
import java.io.IOException
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NewSignalServiceMessageSender @Inject constructor(
    serviceConfig: ServiceConfig,
    private val messagingService: NewMessagingService,
    @param:Named("message_sender_max_envelope_size")
    private val maxEnvelopeSize: Long,
    private val messageEncryptor: INewMessageContentEncryptor,
    private val conversationManager: ConversationManager,
) {
    companion object {
        private const val TAG = "SignalServiceMessageSender"
        private const val RETRY_COUNT = 3
    }

    private val socket = PushServiceSocket(serviceConfig, true)
    private val localAddress: For = For.Account(globalServices.myId)
    private val contentCreator: SignalServiceContentCreator = SignalServiceContentCreator(maxEnvelopeSize)

    /**
     * Send a message to a single recipient.
     * group 消息目前(服务端)不支持端上加密，所以发送群消息时 isSecureText 应设置为 false
     *
     * @param recipient    The message's destination.
     * @param message      The message.
     * @param isSecureText 是否使用端上加密
     * @throws org.signal.libsignal.protocol.UntrustedIdentityException
     * @throws IOException
     */
    fun sendDataMessage(
        recipient: For,
        room: For,
        message: SignalServiceProtos.DataMessage,
        notification: NewOutgoingPushMessage.Notification?,
    ): SendMessageResult {
        L.i { "[Message] [${message.timestamp}] Sending a data message." }

        val timestamp = message.timestamp
        val content = contentCreator.createFrom(message)

        // 1v1 messages (including memo): generate syncContent encrypted with own public key for server to distribute to other devices
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
        // v4 API: server handles sync for both 1v1 and group messages
        return result
    }

    /**
     * Generate syncContent: encrypt sync message content with own public key.
     * This content is sent with v4 API, server distributes it to other devices.
     */
    private fun generateSyncContent(
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

            // Get own public key from room's public key list (1v1 room contains [peer, self] keys)
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

    private fun sendMessage(
        recipient: For,
        room: For,
        timestamp: Long,
        content: Content,
        readReceipt: Boolean,
        readPositionEntity: Optional<NewOutgoingPushMessage.ReadPositionEntity>,
        realSourceEntity: Optional<NewOutgoingPushMessage.RealSourceEntity>,
        notification: NewOutgoingPushMessage.Notification?,
        syncContent: String? = null,  // 1v1消息的同步内容（v4接口使用）
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
                    syncContent,  // 传递 syncContent
                )
                try {
                    val response = if (recipient is For.Group) {
                        ServiceResponseProcessor.DefaultProcessor(
                            messagingService.sendToGroup(
                                newOutgoingMessage,
                                recipient.id,
                            ).blockingGet()
                        ).resultOrThrow
                    } else {
                        ServiceResponseProcessor.DefaultProcessor(
                            messagingService.send(
                                newOutgoingMessage,
                                recipient.id,
                            ).blockingGet()
                        ).resultOrThrow
                    }
                    if (response.status == 11001 || response.data.missing.isNullOrEmpty().not() || response.data.stale.isNullOrEmpty().not()) {
                        //Here can also in group message case
                        val error = "Invalid session for ${if (room is For.Group) "Group" else ""} ${recipient.id} "
                        L.w { error }
                        conversationManager.updateConversationMemberData(room)
                        conversationManager.updatePublicKeyInfoData(room)
                        continue // Retry with updated key info
                    } else {
                        return SendMessageResult.success(
                            recipient.id,
                            response.data.isNeedsSync,
                            System.currentTimeMillis() - startTime,
                            response.data.systemShowTimestamp,
                            response.data.notifySequenceId,
                            response.data.sequenceId
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
                    L.e { "[Message] [sendMessage][$timestamp] Pipe unavailable, falling back... (${e.javaClass.simpleName}: ${e.stackTraceToString()})" }
                } catch (e: IOException) {
                    L.e { "[Message][$timestamp] Pipe failed, falling back... (${e.javaClass.simpleName}: ${e.stackTraceToString()})" }
                }
                val response = socket.sendMessageNew(newOutgoingMessage, recipient)
                if (response.status == 11001 || response.data.missing.isNullOrEmpty().not() || response.data.stale.isNullOrEmpty().not()) {
                    //Here can also in group message case
                    val error = "Invalid session for ${if (room is For.Group) "Group" else ""} ${recipient.id} "
                    L.w { error }
                    conversationManager.updateConversationMemberData(room)
                    conversationManager.updatePublicKeyInfoData(room)
                    continue // Retry with updated key info
                } else {
                    return SendMessageResult.success(
                        recipient.id,
                        response.data.isNeedsSync,
                        System.currentTimeMillis() - startTime,
                        response.data.systemShowTimestamp,
                        response.data.notifySequenceId,
                        response.data.sequenceId
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

                    // Force update public key data
                    conversationManager.updateConversationMemberData(room)
                    conversationManager.updatePublicKeyInfoData(room)
                } else {
                    L.e { "[Message] [sendMessage][$timestamp] Rust encryption exception. (${errorMessage})" }
                }
            }
        }

        throw IOException("Failed to resolve conflicts after $RETRY_COUNT attempts!")
    }

    private fun createNewOutgoingPushMessage(
        recipient: For,
        room: For,
        content: Content,
        notification: NewOutgoingPushMessage.Notification?,
        readReceipt: Boolean,
        readPositionEntity: NewOutgoingPushMessage.ReadPositionEntity?,
        realSourceEntity: NewOutgoingPushMessage.RealSourceEntity?,
        syncContent: String? = null,  // 1v1消息的同步内容（v4接口使用）
    ): NewOutgoingPushMessage {
        if (conversationManager.hasPublicKeyInfoData(room).not()) {
            if (!conversationManager.updatePublicKeyInfoData(room)) {
                throw IOException("Failed to update public key info data")
            }
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
            val error = "No valid public key info available after filtering (all identityKeys were empty)"
            L.e { error }
            throw IOException(error)
        }

        val msgType = getMsgType(content)

        /**
         * fun getDetailMessageType(): OWSDetailMessageType {
         *     return when {
         *         this.combinedForwardingMessage != null && this.combinedForwardingMessage.subForwardingMessages != null && this.combinedForwardingMessage.subForwardingMessages.size >= 1 -> {
         *             OWSDetailMessageType.Forward
         *         }
         *         this.isContactShareMessage() -> {
         *             OWSDetailMessageType.Contact
         *         }
         *         this.isTaskMessage() -> {
         *             OWSDetailMessageType.Task
         *         }
         *         this.isRecalMessage() -> {
         *             OWSDetailMessageType.Recall
         *         }
         *         this.isVoteMessgae() -> {
         *             OWSDetailMessageType.Vote
         *         }
         *         this.isReactionMessage() -> {
         *             OWSDetailMessageType.Reaction
         *         }
         *         this.card != null -> {
         *             OWSDetailMessageType.Card
         *         }
         *         this.messageModeType == TSMessageModeType.Confidential -> {
         *             OWSDetailMessageType.Confidential
         *         }
         *         else -> {
         *             OWSDetailMessageType.Unknow
         *         }
         *     }
         * }
         */
        val detailMessageType: DetailMessageType = if (content.hasDataMessage()) {
            if (content.dataMessage.hasForwardContext()) DetailMessageType.Forward
            else if (content.dataMessage.contactCount > 0) DetailMessageType.Contact
            else if (content.dataMessage.hasRecall()) DetailMessageType.Recall
            else if (content.dataMessage.hasReaction()) DetailMessageType.Reaction
            else if (content.dataMessage.hasCard()) DetailMessageType.Card
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

        val timestamp = if (content.hasDataMessage()) content.dataMessage.timestamp else System.currentTimeMillis()
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
            .syncContent(syncContent)  // 1v1消息的同步内容（v4接口使用）
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
        } else if (content.hasNotifyMessage()) {
            msgType = Envelope.MsgType.MSG_CLIENT_NOTIFY_VALUE
        }
        return msgType
    }

    /**
     * Send a read receipt for a received message.
     *
     * @param recipient The sender of the received message you're acknowledging.
     * @param message   The read receipt to deliver.
     * @param group if the receipt is for a group message. then this value is not null, then used in later flow
     * @param sendReceiptToSender Whether to send read receipt to the message sender
     * @param sendSyncToSelf Whether to send sync message to self's other devices
     */
    fun sendReceipt(
        recipient: For,
        room: For,
        message: ReceiptMessage,
        readMessages: List<SyncMessage.Read>,
        sendReceiptToSender: Boolean = true,
        sendSyncToSelf: Boolean = true
    ) {
        val sendTimeStamp = System.currentTimeMillis()

        // 1. 发送已读回执给对方（如果需要）
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

        // 2. 发送同步消息给自己的其他设备（如果需要）
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

    fun sendClientNotifyMessage(
        recipient: For,
        room: For,
        message: SignalServiceProtos.NotifyMessage
    ): SendMessageResult {
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
}