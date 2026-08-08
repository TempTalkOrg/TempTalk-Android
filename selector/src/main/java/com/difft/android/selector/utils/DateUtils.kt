package com.difft.android.selector.utils

import android.annotation.SuppressLint
import android.content.Context

import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.R

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {
    @SuppressLint("SimpleDateFormat")
    private val SF = SimpleDateFormat("yyyyMMddHHmmssSSS")

    @SuppressLint("SimpleDateFormat")
    private val SDF = SimpleDateFormat("yyyy-MM")

    @JvmStatic
    fun getCurrentTimeMillis(): Long {
        val timeToString = ValueOf.toString(System.currentTimeMillis())
        return ValueOf.toLong(if (timeToString.length > 10) timeToString.substring(0, 10) else timeToString)
    }

    @JvmStatic
    fun getDataFormat(context: Context, time: Long): String {
        val t = if (time.toString().length > 10) time else time * 1000
        return when {
            isThisWeek(t) -> context.getString(R.string.ps_current_week)
            isThisMonth(t) -> context.getString(R.string.ps_current_month)
            else -> SDF.format(t)
        }
    }

    private fun isThisWeek(time: Long): Boolean {
        val calendar = Calendar.getInstance()
        val currentWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        calendar.time = Date(time)
        val paramWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        return paramWeek == currentWeek
    }

    @JvmStatic
    fun isThisMonth(time: Long): Boolean {
        val date = Date(time)
        val param = SDF.format(date)
        val now = SDF.format(Date())
        return param == now
    }

    @JvmStatic
    fun millisecondToSecond(duration: Long): Long {
        return (duration / 1000) * 1000
    }

    /** Absolute difference in seconds between now and [d]. */
    @JvmStatic
    fun dateDiffer(d: Long): Int {
        return try {
            val l1 = getCurrentTimeMillis()
            val interval = l1 - d
            Math.abs(interval).toInt()
        } catch (e: Exception) {
            L.w(e) { "[DateUtils] dateDiffer error:" }
            -1
        }
    }

    @JvmStatic
    fun formatDurationTime(timeMs: Long): String {
        val prefix = if (timeMs < 0) "-" else ""
        val abs = Math.abs(timeMs)
        val totalSeconds = abs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%s%d:%02d:%02d", prefix, hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%s%02d:%02d", prefix, minutes, seconds)
        }
    }

    /** Create a file name from the current timestamp, with [prefix]. */
    @JvmStatic
    fun getCreateFileName(prefix: String): String {
        val millis = System.currentTimeMillis()
        return prefix + SF.format(millis)
    }

    /** Create a file name from the current timestamp. */
    @JvmStatic
    fun getCreateFileName(): String {
        val millis = System.currentTimeMillis()
        return SF.format(millis)
    }
}
