package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

data class ProofRequiredResponse(
    @JvmField
    @JsonProperty
    var token: String? = null,

    @JvmField
    @JsonProperty
    var options: List<String>? = null
)
