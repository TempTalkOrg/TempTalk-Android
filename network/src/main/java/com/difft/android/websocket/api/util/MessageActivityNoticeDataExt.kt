package com.difft.android.websocket.api.util

import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.MessageActivityNoticeData
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CopyData
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageActivityNotice


















fun MessageActivityNoticeData.toProtoTypeDataBuilder(): MessageActivityNotice.Builder {
    val builder = MessageActivityNotice.newBuilder()
    when (this.type) {
        MessageActivityNoticeData.Type.COPY -> {
            builder.copyData = CopyData.newBuilder()
                .addAllSourceAuthorIds(this.sourceAuthorIds)
                .setMessageCount(this.messageCount)
                .setCombinedForwardMode(this.combinedForwardMode.toProtoEnum())
                .build()
        }
    }
    return builder
}





fun MessageActivityNotice.toKotlinDataOrNull(): MessageActivityNoticeData? {
    return when (this.typeDataCase) {
        MessageActivityNotice.TypeDataCase.COPYDATA -> {
            val copy = this.copyData
            MessageActivityNoticeData(
                type = MessageActivityNoticeData.Type.COPY,
                sourceAuthorIds = copy.sourceAuthorIdsList.toList(),
                
                
                messageCount = maxOf(1, copy.messageCount),
                combinedForwardMode = copy.combinedForwardMode.toKotlinEnum(),
            )
        }
        MessageActivityNotice.TypeDataCase.TYPEDATA_NOT_SET -> null   
    }
}









fun CombinedForwardMode.toProtoEnum(): MessageActivityNotice.CombinedForwardMode = when (this) {
    CombinedForwardMode.UNKNOWN                   -> MessageActivityNotice.CombinedForwardMode.UNKNOWN
    CombinedForwardMode.CONTAINS_COMBINED_FORWARD -> MessageActivityNotice.CombinedForwardMode.CONTAINS_COMBINED_FORWARD
    CombinedForwardMode.ALL_COMBINED_FORWARD      -> MessageActivityNotice.CombinedForwardMode.ALL_COMBINED_FORWARD
    CombinedForwardMode.SUB_COMBINED_FORWARD      -> MessageActivityNotice.CombinedForwardMode.SUB_COMBINED_FORWARD
}

fun MessageActivityNotice.CombinedForwardMode.toKotlinEnum(): CombinedForwardMode = when (this) {
    MessageActivityNotice.CombinedForwardMode.UNKNOWN                   -> CombinedForwardMode.UNKNOWN
    MessageActivityNotice.CombinedForwardMode.CONTAINS_COMBINED_FORWARD -> CombinedForwardMode.CONTAINS_COMBINED_FORWARD
    MessageActivityNotice.CombinedForwardMode.ALL_COMBINED_FORWARD      -> CombinedForwardMode.ALL_COMBINED_FORWARD
    MessageActivityNotice.CombinedForwardMode.SUB_COMBINED_FORWARD      -> CombinedForwardMode.SUB_COMBINED_FORWARD
}
