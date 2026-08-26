package util

import android.content.Context
import android.text.format.DateFormat
import com.difft.android.base.R
import com.difft.android.base.utils.application
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object TimeFormatter {

    private const val MILLIS_PER_DAY = 86_400_000L

    // Cache the result to avoid repeated ContentProvider IPC calls on every message bind.
    // The 12/24-hour format setting rarely changes; the app restart picks up any change.
    private val is24HourFormat by lazy { DateFormat.is24HourFormat(application) }

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

    /**
     * Full date+time header used for multi-select copy output (PRD §3.2).
     *
     * Format (locale-aware):
     * - en: "May 8, 2026 at 14:30"
     * - zh: "2026年5月8日 14:30"
     *
     * Always 24-hour ("HH:mm") regardless of system 12/24h preference. The
     * copied text crosses devices and apps, so the formatter must be
     * deterministic and not vary by reader's local 12h setting.
     */
    fun formatCopyHeaderTime(language: String, timestamp: Long): String {
        val locale = Locale(language)
        val pattern = if (language == Locale.CHINA.language) {
            "yyyy年M月d日 HH:mm"
        } else {
            "MMM d, yyyy 'at' HH:mm"
        }
        return SimpleDateFormat(pattern, locale).format(timestamp)
    }

    // 判断是否是同一天
    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Whether two timestamps fall on the same day in the device's current timezone.
     *
     * Day-ordinal arithmetic rather than two `Calendar` instances: this is called three times per
     * row of the message window, so the allocations dominated the assembly pass. Semantics are
     * unchanged — both forms partition instants at local midnight of the default timezone.
     */
    fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        // Read the default zone once per call, exactly as the two Calendar instances did.
        val zone = TimeZone.getDefault()
        return localDayOrdinal(timestamp1, zone) == localDayOrdinal(timestamp2, zone)
    }

    /**
     * Days elapsed since the epoch at [zone]'s local midnight boundaries.
     *
     * The offset is resolved per timestamp, not once for the zone: on a DST transition day the two
     * halves carry different offsets and a single offset would move one of them across midnight.
     * `floorDiv` (not `/`) keeps pre-epoch timestamps on the day below instead of truncating them
     * toward zero, which would merge the first day before the epoch with the first day after it.
     */
    private fun localDayOrdinal(timestamp: Long, zone: TimeZone): Long =
        (timestamp + zone.getOffset(timestamp)).floorDiv(MILLIS_PER_DAY)

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