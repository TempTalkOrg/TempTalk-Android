package com.difft.android.websocket.api.messages

import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.websocket.api.messages.TTNotifyMessage.Companion.NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING
import com.difft.android.websocket.api.messages.TTNotifyMessage.Companion.NOTIFY_MESSAGE_TYPE_CONVERSATION_SHARE_SETTING
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import com.google.gson.Gson
import difft.android.messageserialization.For
import difft.android.messageserialization.model.MessageId
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.wcdb
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import util.Hex

/**
 * Some special server to me notify message need shown inside one to one conversation or group conversation
 * Here do the special cases handle
 */
private fun TTNotifyMessage.specialOneToOneConversation(myId: String): For {
    val groupDetailType = data?.groupNotifyDetailedType ?: -1
    val groupId: String? = when (groupDetailType) {
        GroupNotifyDetailType.LeaveGroup.value -> {
            data?.gid
        }

        GroupNotifyDetailType.KickoutGroup.value -> {
            data?.gid
        }

        GroupNotifyDetailType.GroupSelfInfoChange.value -> {
            data?.gid
        }

        GroupNotifyDetailType.KickoutAutoClear.value -> {
            data?.gid
        }

        else -> null
    }
    if (groupId != null) return For.Group(groupId)

    val oneToOneConversationId = when (notifyType) {
        NOTIFY_MESSAGE_TYPE_CONVERSATION_SHARE_SETTING -> {
            data?.conversation?.asString?.replace(myId, "")?.replace(":", "")
                ?: throw IllegalArgumentException("conversation is null when notifyType is NOTIFY_MESSAGE_TYPE_CONVERSATION_SHARE_SETTING")
        }

        NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING -> {
            data?.conversation?.runCatching {
                val notifyConversation = globalServices.gson.fromJson(toString(), NotifyConversation::class.java)
                notifyConversation.conversation
            }?.getOrNull()
                ?: throw IllegalArgumentException("conversation is null when notifyType is NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING")
        }

        else -> {
            null
        }
    }
    return if (oneToOneConversationId.isNullOrEmpty()) {
        For.Account("server")
    } else if (oneToOneConversationId.startsWith("+")) {
        For.Account(oneToOneConversationId)
    } else {
        For.Group(oneToOneConversationId)
    }
}

private fun TTNotifyMessage.specialGroupId(): String {
    val groupDetailType = data?.groupNotifyDetailedType ?: -1
    val groupId: String? = when (groupDetailType) {
        GroupNotifyDetailType.LeaveGroup.value -> {
            data?.gid
        }

        GroupNotifyDetailType.KickoutGroup.value -> {
            data?.gid
        }

        GroupNotifyDetailType.GroupSelfInfoChange.value -> {
            data?.gid
        }

        GroupNotifyDetailType.KickoutAutoClear.value -> {
            data?.gid
        }

        else -> null
    }
    return groupId ?: "server"
}

/**
 * Data class for store all deserialized message data(proto and json)
 * Created by King.W on 2024.07.01
 * todo king.w check here why it can't be data class, if data class it will compile error with load model error tip
 */
