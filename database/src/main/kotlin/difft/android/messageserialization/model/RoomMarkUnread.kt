package difft.android.messageserialization.model

/**
 * Domain of `RoomModel.markUnreadFlag` — the user's manual "mark as unread / read" state,
 * synced across the account's devices via `SyncMessage.markAsUnread`. Values mirror the
 * wire enum `SyncMessage.MarkAsUnread.Flag` so the stored value is the flag as received.
 *
 * CLEAR MUST stay 0: the column ships with `@WCDBDefault(intValue = 0)`, so pre-existing rows
 * read back as "no manual state".
 */
const val MARK_UNREAD_FLAG_CLEAR = 0

/** The user marked the conversation unread; rendered as a badge of "1" while no real unread exists. */
const val MARK_UNREAD_FLAG_UNREAD = 1

/** The user marked the conversation read from the list (also clears any manual-unread state). */
const val MARK_UNREAD_FLAG_READ = 2

/**
 * Whether a stored manual-unread mark is still visible.
 *
 * The mark only counts while nothing newer than the mark time has happened in the room: a
 * message arriving (or being sent) after [markUnreadAt] supersedes it, and a read position at
 * or past the mark time means the user has since read the room. Sibling rule: iOS
 * `HomeViewCell.isManualUnread`, Desktop `getDisplayUnreadCount`.
 */
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

/**
 * Decides how an incoming `markAsUnread` sync applies to the stored state (Desktop
 * `onMarkAsUnread` rules): a sync older than the stored mark is ignored (offline-queue
 * reordering), and an UNREAD mark already read past by this device is stored as CLEAR.
 *
 * @return the flag to store, or `null` when the sync must be ignored.
 */
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
