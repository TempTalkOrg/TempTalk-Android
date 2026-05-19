package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Credentials

data class AuthCredentials(
    @JsonProperty
    var username: String = "",

    @JsonProperty
    var password: String = ""
) {
    fun asBasic(): String = Credentials.basic(username, password)

    companion object {
        @JvmStatic
        fun create(username: String, password: String): AuthCredentials =
            AuthCredentials(username = username, password = password)
    }
}
