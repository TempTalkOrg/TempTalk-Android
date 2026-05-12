package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

data class MismatchedDevices(
    @JvmField
    @JsonProperty
    var missingDevices: List<Int> = emptyList(),

    @JvmField
    @JsonProperty
    var extraDevices: List<Int> = emptyList()
)
