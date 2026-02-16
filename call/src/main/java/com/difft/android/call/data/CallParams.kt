package com.difft.android.call.data

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.StartCallRequestBody
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