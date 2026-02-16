package com.difft.android.call.handler

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.CancelHandRtmMessage
import com.difft.android.call.data.CountDownTimerData
import com.difft.android.call.data.EndCallRtmMessage
import com.difft.android.call.data.HandsUpData
import com.difft.android.call.data.RTM_MESSAGE_KEY_SENDTIMESTAMP
import com.difft.android.call.data.RTM_MESSAGE_KEY_TEXT
import com.difft.android.call.data.RTM_MESSAGE_KEY_TOPIC
import com.difft.android.call.data.RTM_MESSAGE_KEY_TYPE
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CHAT
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_END_CALL
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_MUTE
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RAISE_HANDS_UP
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESUME_CALL
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SET_COUNTDOWN
import com.difft.android.call.data.RaiseHandRtmMessage
import com.difft.android.call.data.RtmDataPacket
import com.difft.android.call.data.RtmMessage
import com.google.gson.Gson
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.UUID

class RtmMessageHandler(
    private val room: Room,
    private val scope: CoroutineScope,
    private val encryptor: (plain: ByteArray, timestamp: Long) -> String?,
    private val decryptor: (participant: Participant, cipher: ByteArray) -> RtmMessage?,
) {
    private val gson = Gson()

    /**
     * Sends a chat message as a barrage (scrolling message) to the RTM channel.
     */
    fun sendChatBarrage(text: String, type: Int, onComplete: (Boolean) -> Unit = {}) {
        if (text.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        val json = JSONObject()
            .put(RTM_MESSAGE_KEY_TEXT, text)
            .put(RTM_MESSAGE_KEY_TOPIC, RTM_MESSAGE_TOPIC_CHAT)
            .put(RTM_MESSAGE_KEY_TYPE, type)
            .put(RTM_MESSAGE_KEY_SENDTIMESTAMP, timestamp)
            .toString()
        send(topic = RTM_MESSAGE_TOPIC_CHAT, payload = json, timestamp = timestamp, encrypt = true, onComplete = onComplete)
    }

    /**
     * Toggles the mute state for a remote participant in the call.
     */
    fun toggleMute(target: Participant) {
        if (target is LocalParticipant) return
        val identities = target.identity?.let { listOf(it) } ?: return
        val timestamp = System.currentTimeMillis()
        val msg = RtmMessage(
            topic = RTM_MESSAGE_TOPIC_MUTE,
            identities = identities,
            sendTimestamp = timestamp
        )
        val json = Json.Default.encodeToString(RtmMessage.serializer(), msg)
        send(topic = RTM_MESSAGE_TOPIC_MUTE, payload = json, timestamp = timestamp, encrypt = true, onComplete = {}, identities = identities)
    }

    /**
     * Handles various RTM (Real-Time Messaging) events received in the room and dispatches them to appropriate callbacks.
     *
     * This method processes incoming data messages from the RTM channel, decrypts them, and routes them to
     * different handler functions based on the message topic. It supports multiple message types including
     * chat messages, mute/unmute requests, call control commands, countdown timer operations, and hand raising events.
     */
    fun handleDataReceived(event: RoomEvent.DataReceived, onChat: (Participant, String, Int?) -> Unit, onMuteMe: () -> Unit, onResumeMe: () -> Unit, onEndCall: () -> Unit, onCountDown: (CountDownTimerData, String) -> Unit, onHandsUp: (HandsUpData, String) -> Unit) {
        val topic = event.topic ?: return
        when (topic) {
            RTM_MESSAGE_TOPIC_CHAT, RTM_MESSAGE_TOPIC_MUTE, RTM_MESSAGE_TOPIC_RESUME_CALL -> {
                val p = event.participant ?: return
                scope.launch(Dispatchers.IO) {
                    val rtm = decryptor(p, event.data)
                    when (topic) {
                        RTM_MESSAGE_TOPIC_CHAT -> rtm?.text?.let { onChat(p, it, rtm.type) }
                        RTM_MESSAGE_TOPIC_MUTE -> {
                            val localIdentity = room.localParticipant.identity?.value
                            if (localIdentity != null && rtm?.identities?.map { it.value }?.any { it.contains(localIdentity) } == true) {
                                onMuteMe()
                            }
                        }
                        RTM_MESSAGE_TOPIC_RESUME_CALL -> if (rtm?.identities?.map { it.value }?.any { it.contains(room.localParticipant.identity!!.value) } == true) onResumeMe()
                    }
                }
            }
            RTM_MESSAGE_TOPIC_END_CALL -> {
                scope.launch(Dispatchers.IO) {
                    val p = event.participant ?: return@launch
                    val rtm = decryptor(p, event.data)
                    if (topic == rtm?.topic) onEndCall()
                }
            }
            RTM_MESSAGE_TOPIC_SET_COUNTDOWN, RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN, RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN, RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN -> {
                val packet = try { gson.fromJson(String(event.data, Charsets.UTF_8), RtmDataPacket::class.java) } catch (e: Exception) { null }
                packet?.payload?.let { payload ->
                    val data = try { gson.fromJson(payload, CountDownTimerData::class.java) } catch (e: Exception) { null }
                    if (data != null) onCountDown(data, topic)
                }
            }

            RTM_MESSAGE_TOPIC_RAISE_HANDS_UP, RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP -> {
                val packet = try { gson.fromJson(String(event.data, Charsets.UTF_8), RtmDataPacket::class.java) } catch (e: Exception) { null }
                packet?.payload?.let { payload ->
                    val data = try { gson.fromJson(payload, HandsUpData::class.java) } catch (e: Exception) { null }
                    if (data != null) onHandsUp(data, topic)
                }
            }

        }
    }

    /**
     * Sends an end-call command to all participants in the room via RTM (Real-Time Messaging).
     */
    fun sendEndCall(onComplete: (Boolean) -> Unit) {
        val timestamp = System.currentTimeMillis()
        val json = gson.toJson(
            EndCallRtmMessage(
                topic = RTM_MESSAGE_TOPIC_END_CALL,
                sendTimestamp = timestamp
            )
        )
        send(topic = RTM_MESSAGE_TOPIC_END_CALL, payload = json, timestamp = timestamp, encrypt = true, onComplete = onComplete)
    }

    /**
     * Sends an RTM (Real-Time Messaging) message to a participant to continue a call.
     */
    fun sendContinueCallRtmMessage(participant: Participant) {
        L.i { "[Call] LCallViewModel sendContinueCallRtmMessage" }
        participant.identity?.let { identity ->
            val identities = listOf(identity)
            val timestamp = System.currentTimeMillis()
            val rtmMessage = RtmMessage(
                topic = RTM_MESSAGE_TOPIC_RESUME_CALL,
                sendTimestamp = timestamp,
                identities = identities,
            )
            val json = Json.Default.encodeToString(rtmMessage)
            send(topic = RTM_MESSAGE_TOPIC_RESUME_CALL, payload = json, timestamp = timestamp, encrypt = true, onComplete = {}, identities = identities)
        }
    }

    /**
     * Sends an RTM (Real-Time Messaging) message indicating whether a participant has raised or canceled their hand.
     */
    fun sendRaiseHandsRtmMessage(enabled: Boolean, participant: Participant, onComplete: (Boolean) -> Unit) {
        val topic = if (enabled) RTM_MESSAGE_TOPIC_RAISE_HANDS_UP else RTM_MESSAGE_TOPIC_CANCEL_HANDS_UP
        // 发送 RTM
        val identities = listOfNotNull(participant.identity?.value)
        val timestamp = System.currentTimeMillis()
        val json = if (enabled)
            gson.toJson(
                RtmDataPacket(
                    payload = gson.toJson(RaiseHandRtmMessage(topic = topic)),
                    signature = "",
                    sendTimestamp = timestamp,
                    uuid = UUID.randomUUID().toString()
                )
            )
        else
            gson.toJson(
                RtmDataPacket(
                    payload = gson.toJson(
                        CancelHandRtmMessage(
                            topic = topic,
                            hands = identities
                        )
                    ),
                    signature = "",
                    sendTimestamp = timestamp,
                    uuid = UUID.randomUUID().toString()
                )
            )

        send(topic = topic, payload = json, timestamp = timestamp, encrypt = false, onComplete = onComplete)
    }

    /**
     * Sends a data payload through the RTM (Real-Time Messaging) channel with optional encryption.
     */
    private fun send(topic: String, payload: String, timestamp: Long, encrypt: Boolean, onComplete: (Boolean) -> Unit, identities: List<Participant.Identity>? = null) {
        scope.launch(Dispatchers.IO) {
            val success = try {
                val data = if (encrypt) encryptor(payload.toByteArray(Charsets.UTF_8), timestamp) else payload
                if (data == null) {
                    L.i { "[Call] rtm encryptor result is null" };
                    withContext(Dispatchers.Main) {
                        onComplete(false)
                    }
                    return@launch }
                val result = room.localParticipant.publishData(
                    data = data.toByteArray(Charsets.UTF_8),
                    identities = identities,
                    topic = topic,
                )
                true
            } catch (e: Exception) {
                L.e { "[Call] RtmMessageHandler send error = ${e.message}" }
                false
            }

            // Always switch to Main thread for callback
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }
}