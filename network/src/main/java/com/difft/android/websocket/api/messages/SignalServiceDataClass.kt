package com.difft.android.websocket.api.messages

import android.text.TextUtils
import com.difft.android.base.utils.globalServices
import com.difft.android.websocket.api.messages.TTNotifyMessage.Companion.NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING
import com.difft.android.websocket.api.messages.TTNotifyMessage.Companion.NOTIFY_MESSAGE_TYPE_CONVERSATION_SHARE_SETTING
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
                val notifyConversation = Gson().fromJson(toString(), NotifyConversation::class.java)
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

    val extraLatestCard: SignalServiceProtos.Card? by lazy {
        if (signalServiceEnvelope.hasMsgExtra() && signalServiceEnvelope.msgExtra.hasLatestCard()) {
            signalServiceEnvelope.msgExtra.latestCard
        } else null
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
            } else {
                throw IllegalArgumentException("syncMessage doesn't have sent or read or topicMark or topicAction")
            }
        } else if (signalCustomNotifyMessage != null) { //Server Notify Message
            if (signalServiceEnvelope.hasMsgExtra() && signalServiceEnvelope.msgExtra.hasConversationId()) {
                if (signalServiceEnvelope.msgExtra.conversationId.hasGroupId()) {
                    val groupIdMsgExtra = signalServiceEnvelope.msgExtra.conversationId.groupId.toByteArray()
                    if (groupIdMsgExtra.size != 32 && groupIdMsgExtra.size != 36) {
                        val hex = Hex.toStringCondensed(groupIdMsgExtra)
                        val string = String(groupIdMsgExtra)
                        FirebaseCrashlytics.getInstance().recordException(
                            IllegalArgumentException(
                                "Invalid group id length: ${groupIdMsgExtra.size}, groupId: ${signalServiceEnvelope.msgExtra.conversationId.groupId} groupIdInData:${signalCustomNotifyMessage.data?.gid} timestamp:${signalServiceEnvelope.timestamp} groupNotifyDetailedType:${signalCustomNotifyMessage.data?.groupNotifyDetailedType} hex:$hex string:$string"
                            )
                        )
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
        } else {
            throw IllegalArgumentException("Unknown message type msg typ: ${signalServiceEnvelope.msgType} content: $signalServiceContent")
        }
    }
}