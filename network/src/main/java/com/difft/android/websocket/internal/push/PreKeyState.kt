package com.difft.android.websocket.internal.push

import com.difft.android.websocket.internal.util.JsonUtil
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.signal.libsignal.protocol.IdentityKey

class PreKeyState {

    @field:JsonProperty
    @field:JsonSerialize(using = JsonUtil.IdentityKeySerializer::class)
    @field:JsonDeserialize(using = JsonUtil.IdentityKeyDeserializer::class)
    var identityKey: IdentityKey? = null
        private set

    @field:JsonProperty
    var newSign: String? = null
        private set

    constructor()

    constructor(identityKey: IdentityKey?, identityKeySign: String?) {
        this.identityKey = identityKey
        this.newSign = identityKeySign
    }
}
