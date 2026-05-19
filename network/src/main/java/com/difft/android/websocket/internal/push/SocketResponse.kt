package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * {"ver":1,"status":10105,"reason":"This account has logged out and messages can not be reached.","data":null}
 */
data class SocketResponse(
    @JsonProperty
    var ver: Int = 0,

    @JsonProperty
    var status: Int = 0,

    @JsonProperty
    var reason: String = ""
)
