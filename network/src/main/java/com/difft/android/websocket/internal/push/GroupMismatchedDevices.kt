package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty




data class GroupMismatchedDevices(
    @JsonProperty
    var uuid: String = "",

    @JsonProperty
    var devices: MismatchedDevices = MismatchedDevices()
)
