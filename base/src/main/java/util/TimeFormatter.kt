package util

import android.content.Context
import android.text.format.DateFormat
import com.difft.android.base.R
import com.difft.android.base.utils.application
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeFormatter {

    fun formatConversationTime(language: String, timestamp: Long): String {
        val currentLocale = Locale(language)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val messageYear = calendar.get(Calendar.YEAR)

        val isToday = isSameDay(calendar, Calendar.getInstance())

        val is24HourFormat = DateFormat.is24HourFormat(application)

        val timeFormat: SimpleDateFormat
        val dateFormat: SimpleDateFormat

        if (language == Locale.CHINA.language) {
            if (isToday) {
                timeFormat = if (is24HourFormat) {
                    SimpleDateFormat("HH:mm", currentLocale)
                } else {
                    SimpleDateFormat("a hh:mm", currentLocale)
                }
                return timeFormat.format(calendar.time)
            } else if (messageYear == currentYear) {
                dateFormat = SimpleDateFormat("M月d日", currentLocale)
            } else {
                dateFormat = SimpleDateFormat("yyyy/M/d", currentLocale)
            }
        } else {
            if (isToday) {
                timeFormat = if (is24HourFormat) {
                    SimpleDateFormat("HH:mm", currentLocale)
                } else {
                    SimpleDateFormat("hh:mm a", currentLocale)
                }
                return timeFormat.format(calendar.time)
            } else if (messageYear == currentYear) {
                dateFormat = SimpleDateFormat("M/d", currentLocale)
            } else {
                dateFormat = SimpleDateFormat("yyyy/M/d", currentLocale)
            }
        }

        return dateFormat.format(calendar.time)
    }

    fun formatMessageTime(language: String, timestamp: Long): String {
        val currentLocale = Locale(language)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp

        val is24HourFormat = DateFormat.is24HourFormat(application)

        val pattern = when {
            is24HourFormat -> "HH:mm"
            language == Locale.CHINA.language -> "a hh:mm"
            else -> "hh:mm a"
        }

        return SimpleDateFormat(pattern, currentLocale).format(calendar.time)
    }

    // 判断是否是同一天
    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    // 判断两个时间戳是否是同一天
    fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        val calendar1 = Calendar.getInstance()
        val calendar2 = Calendar.getInstance()

        calendar1.timeInMillis = timestamp1
        calendar2.timeInMillis = timestamp2

        return calendar1.get(Calendar.YEAR) == calendar2.get(Calendar.YEAR) &&
                calendar1.get(Calendar.DAY_OF_YEAR) == calendar2.get(Calendar.DAY_OF_YEAR)
    }

    fun getConversationDateHeaderString(context: Context, locale: Locale, timestamp: Long): String {
        return if (isToday(timestamp)) {
            context.getString(R.string.DateUtils_today) // 今天
        } else if (isYesterday(timestamp)) {
            context.getString(R.string.DateUtils_yesterday) // 昨天
        } else if (isWithinOneYear(timestamp)) { // 一年内
            if (locale.language == Locale.CHINA.language) {
                formatDateWithinOneYearZH(locale, timestamp)
            } else {
                formatDateWithinOneYear(locale, timestamp)
            }
        } else { // 超过一年
            return if (locale.language == Locale.CHINA.language) {
                formatDateBeyondOneYearZH(locale, timestamp)
            } else {
                formatDateBeyondOneYear(locale, timestamp)
            }
        }
    }

    // 判断时间戳是否是今天
    private fun isToday(timestamp: Long): Boolean {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val today = Calendar.getInstance()
        return today.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)
    }

    // 判断时间戳是否是昨天
    private fun isYesterday(timestamp: Long): Boolean {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        return yesterday.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)
    }

    // 判断时间戳是否在一年内
    private fun isWithinOneYear(timestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val oneYearAgo = currentTime - TimeUnit.DAYS.toMillis(365)
        return timestamp in (oneYearAgo + 1)..currentTime
    }

    // 格式化一年内的日期（中文） 1月22日 周五
    private fun formatDateWithinOneYearZH(locale: Locale, timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val sdf = SimpleDateFormat("M月d日 E", locale)
        return sdf.format(calendar.time)
    }

    // 格式化一年内的日期（英文） Fri, Jan 22
    private fun formatDateWithinOneYear(locale: Locale, timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val sdf = SimpleDateFormat("EEE, MMM d", locale)
        return sdf.format(calendar.time)
    }

    // 格式化超过一年日期（中文） 2024年10月17日
    private fun formatDateBeyondOneYearZH(locale: Locale, timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val sdf = SimpleDateFormat("yyyy年M月d日", locale)
        return sdf.format(calendar.time)
    }

    // 格式化超过一年日期（英文） Oct 17, 2024
    private fun formatDateBeyondOneYear(locale: Locale, timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val sdf = SimpleDateFormat("MMM d, yyyy", locale)
        return sdf.format(calendar.time)
    }
}