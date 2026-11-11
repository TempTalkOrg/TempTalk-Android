package difft.android.messageserialization.model



















const val ROOM_SEND_STATUS_NONE = 0







const val ROOM_SEND_STATUS_SENDING = 1


const val ROOM_SEND_STATUS_FAILED = 2













const val ROOM_SENDING_STATUS_NONE = 0


const val ROOM_SENDING_STATUS_ACTIVE = 1





fun aggregateRoomSendStatus(hasFailed: Boolean, hasSending: Boolean = false): Int = when {
    hasFailed -> ROOM_SEND_STATUS_FAILED
    hasSending -> ROOM_SEND_STATUS_SENDING
    else -> ROOM_SEND_STATUS_NONE
}











fun needsSendStatusRecompute(storedStatus: Int): Boolean = storedStatus != ROOM_SEND_STATUS_NONE












fun resolveRoomSendStatus(storedStatus: Int, hasFailed: Boolean): Int? {
    if (storedStatus == ROOM_SEND_STATUS_NONE) return null
    val target = aggregateRoomSendStatus(hasFailed)
    return target.takeIf { it != storedStatus }
}







fun roomSendStatusBackfillPlan(
    roomsWithFailedMessage: Set<String>,
    roomsCurrentlyFlagged: Set<String>,
): Pair<List<String>, List<String>> =
    (roomsWithFailedMessage - roomsCurrentlyFlagged).toList() to
        (roomsCurrentlyFlagged - roomsWithFailedMessage).toList()
