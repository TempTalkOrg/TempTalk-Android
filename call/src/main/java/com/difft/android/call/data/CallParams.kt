package com.difft.android.call.data

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallRole
import com.difft.android.base.ui.theme.SfProFont


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
    fontFamily = SfProFont,
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