package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

data class DeviceLimit(
    @JsonProperty
    var current: Int = 0,

    @JsonProperty
    var max: Int = 0
)
