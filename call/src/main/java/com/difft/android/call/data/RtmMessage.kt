package com.difft.android.call.data

import io.livekit.android.room.participant.Participant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer


const val RTM_MESSAGE_TOPIC_CHAT = "chat"
const val RTM_MESSAGE_TOPIC_MUTE = "mute-other"
const val RTM_MESSAGE_TOPIC_RESUME_CALL = "continue-call-after-silence"
const val RTM_MESSAGE_TOPIC_SET_COUNTDOWN = "set-countdown"
const val RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN = "restart-countdown"
const val RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN = "extend-countdown"
const val RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN = "clear-countdown"
const val RTM_MESSAGE_TOPIC_END_CALL = "end-call"
/** Server → Client plaintext meeting-ended sync (RTM protocol §4.1). */
const val RTM_MESSAGE_TOPIC_SERVER_END_CALL = "server-end-call"
const val RTM_MESSAGE_KEY_TEXT = "text"
const val RTM_MESSAGE_KEY_TOPIC = "topic"
const val RTM_MESSAGE_KEY_TYPE = "type"
const val RTM_MESSAGE_KEY_IDENTITIES = "identities"
const val RTM_MESSAGE_KEY_SENDTIMESTAMP = "sendTimestamp"
const val RTM_MESSAGE_TYPE_DEFAULT = 0
const val RTM_MESSAGE_TYPE_BUBBLE = 1

enum class BubbleMessageType {
    EMOJI,
    TEXT
}


const val MUTE_ACTION_INDEX = 0


@Serializable
data class RtmMessage(
    val topic: String? = null,
    @Serializable(with = IdentityListSerializer::class)
    val identities: List<Participant.Identity>? = null,
    val sendTimestamp: Long = 0L,
    val type: Int? = 0,
    val text: String? = null
)


object IdentityListSerializer : JsonTransformingSerializer<List<Participant.Identity>>(
    ListSerializer(Participant.Identity.serializer())
) {
    // If response is not an array, then it is a single object that should be wrapped into the array
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element !is JsonArray) JsonArray(listOf(element)) else element
}

@Serializable
data class RtmDataPacket(
    val payload: String,
    val signature: String,
    val sendTimestamp: Long,
    val uuid: String
)

@Serializable
data class CountDownTimerData(
    val currentTimeMs: Long,
    val durationMs: Long,
    val expiredTimeMs: Long,
    val operatorIdentity: String,
)

@Serializable
data class EndCallRtmMessage(
    val topic: String? = null,
    val sendTimestamp: Long,
)

/** Inner jsonData for the plaintext `server-end-call` topic (RTM protocol §4.1). */
@Serializable
data class ServerEndCallRtmMessage(
    val topic: String? = null,
    val sendTimestamp: Long,
)