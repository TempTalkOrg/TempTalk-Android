package com.difft.android.call.exception


class NetworkConnectionPoorException(message: String? = null, cause: Throwable? = null) : Exception(message, cause){

    companion object {
        const val CONNECTION_POOR_MESSAGE = "Connection poor"
    }

}