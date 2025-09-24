package com.difft.android.call.exception


class DisconnectException(message: String? = null, cause: Throwable? = null) : Exception(message, cause){

    companion object {
        const val ROOM_DISCONNECTED_MESSAGE = "Room disconnected"
    }

}