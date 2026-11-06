package com.difft.android.chat.focus

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Formats a focus end time as the settings rows and captions show it. Pure, so it is unit tested. */
object FocusTimeFormat {

    /** [time] is the localized clock time; [tomorrow] is true when it is not on today's date (shown as "Tomorrow HH:MM"). */
    data class EndLabel(val time: String, val tomorrow: Boolean)

    fun endLabel(
        until: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): EndLabel {
        val end = Instant.ofEpochMilli(until).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return EndLabel(time = formatter.format(end), tomorrow = end.toLocalDate() != today)
    }
}
