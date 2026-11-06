package com.difft.android.chat.focus

/**
 * Account isolation for every local focus mark kept in app_state (invalid-dialog handled episode, strip
 * dismissed episode, group-name cache, deleted-row records, pin opt-out). A passive logout keeps app_state,
 * so a mark written by one account must never apply to the next one: each stored value carries its owner uid
 * as a prefix, and a read returns the value only when the prefix is the current account. Without a known
 * current account nothing is returned.
 */
internal object FocusOwnedMark {
    private const val SEPARATOR = '|'

    /** [value] stamped for [owner]; [owner] must be non-empty and free of the separator. */
    fun encode(owner: String, value: String): String = "$owner$SEPARATOR$value"

    /** The value of [raw] when it was written by [owner]; null for another account, an unstamped value or no [owner]. */
    fun decode(raw: String?, owner: String?): String? {
        if (raw.isNullOrEmpty() || owner.isNullOrEmpty()) return null
        val index = raw.indexOf(SEPARATOR)
        if (index <= 0 || raw.substring(0, index) != owner) return null
        return raw.substring(index + 1).takeIf { it.isNotEmpty() }
    }
}
