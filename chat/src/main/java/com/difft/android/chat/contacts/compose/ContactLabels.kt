package com.difft.android.chat.contacts.compose



fun memberCountLabel(memberCount: Int): String? = memberCount.takeIf { it > 0 }?.toString()


fun countdownDaysLabel(days: Int?, todayLabel: String, daysFormat: String): String? = when {
    days == null -> null
    days <= 0 -> todayLabel
    else -> String.format(daysFormat, days)
}
