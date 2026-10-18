package com.difft.android.call.session

import io.livekit.android.room.Room

/**
 * Where the session's room setup (Phase B, `CallRoomWiring.kt`) stands. It starts [Pending] and
 * resolves once: [Ready] with the room the call page renders, or [Failed], after which the session
 * ends itself. A call page waits for it to leave [Pending] before it shows the call.
 */
sealed interface CallRoomState {
    data object Pending : CallRoomState

    data class Ready(val room: Room) : CallRoomState

    data object Failed : CallRoomState
}
