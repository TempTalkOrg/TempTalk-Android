package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

class RegistrationLockFailure {
    @JvmField
    @JsonProperty
    var length: Int = 0

    @JvmField
    @JsonProperty
    var timeRemaining: Long = 0

    @JvmField
    @JsonProperty
    var backupCredentials: AuthCredentials? = null
}
