package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents the body of a 409 response from the service during a sender key send.
 */
data class GroupMismatchedDevices(
    @JsonProperty
    var uuid: String = "",

    @JsonProperty
    var devices: MismatchedDevices = MismatchedDevices()
)
