package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class OutgoingPushMessage(
    @JsonProperty val type: Int,
    @JsonProperty val destination: String,
    @JsonProperty val destinationDeviceId: Int,
    @JsonProperty val destinationRegistrationId: Int,
    @JsonProperty val content: String,
    @JsonProperty val readReceipt: Boolean,
    @JsonProperty val notification: Notification?,
    @JsonProperty val conversation: Conversation?,
    @JsonProperty val msgType: Int,
    @JsonProperty val detailMessageType: Int,
    @JsonProperty val readPositions: List<ReadPositionEntity>? = emptyList(),
    @JsonProperty val realSource: RealSourceEntity?,
    @JsonProperty val reactionInfo: ReactionInfo?
) {
    data class Conversation(
        @JsonProperty val number: String? = null,
        @JsonProperty("gid") val groupId: String? = null
    ) {
        companion object {
            fun ofGroup(groupId: String): Conversation {
                require(groupId.isNotEmpty()) { "Group ID cannot be null or empty" }
                return Conversation(groupId = groupId)
            }
        }
    }

    data class PassThrough(
        @JsonProperty val conversationId: String,
        @JsonProperty val callInfo: CallInfo? = null
    ) {
        constructor(conversationId: String, caller: String, channelName: String, mode: String) : this(
            conversationId,
            CallInfo(caller, channelName, mode)
        )

        fun setMeetingName(name: String) {
            callInfo?.meetingName = name
        }

        fun setIsLiveStream(isLiveStream: Boolean?) {
            callInfo?.isLiveStream = isLiveStream
        }

        fun setEid(eid: String?) {
            callInfo?.eid = eid
        }
    }

    data class CallInfo(
        @JsonProperty val caller: String,
        @JsonProperty val channelName: String,
        @JsonProperty val mode: String,
        @JsonProperty var meetingName: String? = null,
        @JsonProperty val startAt: Long = Instant.now().epochSecond,
        @JsonProperty var isLiveStream: Boolean? = false,
        @JsonProperty var eid: String? = null
    )

    data class Args(
        @JsonProperty val gid: String? = null,
        @JsonProperty val collapseId: String? = null,
        @JsonProperty val passthrough: String? = null,
        @JsonProperty val mentionedPersons: Array<String>? = null
    ) {
        fun toNewArgs() = NewOutgoingPushMessage.Args(gid, collapseId, passthrough)
    }

    data class Notification(
        @JsonProperty val args: Args,
        @JsonProperty val type: Int
    ) {
        fun toNewNotification() = NewOutgoingPushMessage.Notification(args.toNewArgs(), type)
    }

    enum class DetailMessageType(val value: Int) {
        FORWARD(1),
        CONTACT(2),
        RECALL(3),
        TASK(4),
        VOTE(5),
        REACTION(6),
        CARD(7),
        UN_KNOW(999)
    }

    data class ReadPositionEntity(
        @JsonProperty val groupId: String?,
        @JsonProperty val readAt: Long,
        @JsonProperty val maxServerTime: Long,
        @JsonProperty val maxNotifySequenceId: Long,
        @JsonProperty val maxSequenceId: Long
    )

    data class RealSourceEntity(
        @JsonProperty val timestamp: Long,
        @JsonProperty val sourceDevice: Int,
        @JsonProperty val source: String,
        @JsonProperty val serverTimestamp: Long,
        @JsonProperty val sequenceId: Long,
        @JsonProperty val notifySequenceId: Long
    )
    /**
     * "reactionInfo":{
     * "content":"xxx",//reaction消息内容
     * "remove":false,//是否remove
     * "originTimestamp":111231231231 //为remove时，reaction原始消息(被移除的那个表情)的时间戳
     * }
     *
     */
    data class ReactionInfo(
        @JsonProperty val content: String,
        @JsonProperty val remove: Boolean,
        @JsonProperty val originTimestamp: Long
    )
}