package com.difft.android.chat.focus

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale


object FocusTimeFormat {

    
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
