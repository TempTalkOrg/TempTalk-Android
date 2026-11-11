package com.difft.android.call.session

import io.livekit.android.room.Room






sealed interface CallRoomState {
    data object Pending : CallRoomState

    data class Ready(val room: Room) : CallRoomState

    data object Failed : CallRoomState
}
