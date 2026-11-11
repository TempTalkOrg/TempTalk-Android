package difft.android.messageserialization.model









const val MARK_UNREAD_FLAG_CLEAR = 0


const val MARK_UNREAD_FLAG_UNREAD = 1


const val MARK_UNREAD_FLAG_READ = 2









fun isManualUnreadActive(
    markUnreadFlag: Int,
    markUnreadAt: Long,
    lastActiveTime: Long,
    readPosition: Long,
): Boolean =
    markUnreadFlag == MARK_UNREAD_FLAG_UNREAD &&
        markUnreadAt > 0 &&
        lastActiveTime <= markUnreadAt &&
        readPosition < markUnreadAt








fun resolveIncomingMarkUnread(
    incomingFlag: Int,
    incomingAt: Long,
    storedAt: Long,
    readPosition: Long,
): Int? {
    if (incomingAt <= storedAt) return null
    if (incomingFlag == MARK_UNREAD_FLAG_UNREAD && readPosition >= incomingAt) return MARK_UNREAD_FLAG_CLEAR
    return incomingFlag
}
