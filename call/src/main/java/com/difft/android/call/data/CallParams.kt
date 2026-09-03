package com.difft.android.call.data

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.StartCallRequestBody
import io.livekit.android.room.MediaSendConnectionState
import io.livekit.android.room.Room
import livekit.LivekitTemptalk


data class CallExitParams(
    val roomId: String?,
    val callerId: String,
    val callRole: CallRole?,
    val callType: String,
    val conversationId: String?
)

enum class BottomCallEndAction {
    END_CALL,
    LEAVE_CALL,
    CANCEL
}

val BottomButtonTextStyle = TextStyle(
    fontSize = 16.sp,
    lineHeight = 24.sp,
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight(400),
    textAlign = TextAlign.Center
)

enum class CallStatus {
    JOINING,
    CALLING,
    CONNECTED,
    CONNECTED_FAILED,
    DISCONNECTED,
    RECONNECTING,
    RECONNECT_FAILED,
    RECONNECTED,
    UNKNOWN,
    SWITCHING_SERVER
}

/**
 * UI-facing send-status presentation derived from the SDK's MediaSendConnectionState +
 * Room state (doc's unified priority mapping, SDK >= 2.27.0.2).
 * CONNECTION_RECOVERING - whole meeting link recovering (room reconnect / resume / network
 *   loss) -> reuse the existing connection presentation ("连接中…")
 * SEND_RECOVERING - room healthy but the publisher uplink is recovering or failed -> the
 *   single media-recovery hint (FAILED shares it; the SDK keeps recovering after FAILED)
 */
enum class MediaSendIssueState {
    NONE,
    CONNECTION_RECOVERING,
    SEND_RECOVERING;

    companion object {
        /**
         * The doc-mandated unified priority mapping, pure so the acceptance cases are directly
         * regression-testable. Terminal states hide everything; ROOM_RECOVERING joins the
         * room-level RECONNECTING presentation; only a genuine uplink degradation while the room
         * is healthy warns. CONNECTING is normal first-negotiation and never warns (the SDK's
         * hasPublisherEverConnected gate).
         */
        fun resolve(roomState: Room.State, sendState: MediaSendConnectionState): MediaSendIssueState = when {
            roomState == Room.State.DISCONNECTED -> NONE
            roomState == Room.State.RECONNECTING ||
                sendState == MediaSendConnectionState.ROOM_RECOVERING -> CONNECTION_RECOVERING
            roomState == Room.State.CONNECTED && sendState.isMediaSendAbnormal -> SEND_RECOVERING
            else -> NONE
        }
    }
}

/**
 * Represents how a call was terminated
 * LEAVE - Individual participant left the call
 * END - Call was terminated for all participants
 */
enum class CallEndType {
    LEAVE,
    END
}


fun createStartCallParams(params: StartCallRequestBody): ByteArray {
    val params = LivekitTemptalk.TTStartCall.newBuilder().apply {
        this.type = params.type
        this.timestamp = params.timestamp

        this.version = params.version

        params.conversation?.let { conversation ->
            this.conversationId = conversation
        }

        params.publicKey?.let { publicKey ->
            this.publicKey = publicKey
        }
        params.roomId?.let { roomId ->
            this.roomId = roomId
        }

        params.clientCallId?.let { clientCallId ->
            this.clientCallId = clientCallId
        }

        params.notification?.let { notification ->
            this.notification =  LivekitTemptalk.TTNotification.newBuilder().apply {
                type = notification.type
                notification.args.let { notificationArgs ->
                    args = LivekitTemptalk.TTNotification.TTArgs.newBuilder().apply {
                        collapseId = notificationArgs.collapseId
                    }.build()
                }
            }.build()
        }

        params.encInfos?.let { encInfos ->
            val encInfos: List<LivekitTemptalk.TTEncInfo> = encInfos.map { data ->
                LivekitTemptalk.TTEncInfo.newBuilder().apply {
                    emk = data.emk
                    uid = data.uid
                }.build()
            }
            addAllEncInfos(encInfos)
        }

        params.cipherMessages?.let { cipherMessages ->
            val cipherMessages: List<LivekitTemptalk.TTCipherMessages> = cipherMessages.map { data ->
                LivekitTemptalk.TTCipherMessages.newBuilder().apply {
                    uid = data.uid
                    content = data.content
                    registrationId = data.registrationId
                }.build()
            }
            addAllCipherMessages(cipherMessages)
        }

    }.build()

    return params.toByteArray()
}