class SignalServiceDataClass(
    val signalServiceEnvelope: SignalServiceProtos.Envelope,
    val signalServiceContent: SignalServiceProtos.Content?,
    val signalCustomNotifyMessage: TTNotifyMessage?
) {
    // create component1() to component3 for this class
    operator fun component1() = signalServiceEnvelope
    operator fun component2() = signalServiceContent
    operator fun component3() = signalCustomNotifyMessage


    val sequenceId: Long by lazy {
        if (signalCustomNotifyMessage != null &&
            (conversation is For.Account || (conversation is For.Group && signalCustomNotifyMessage.data?.groupNotifyDetailedType in arrayOf(
                GroupNotifyDetailType.LeaveGroup.value,
                GroupNotifyDetailType.KickoutGroup.value,
                GroupNotifyDetailType.GroupSelfInfoChange.value,
                GroupNotifyDetailType.KickoutAutoClear.value
            )))
        ) { //Server one to one and special group Notify Message
            -1 // return -1 as sequenceId means this message don't take part in hot data logic
        } else if (signalServiceContent?.hasSyncMessage() == true
            && signalServiceContent.syncMessage?.hasSent() == true
            && signalServiceContent.syncMessage.sent.hasSequenceId()
            && signalServiceContent.syncMessage.sent.sequenceId != 0L
        ) {
            signalServiceContent.syncMessage.sent.sequenceId
        } else signalServiceEnvelope.sequenceId
    }

    val extraReactionInfos: List<SignalServiceProtos.MsgExtra.ReactionInfo>? by lazy {
        if (signalServiceEnvelope.hasMsgExtra() && signalServiceEnvelope.msgExtra.reactionInfosCount > 0) {
            signalServiceEnvelope.msgExtra.reactionInfosList
        } else null
    }

    val myId: String by lazy {
        globalServices.myId
    }

    val senderId: String by lazy {
        signalServiceEnvelope.source.takeIf { signalServiceEnvelope.hasSource() } ?: throw IllegalArgumentException("source is null")
    }

    val messageId: String by lazy {
        MessageId(
            senderId,
            signalServiceEnvelope.timestamp,
            signalServiceEnvelope.sourceDevice
        ).idValue
    }
    val shouldShowNotification: Boolean by lazy {
        // Skip notification for self-sent messages (v4 API: group messages returned as normal messages, not sync)
        if (senderId == myId) return@lazy false
        (signalServiceContent?.hasDataMessage() == true && signalServiceContent.dataMessage.run { !hasReaction() })
    }

    val conversation: For by lazy {
        if (signalServiceContent?.hasDataMessage() == true) {
            if (signalServiceContent.dataMessage.hasGroup() && signalServiceContent.dataMessage.group.hasId()) {
                For.Group(signalServiceContent.dataMessage.group.id.toByteArray().transformGroupIdFromServerToLocal())
            } else if (signalServiceEnvelope.hasMsgType() && signalServiceEnvelope.msgType.number == SignalServiceProtos.Envelope.MsgType.MSG_SCHEDULE_NORMAL_VALUE) {
                if (senderId == myId) {
                    if (signalServiceEnvelope.hasMsgExtra() && signalServiceEnvelope.msgExtra.hasConversationId() && signalServiceEnvelope.msgExtra.conversationId.hasNumber()) {
                        For.Account(signalServiceEnvelope.msgExtra.conversationId.number)
                    } else {
                        throw IllegalArgumentException("msgExtra's conversationId is null when it's a scheduled message")
                    }
                } else {
                    For.Account(senderId)
                }
            } else {
                For.Account(senderId)
            }
        } else if (signalServiceContent?.hasSyncMessage() == true) {
            if (signalServiceContent.syncMessage.hasSent() && signalServiceContent.syncMessage.sent.hasMessage()) {
                if (signalServiceContent.syncMessage.sent.message.hasGroup()) {
                    For.Group(signalServiceContent.syncMessage.sent.message.group.id.toByteArray().transformGroupIdFromServerToLocal())
                } else if (signalServiceContent.syncMessage.sent.hasDestination()) {
                    For.Account(signalServiceContent.syncMessage.sent.destination)
                } else if (senderId == myId) {
                    For.Account(senderId) // for Note
                } else {
                    throw IllegalArgumentException("syncMessage's sent doesn't have message or destination or the senderId is not me")
                }
            } else if (signalServiceContent.syncMessage.readCount > 0) {
                val readMessage = signalServiceContent.syncMessage.readList[0]
                if (readMessage.readPosition.hasGroupId() && readMessage.readPosition.groupId.isEmpty.not()) {
                    For.Group(String(readMessage.readPosition.groupId.toByteArray()))
                } else if (!TextUtils.isEmpty(readMessage.sender)) {
                    For.Account(readMessage.sender)
                } else {
                    throw IllegalArgumentException("readMessage's readPosition doesn't have groupId or sender")
                }
            } else if (signalServiceContent.syncMessage.hasForwardNoticeSync()) {
                // Self-sync: Content.syncMessage.forwardNoticeSync is a ForwardNoticeMessage.
                // Resolve conversation from payload.conversation (filled by sender from its
                // own view: peer uid for 1v1, myId for NTS source).
                //
                // The groupId arm below is defense-in-depth only — our sender never emits
                // sync for group source (see PushForwardNoticeSendJob.sendSyncToSelf and
                // NewSignalServiceMessageSender's For.Group guard). Kept so a future change
                // that enables group sync doesn't silently route to the wrong conversation.
                val syncMsg = signalServiceContent.syncMessage.forwardNoticeSync
                val payloadConv = syncMsg.takeIf { it.hasConversation() }?.conversation
                when {
                    payloadConv?.hasGroupId() == true ->
                        payloadConv.parseToFor(signalServiceEnvelope.timestamp)
                            ?: For.Account(senderId)
                    payloadConv?.hasNumber() == true && payloadConv.number.isNotEmpty() ->
                        For.Account(payloadConv.number)
                    else -> For.Account(senderId) // defensive: NTS fallback
                }
            } else if (signalServiceContent.syncMessage.hasActivityNoticeSync()) {
                // Self-sync mirror of activityNoticeSync — same resolution rules as
                // forwardNoticeSync above. Activity notice covers COPY (this iteration)
                // and future types (PASTE/SCREENSHOT/...). Payload.conversation carries
                // the source conversation filled by the sender's own view.
                val syncMsg = signalServiceContent.syncMessage.activityNoticeSync
                val payloadConv = syncMsg.takeIf { it.hasConversation() }?.conversation
                when {
                    payloadConv?.hasGroupId() == true ->
                        payloadConv.parseToFor(signalServiceEnvelope.timestamp)
                            ?: For.Account(senderId)
                    payloadConv?.hasNumber() == true && payloadConv.number.isNotEmpty() ->
                        For.Account(payloadConv.number)
                    else -> For.Account(senderId) // defensive: NTS fallback
                }
            } else {
                throw IllegalArgumentException("syncMessage doesn't have sent or read or topicMark or topicAction")
            }
        } else if (signalCustomNotifyMessage != null) { //Server Notify Message
            if (signalServiceEnvelope.hasMsgExtra() && signalServiceEnvelope.msgExtra.hasConversationId()) {
                if (signalServiceEnvelope.msgExtra.conversationId.hasGroupId()) {
                    val groupIdMsgExtra = signalServiceEnvelope.msgExtra.conversationId.groupId.toByteArray()
                    if (groupIdMsgExtra.size != 16 && groupIdMsgExtra.size != 32 && groupIdMsgExtra.size != 36) {
                        val hex = Hex.toStringCondensed(groupIdMsgExtra)
                        val string = String(groupIdMsgExtra)
                        L.e { "[Message] Invalid group id length: ${groupIdMsgExtra.size}, groupId: ${signalServiceEnvelope.msgExtra.conversationId.groupId} groupIdInData:${signalCustomNotifyMessage.data?.gid} timestamp:${signalServiceEnvelope.timestamp} groupNotifyDetailedType:${signalCustomNotifyMessage.data?.groupNotifyDetailedType} hex:$hex string:$string" }
                    }
                    For.Group(groupIdMsgExtra.transformGroupIdFromServerToLocal())
                } else if (signalServiceEnvelope.msgExtra.conversationId.hasNumber()) {
                    if (signalServiceEnvelope.msgExtra.conversationId.number == "server") {
                        signalCustomNotifyMessage.specialOneToOneConversation(myId)
                    } else For.Account(signalServiceEnvelope.msgExtra.conversationId.number)
                } else {
                    throw IllegalArgumentException("msgExtra's conversationId don't have number or groupId when it's a custom notify message")
                }
            } else {
                throw IllegalArgumentException("msgExtra's conversationId is null when it's a custom notify message")
            }
        } else if (signalServiceContent?.hasReceiptMessage() == true) {
            val groupId = if (signalServiceContent.receiptMessage.hasReadPosition()
                && signalServiceContent.receiptMessage.readPosition.hasGroupId()
            ) {
                signalServiceContent.receiptMessage.readPosition.groupId.toByteArray().transformGroupIdFromServerToLocal()
            } else if (signalServiceContent.receiptMessage.timestampCount > 0) {
                wcdb.message.getFirstObject(DBMessageModel.timeStamp.eq(signalServiceContent.receiptMessage.timestampList.first()))?.takeIf { it.roomType == 1 }?.roomId
            } else null
            groupId?.let { For.Group(it) } ?: For.Account(senderId)
        } else if (signalServiceContent?.hasCallMessage() == true) {
            signalServiceContent.callMessage?.calling?.conversationId?.number.let {
                if (!it.isNullOrEmpty()) {
                    For.Account(it)
                } else {
                    throw IllegalArgumentException("conversationId number is null: ${signalServiceEnvelope.msgType} content: $signalServiceContent")
                }
            }
        } else if (signalServiceContent?.hasForwardNotice() == true) {
            // Primary path: Content.forwardNotice from peer (or from self for NTS source).
            // Self-sync goes through SyncMessage.forwardNoticeSync — NOT this branch.
            //
            // Rule:
            //   1) payload.conversation.groupId → For.Group(groupId) — fixes group bug
            //      (v1 relied on envelope.msgExtra.conversationId, which is custom-notify-specific).
            //   2) else                         → For.Account(senderId) — envelope.source IS the peer
            //                                     (for 1v1 primary; for NTS source this also lands
            //                                      correctly since senderId==myId).
            val payloadConv = signalServiceContent.forwardNotice
                .takeIf { it.hasConversation() }
                ?.conversation
            if (payloadConv?.hasGroupId() == true) {
                payloadConv.parseToFor(signalServiceEnvelope.timestamp)
                    ?: For.Account(senderId)
            } else {
                For.Account(senderId)
            }
        } else if (signalServiceContent?.hasActivityNotice() == true) {
            // Primary path: Content.activityNotice from peer (or from self for NTS source).
            // Same resolution rules as forwardNotice — top-level `conversation` field
            // is filled by sender's own view; 1v1 falls back to envelope.source.
            // Self-sync goes through SyncMessage.activityNoticeSync — NOT this branch.
            val payloadConv = signalServiceContent.activityNotice
                .takeIf { it.hasConversation() }
                ?.conversation
            if (payloadConv?.hasGroupId() == true) {
                payloadConv.parseToFor(signalServiceEnvelope.timestamp)
                    ?: For.Account(senderId)
            } else {
                For.Account(senderId)
            }
        } else {
            throw IllegalArgumentException("Unknown message type msg typ: ${signalServiceEnvelope.msgType} content: $signalServiceContent")
        }
    }

    /**
     * Server-stamped conversation from Envelope.msgExtra.conversationId, normalized to the
     * same For representation as [conversation]. null when the server has not stamped it
     * (old/transitional server) -> caller treats as absent (fail-open). Never throws.
     * Public accessor for the receive-path cross-check in :chat.
     */
    val envelopeConversation: For? by lazy {
        extractEnvelopeConversation(signalServiceEnvelope)
    }
}

