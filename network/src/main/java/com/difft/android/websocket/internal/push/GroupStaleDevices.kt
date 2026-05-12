package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents the body of a 410 response from the service during a sender key send.
 */
data class GroupStaleDevices(
    @JsonProperty
    var uuid: String = "",

    @JsonProperty
    var devices: StaleDevices = StaleDevices()
)
