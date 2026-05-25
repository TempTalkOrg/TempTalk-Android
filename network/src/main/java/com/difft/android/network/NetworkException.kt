package com.difft.android.network

import com.difft.android.base.utils.globalServices
import java.io.IOException

class NetworkException(val errorCode: Int? = -1, override val message: String = "") : IOException(message) {
    val errorMsg: String
        get() =
            try {
                val errorBody = globalServices.gson.fromJson(message, ErrorData::class.java)
                errorBody.reason
            } catch (e: Throwable) {
                message
            }
}