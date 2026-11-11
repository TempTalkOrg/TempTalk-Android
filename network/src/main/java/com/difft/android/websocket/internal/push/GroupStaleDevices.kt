package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty




data class GroupStaleDevices(
    @JsonProperty
    var uuid: String = "",

    @JsonProperty
    var devices: StaleDevices = StaleDevices()
)
