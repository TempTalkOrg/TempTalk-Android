package com.difft.android.websocket.internal.push

data class NewOutgoingPushMessage(
    val type: Int,
    val destination: String,
    val destinationDeviceId: Int,
    val destinationRegistrationId: Int,
    val content: String,
    val legacyContent: String?,
    val readReceipt: Boolean,
    val notification: Notification?,
    val conversation: Conversation?,
    val msgType: Int,
    val detailMessageType: Int,
    val readPositions: List<ReadPositionEntity>?,
    val realSource: RealSourceEntity?,
    val silent: Boolean,
    val timestamp: Long,
    val recipients: List<Recipient>
) {
    data class Recipient(
        val uid: String,
        val registrationId: Int,
        val peerContext: String
    )

    data class Conversation(
        val number: String?,
        val gid: String?
    )

    data class PassThrough(
        val conversationId: String,
        val callInfo: CallInfo
    ) {
        data class CallInfo(
            val caller: String,
            val channelName: String,
            val mode: String,
            val meetingName: String
        )
    }

    data class Args(
        val gid: String? = null,
        val collapseId: String? = null,
        val passthrough: String? = null
    )

    data class Notification(
        val args: Args,
        val type: Int
    )

    data class ReadPositionEntity(
        val groupId: String,
        val readAt: Long,
        val maxServerTime: Long,
        val maxNotifySequenceId: Long,
        val maxSequenceId: Long
    )

    data class RealSourceEntity(
        val timestamp: Long,
        val sourceDevice: Int,
        val source: String,
        val serverTimestamp: Long,
        val sequenceId: Long,
        val notifySequenceId: Long
    )

    data class Builder(
        var type: Int = 0,
        var destination: String = "",
        var destinationDeviceId: Int = 0,
        var destinationRegistrationId: Int = 0,
        var content: String = "",
        var legacyContent: String? = null,
        var readReceipt: Boolean = false,
        var notification: Notification? = null,
        var conversation: Conversation? = null,
        var msgType: Int = 0,
        var detailMessageType: Int = 0,
        var readPositions: List<ReadPositionEntity>? = null,
        var realSource: RealSourceEntity? = null,
        var silent: Boolean = false,
        var timestamp: Long = 0L,
        var recipients: List<Recipient> = listOf()
    ) {
        fun type(type: Int) = apply { this.type = type }
        fun destination(destination: String) = apply { this.destination = destination }
        fun destinationDeviceId(destinationDeviceId: Int) =
            apply { this.destinationDeviceId = destinationDeviceId }

        fun destinationRegistrationId(destinationRegistrationId: Int) =
            apply { this.destinationRegistrationId = destinationRegistrationId }

        fun content(content: String) = apply { this.content = content }
        fun legacyContent(legacyContent: String?) = apply { this.legacyContent = legacyContent }
        fun readReceipt(readReceipt: Boolean) = apply { this.readReceipt = readReceipt }
        fun notification(notification: Notification?) = apply { this.notification = notification }
        fun conversation(conversation: Conversation) = apply { this.conversation = conversation }
        fun msgType(msgType: Int) = apply { this.msgType = msgType }
        fun detailMessageType(detailMessageType: Int) =
            apply { this.detailMessageType = detailMessageType }

        fun readPositions(readPositions: List<ReadPositionEntity>?) =
            apply { this.readPositions = readPositions }

        fun realSource(realSource: RealSourceEntity?) = apply { this.realSource = realSource }
        fun silent(silent: Boolean) = apply { this.silent = silent }
        fun timestamp(timestamp: Long) = apply { this.timestamp = timestamp }

        fun recipients(recipients: List<Recipient>) = apply { this.recipients = recipients }

        fun build() = NewOutgoingPushMessage(
            type,
            destination,
            destinationDeviceId,
            destinationRegistrationId,
            content,
            legacyContent,
            readReceipt,
            notification,
            conversation,
            msgType,
            detailMessageType,
            readPositions,
            realSource,
            silent,
            timestamp,
            recipients
        )
    }
}