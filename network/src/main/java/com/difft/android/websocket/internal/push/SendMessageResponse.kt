package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

class SendMessageResponse {
    @JsonProperty
    var isNeedsSync: Boolean = false //是否需要发送同步信息（客户端没用）

    @JvmField
    @JsonProperty
    var sequenceId: Int = 0 //消息sid，用于消息排序及连续性判断

    @JvmField
    @JsonProperty
    var systemShowTimestamp: Long = 0 //消息服务端时间戳

    @JvmField
    @JsonProperty
    var notifySequenceId: Long = 0 //消息nsid，用于计算未读数

    constructor()

    constructor(
        needsSync: Boolean,
        sequenceId: Int,
        systemShowTimestamp: Long,
        notifySequenceId: Long
    ) {
        this.isNeedsSync = needsSync
        this.sequenceId = sequenceId
        this.systemShowTimestamp = systemShowTimestamp
        this.notifySequenceId = notifySequenceId
    }
}
