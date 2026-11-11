package com.difft.android.websocket.api.util

import difft.android.messageserialization.model.ForwardNoticeData
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage.ForwardScene












fun ForwardNoticeData.Scene.toProtoEnum(): ForwardScene = when (this) {
    ForwardNoticeData.Scene.SINGLE        -> ForwardScene.SINGLE
    ForwardNoticeData.Scene.ONE_BY_ONE    -> ForwardScene.ONE_BY_ONE
    ForwardNoticeData.Scene.COMBINED      -> ForwardScene.COMBINED
    ForwardNoticeData.Scene.SAVE_TO_NOTES -> ForwardScene.SAVE_TO_NOTES
}

fun ForwardScene.toKotlinEnum(): ForwardNoticeData.Scene? = when (this) {
    ForwardScene.UNKNOWN       -> null   
    ForwardScene.SINGLE        -> ForwardNoticeData.Scene.SINGLE
    ForwardScene.ONE_BY_ONE    -> ForwardNoticeData.Scene.ONE_BY_ONE
    ForwardScene.COMBINED      -> ForwardNoticeData.Scene.COMBINED
    ForwardScene.SAVE_TO_NOTES -> ForwardNoticeData.Scene.SAVE_TO_NOTES
}
