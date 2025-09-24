package com.difft.android.base.utils

import okhttp3.Credentials
import okio.ByteString.Companion.decodeBase64

class AuthCredentials(var username: String, var password: String) {
    companion object {
        fun decode(basic: String): Pair<String, String> {
            val splits = basic.removePrefix("Basic ")
                .decodeBase64()
                ?.toString()
                ?.removePrefix("[text=")
                ?.removeSuffix("]")
                ?.split(":")
                ?.takeIf { it.size == 2 }
                ?: throw IllegalArgumentException("Wrong basic value.")
            return splits[0] to splits[1]
        }
    }

    fun asBasic(): String {
        return Credentials.basic(username, password)
    }
}