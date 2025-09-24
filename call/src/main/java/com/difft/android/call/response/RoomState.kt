package com.difft.android.call.response

data class RoomState(
    val createdAt: Long,
    val anotherDeviceJoined: Boolean,
    val userStopped: Boolean,
)
