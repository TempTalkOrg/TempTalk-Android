package com.difft.android.chat.recent

/**
 * Remembers the format signature (language, 12/24-hour setting, time zone; see
 * `TimeFormatter.formatSignature`) the chat list's pre-formatted time texts were built with.
 * [RecentChatViewModel] outlives the Activity recreation a system language change causes, and a
 * 12/24-hour or time-zone change recreates nothing, so its retained texts can be stale; the list
 * asks [needsRefresh] when it resumes and re-formats (#1395).
 *
 * [markFormatted] runs on the IO formatting thread and [needsRefresh] on another thread.
 */
class TimeTextFormatTracker(private val readSignature: () -> String) {

    @Volatile
    private var formattedWith: String? = null

    fun markFormatted(signature: String) {
        formattedWith = signature
    }

    /** True once something was formatted and the current signature differs from it. */
    fun needsRefresh(): Boolean {
        val formatted = formattedWith ?: return false
        return readSignature() != formatted
    }
}
