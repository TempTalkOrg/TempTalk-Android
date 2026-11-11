package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty




data class SocketResponse(
    @JsonProperty
    var ver: Int = 0,

    @JsonProperty
    var status: Int = 0,

    @JsonProperty
    var reason: String = ""
)
