package util

import android.annotation.SuppressLint
import com.difft.android.base.R
import com.difft.android.base.utils.ResUtils
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Time formatting helpers.
 *
 * Slimmed from the vendored Blankj TimeUtils down to the two methods actually used
 * ([millis2String] and [millis2FitTimeSpan]); the remaining ~100 unused methods and
 * the companion TimeConstants were removed.
 */
object TimeUtils {

    private val SDF_THREAD_LOCAL: ThreadLocal<MutableMap<String, SimpleDateFormat>> =
        ThreadLocal.withInitial { HashMap<String, SimpleDateFormat>() }

    @SuppressLint("SimpleDateFormat")
    private fun getSafeDateFormat(pattern: String): SimpleDateFormat {
        val sdfMap = SDF_THREAD_LOCAL.get()!!
        return sdfMap.getOrPut(pattern) { SimpleDateFormat(pattern) }
    }

    private fun getDefaultFormat(): SimpleDateFormat = getSafeDateFormat("yyyy-MM-dd HH:mm:ss")

    /**
     * Milliseconds to the formatted time string. The pattern is `yyyy-MM-dd HH:mm:ss`.
     */
    @JvmStatic
    fun millis2String(millis: Long): String = millis2String(millis, getDefaultFormat())

    /**
     * Milliseconds to the formatted time string with the given [pattern] (e.g. `yyyy/MM/dd HH:mm`).
     */
    @JvmStatic
    fun millis2String(millis: Long, pattern: String): String = millis2String(millis, getSafeDateFormat(pattern))

    private fun millis2String(millis: Long, format: DateFormat): String = format.format(Date(millis))

    /**
     * Milliseconds to a fit human-readable time span, e.g. "2days3hours".
     *
     * @param precision how many units to render (clamped to 1..5); a non-positive value yields "".
     * @param isShort   use the short unit labels.
     */
    @JvmStatic
    fun millis2FitTimeSpan(millis: Long, precision: Int, isShort: Boolean): String {
        if (precision <= 0) return ""
        val p = minOf(precision, 5)
        val units = if (isShort) {
            arrayOf(
                ResUtils.getString(R.string.DateUtils_days_short),
                ResUtils.getString(R.string.DateUtils_hours_short),
                ResUtils.getString(R.string.DateUtils_minutes_short),
                ResUtils.getString(R.string.DateUtils_seconds_short),
                ResUtils.getString(R.string.DateUtils_millis_short)
            )
        } else {
            arrayOf(
                ResUtils.getString(R.string.DateUtils_days),
                ResUtils.getString(R.string.DateUtils_hours),
                ResUtils.getString(R.string.DateUtils_minutes),
                ResUtils.getString(R.string.DateUtils_seconds),
                ResUtils.getString(R.string.DateUtils_millis)
            )
        }
        if (millis == 0L) return "0" + units[p - 1]
        var remaining = millis
        val sb = StringBuilder()
        if (remaining < 0) {
            sb.append("-")
            remaining = -remaining
        }
        val unitLen = intArrayOf(86400000, 3600000, 60000, 1000, 1)
        for (i in 0 until p) {
            if (remaining >= unitLen[i]) {
                val mode = remaining / unitLen[i]
                remaining -= mode * unitLen[i]
                if (mode < 2) {
                    sb.append(mode).append(units[i].replace("s", ""))
                } else {
                    sb.append(mode).append(units[i])
                }
            }
        }
        return sb.toString()
    }
}
