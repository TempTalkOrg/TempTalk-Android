package com.difft.android.call.handler

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.CountDownTimerData
import com.difft.android.call.data.EndCallRtmMessage
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CHAT
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_END_CALL
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_MUTE
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_RESUME_CALL
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SERVER_END_CALL
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_SET_COUNTDOWN
import com.difft.android.call.data.RtmDataPacket
import com.difft.android.call.data.RtmMessage
import com.difft.android.call.data.ServerEndCallRtmMessage
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RtmMessageHandler(
    private val room: Room,
    private val scope: CoroutineScope,
    private val encryptor: (plain: ByteArray, timestamp: Long) -> String?,
    private val decryptor: (participant: Participant, cipher: ByteArray) -> RtmMessage?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    


    fun sendChatBarrage(text: String, type: Int, onComplete: (Boolean) -> Unit = {}) {
        if (text.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        val msg = RtmMessage(
            text = text,
            topic = RTM_MESSAGE_TOPIC_CHAT,
            type = type,
            sendTimestamp = timestamp
        )
        val payload = json.encodeToString(msg)
        send(topic = RTM_MESSAGE_TOPIC_CHAT, payload = payload, timestamp = timestamp, encrypt = true, onComplete = onComplete)
    }

    





    fun toggleMute(target: Participant, onComplete: (Boolean) -> Unit) {
        val identities = target.identity?.takeIf { target !is LocalParticipant }?.let { listOf(it) }
        if (identities == null) {
            L.w { "[Call] toggleMute skipped local=${target is LocalParticipant} hasIdentity=${target.identity != null}" }
            onComplete(false)
            return
        }
        val timestamp = System.currentTimeMillis()
        val msg = RtmMessage(
            topic = RTM_MESSAGE_TOPIC_MUTE,
            identities = identities,
            sendTimestamp = timestamp
        )
        val payload = json.encodeToString(RtmMessage.serializer(), msg)
        send(topic = RTM_MESSAGE_TOPIC_MUTE, payload = payload, timestamp = timestamp, encrypt = true, onComplete = onComplete, identities = identities)
    }

    






    fun handleDataReceived(
        event: RoomEvent.DataReceived,
        onChat: (Participant, String, Int?) -> Unit,
        onMuteMe: () -> Unit,
        onResumeMe: () -> Unit,
        onEndCall: () -> Unit,
        onServerEndCall: () -> Unit,
        onCountDown: (CountDownTimerData, String) -> Unit,
    ) {
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
            RTM_MESSAGE_TOPIC_SERVER_END_CALL -> handleServerEndCallPacket(event, topic, onServerEndCall)
            RTM_MESSAGE_TOPIC_SET_COUNTDOWN, RTM_MESSAGE_TOPIC_RESTART_COUNTDOWN, RTM_MESSAGE_TOPIC_EXTEND_COUNTDOWN, RTM_MESSAGE_TOPIC_CLEAR_COUNTDOWN -> {
                val packet = try { json.decodeFromString<RtmDataPacket>(String(event.data, Charsets.UTF_8)) } catch (e: Exception) {
                    L.e { "[Call] handleDataReceived decode countdown rtm message failed, error = ${e.message}" }
                    null
                }
                packet?.payload?.let { payload ->
                    val data = try { json.decodeFromString<CountDownTimerData>(payload) } catch (e: Exception) {
                        L.e { "[Call] handleDataReceived decode countdown data failed, error = ${e.message}" }
                        null
                    }
                    if (data != null) onCountDown(data, topic)
                }
            }
        }
    }

    




    private fun handleServerEndCallPacket(
        event: RoomEvent.DataReceived,
        outerTopic: String,
        onServerEndCall: () -> Unit,
    ) {
        val p = event.participant
        if (p != null) {
            L.w { "[Call] server-end-call REJECTED - participant not null (identity=${p.identity?.value})" }
            return
        }
        val packet = try {
            json.decodeFromString<RtmDataPacket>(String(event.data, Charsets.UTF_8))
        } catch (e: Exception) {
            L.w { "[Call] server-end-call REJECTED - decode outer failed: ${e.message}" }
            return
        }
        val inner = try {
            json.decodeFromString<ServerEndCallRtmMessage>(packet.payload)
        } catch (e: Exception) {
            L.w { "[Call] server-end-call REJECTED - decode inner failed: ${e.message}" }
            return
        }
        if (inner.topic != outerTopic) {
            L.w { "[Call] server-end-call REJECTED - inner topic '${inner.topic}' != outer '$outerTopic'" }
            return
        }
        if (inner.sendTimestamp != packet.sendTimestamp) {
            L.w { "[Call] server-end-call REJECTED - inner ts ${inner.sendTimestamp} != outer ${packet.sendTimestamp}" }
            return
        }
        L.i { "[Call] server-end-call ACCEPTED uuid=${packet.uuid} sendTimestamp=${inner.sendTimestamp}" }
        onServerEndCall()
    }

    


    fun sendEndCall(onComplete: (Boolean) -> Unit) {
        val timestamp = System.currentTimeMillis()
        val payload = json.encodeToString(
            EndCallRtmMessage(
                topic = RTM_MESSAGE_TOPIC_END_CALL,
                sendTimestamp = timestamp
            )
        )
        send(topic = RTM_MESSAGE_TOPIC_END_CALL, payload = payload, timestamp = timestamp, encrypt = true, onComplete = onComplete)
    }

    


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
            val payload = json.encodeToString(rtmMessage)
            send(topic = RTM_MESSAGE_TOPIC_RESUME_CALL, payload = payload, timestamp = timestamp, encrypt = true, onComplete = {}, identities = identities)
        }
    }

    


    private fun send(topic: String, payload: String, timestamp: Long, encrypt: Boolean, onComplete: (Boolean) -> Unit, identities: List<Participant.Identity>? = null) {
        scope.launch(Dispatchers.IO) {
            val success = try {
                val data = if (encrypt) encryptor(payload.toByteArray(Charsets.UTF_8), timestamp) else payload
                if (data == null) {
                    L.i { "[Call] rtm encryptor result is null" }
                    withContext(Dispatchers.Main) {
                        onComplete(false)
                    }
                    return@launch }
                val result = room.localParticipant.publishData(
                    data = data.toByteArray(Charsets.UTF_8),
                    identities = identities,
                    topic = topic,
                )
                if (result.isSuccess) {
                    true
                } else {
                    L.e { "[Call] RtmMessageHandler send failed, error = ${result.exceptionOrNull()?.message}" }
                    false
                }
            } catch (e: Exception) {
                L.e { "[Call] RtmMessageHandler send error = ${e.message}" }
                false
            }

            
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }
}