package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

data class StaleDevices(
    @JsonProperty
    var staleDevices: List<Int> = emptyList()
)
