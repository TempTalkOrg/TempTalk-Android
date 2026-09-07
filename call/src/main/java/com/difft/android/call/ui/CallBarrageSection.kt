package com.difft.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.ui.barrage.BarrageMessageView
import io.livekit.android.room.Room

@Composable
internal fun CallBarrageMessageSection(
    viewModel: LCallViewModel,
    callConfig: CallConfig,
    autoHideTimeout: Long,
    isOneVOneCall: Boolean,
    room: Room,
) {
    val barrageConfig = remember(callConfig, autoHideTimeout, isOneVOneCall) {
        BarrageMessageConfig(
            isOneVOneCall = isOneVOneCall,
            barrageTexts = callConfig.chatPresets ?: emptyList(),
            displayDurationMillis = autoHideTimeout,
            baseSpeed = callConfig.bubbleMessage?.baseSpeed ?: 4600L,
            deltaSpeed = callConfig.bubbleMessage?.deltaSpeed ?: 400L,
            columns = callConfig.bubbleMessage?.columns ?: listOf(10, 40, 70),
            emojiPresets = callConfig.bubbleMessage?.emojiPresets ?: LCallUiConstants.DEFAULT_BUBBLE_EMOJIS,
            textPresets = callConfig.bubbleMessage?.textPresets ?: LCallUiConstants.DEFAULT_BUBBLE_TEXTS,
            textMaxLength = callConfig.chatMessage?.maxLength ?: 30,
        )
    }

    BarrageMessageView(
        viewModel,
        config = barrageConfig,
        sendBarrageMessage = { message, type, _ ->
            viewModel.rtm.sendChatBarrage(message, type, onComplete = { status ->
                if (status) {
                    if (type == RTM_MESSAGE_TYPE_DEFAULT) {
                        viewModel.showCallBarrageMessage(room.localParticipant, message)
                    }
                } else {
                    L.e { "[Call] Failed to send barrage message." }
                }
            })
        },
    )
}