/**
 * Private helper — `MsgExtra.ConversationId` → [For].
 *
 * Routes group ids through [transformGroupIdFromServerToLocal]. Accepts 16-byte
 * (legacy WEEK), 32-byte, and 36-byte group ids as valid; logs a warning (no
 * Crashlytics) if the byte length is anything else.
 *
 * Returns `null` when neither `groupId` nor `number` is set — callers decide
 * the fallback (typically `For.Account(senderId)`).
 */
private fun SignalServiceProtos.ConversationId.parseToFor(
    timestampForLog: Long
): For? {
    if (hasGroupId()) {
        val bytes = groupId.toByteArray()
        if (bytes.size != 16 && bytes.size != 32 && bytes.size != 36) {
            L.w { "[ForwardNotice] Invalid group id length: ${bytes.size}, timestamp=$timestampForLog" }
        }
        return For.Group(bytes.transformGroupIdFromServerToLocal())
    }
    if (hasNumber() && number.isNotEmpty()) return For.Account(number)
    return null
}

/**
 * Envelope-side conversation for the cross-check. Reuses [parseToFor] so envelope-side
 * group ids reduce to the identical String the content side produces (both go through
 * transformGroupIdFromServerToLocal).
 *
 * Returns null when conversationId is absent or present-but-empty — both cases are
 * "absent" (fail-open). Never throws.
 */
internal fun extractEnvelopeConversation(envelope: SignalServiceProtos.Envelope): For? {
    if (!envelope.hasMsgExtra() || !envelope.msgExtra.hasConversationId()) return null
    return envelope.msgExtra.conversationId.parseToFor(envelope.timestamp)
}