package com.difft.android.network

import com.google.gson.Gson
import java.io.IOException

class NetworkException(val errorCode: Int? = -1, override val message: String = "") : IOException(message) {
    val errorMsg: String
        get() =
            try {
                val errorBody = Gson().fromJson(message, ErrorData::class.java)
                errorBody.reason
            } catch (e: Throwable) {
                message
            }
}