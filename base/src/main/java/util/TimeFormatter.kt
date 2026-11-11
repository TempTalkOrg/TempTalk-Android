package util

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.VisibleForTesting
import com.difft.android.base.R
import com.difft.android.base.utils.application
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object TimeFormatter {

    private const val MILLIS_PER_DAY = 86_400_000L

    
    
    
    @Volatile
    private var hourFormatCache: Boolean? = null

    private val is24HourFormat: Boolean
        get() = hourFormatCache ?: hourFormatReader().also { hourFormatCache = it }

    
    @VisibleForTesting
    internal var hourFormatReader: () -> Boolean = { DateFormat.is24HourFormat(application) }

    
    fun refreshHourFormat() {
        hourFormatCache = hourFormatReader()
    }

    




    fun formatSignature(language: String): String = "$language|$is24HourFormat|${TimeZone.getDefault().id}"

    @VisibleForTesting
    internal fun resetHourFormatForTest() {
        hourFormatCache = null
    }

    fun formatConversationTime(language: String, timestamp: Long): String {
        val currentLocale = Locale(language)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val messageYear = calendar.get(Calendar.YEAR)

        val isToday = isSameDay(calendar, Calendar.getInstance())

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

        val pattern = when {
            is24HourFormat -> "HH:mm"
            language == Locale.CHINA.language -> "a hh:mm"
            else -> "hh:mm a"
        }

        return SimpleDateFormat(pattern, currentLocale).format(calendar.time)
    }

    










    fun formatCopyHeaderTime(language: String, timestamp: Long): String {
        val locale = Locale(language)
        val pattern = if (language == Locale.CHINA.language) {
            "yyyy年M月d日 HH:mm"
        } else {
            "MMM d, yyyy 'at' HH:mm"
        }
        return SimpleDateFormat(pattern, locale).format(timestamp)
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    



    fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        
        val zone = TimeZone.getDefault()
        return localDayOrdinal(timestamp1, zone) == localDayOrdinal(timestamp2, zone)
    }

    







    private fun localDayOrdinal(timestamp: Long, zone: TimeZone): Long =
        (timestamp + zone.getOffset(timestamp)).floorDiv(MILLIS_PER_DAY)

    fun getConversationDateHeaderString(context: Context, locale: Locale, timestamp: Long): String {
        return if (isToday(timestamp)) {
            context.getString(R.string.DateUtils_today)
        } else if (isYesterday(timestamp)) {
            context.getString(R.string.DateUtils_yesterday)
        } else if (isWithinOneYear(timestamp)) {
            if (locale.language == Locale.CHINA.language) {
                formatDateWithinOneYearZH(locale, timestamp)
            } else {
                formatDateWithinOneYear(locale, timestamp)
            }
        } else {
            return if (locale.language == Locale.CHINA.language) {
                formatDateBeyondOneYearZH(locale, timestamp)
            } else {
                formatDateBeyondOneYear(locale, timestamp)
            }
        }
    }

    private fun isToday(timestamp: Long): Boolean {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val today = Calendar.getInstance()
        return today.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(timestamp: Long): Boolean {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        return yesterday.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)
    }

    private fun isWithinOneYear(timestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val oneYearAgo = currentTime - TimeUnit.DAYS.toMillis(365)
        return timestamp in (oneYearAgo + 1)..currentTime
    }

    private fun formatDateWithinOneYearZH(locale: Locale, timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val sdf = SimpleDateFormat("M月d日 E", locale)
        return sdf.format(calendar.time)
    }

    private fun formatDateWithinOneYear(locale: Locale, timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val sdf = SimpleDateFormat("EEE, MMM d", locale)
        return sdf.format(calendar.time)
    }

    private fun formatDateBeyondOneYearZH(locale: Locale, timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val sdf = SimpleDateFormat("yyyy年M月d日", locale)
        return sdf.format(calendar.time)
    }

    private fun formatDateBeyondOneYear(locale: Locale, timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val sdf = SimpleDateFormat("MMM d, yyyy", locale)
        return sdf.format(calendar.time)
    }
}