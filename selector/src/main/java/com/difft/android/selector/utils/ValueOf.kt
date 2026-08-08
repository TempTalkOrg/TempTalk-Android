package com.difft.android.selector.utils

import com.difft.android.base.log.lumberjack.L

object ValueOf {
    @JvmStatic
    fun toString(o: Any?): String {
        var value = ""
        try {
            value = o?.toString() ?: ""
        } catch (e: Exception) {
            L.w(e) { "[ValueOf] toString failed" }
        }
        return value
    }

    @JvmStatic
    fun toDouble(o: Any?): Double {
        return toDouble(o, 0)
    }

    @JvmStatic
    fun toDouble(o: Any?, defaultValue: Int): Double {
        if (o == null) {
            return defaultValue.toDouble()
        }
        return try {
            o.toString().trim().toDouble()
        } catch (e: Exception) {
            defaultValue.toDouble()
        }
    }

    @JvmStatic
    fun toLong(o: Any?, defaultValue: Long): Long {
        if (o == null) {
            return defaultValue
        }
        return try {
            val s = o.toString().trim()
            if (s.contains(".")) {
                s.substring(0, s.lastIndexOf(".")).toLong()
            } else {
                s.toLong()
            }
        } catch (e: Exception) {
            defaultValue
        }
    }

    @JvmStatic
    fun toLong(o: Any?): Long {
        return toLong(o, 0)
    }

    @JvmStatic
    fun toInt(o: Any?, defaultValue: Int): Int {
        if (o == null) {
            return defaultValue
        }
        return try {
            val s = o.toString().trim()
            if (s.contains(".")) {
                s.substring(0, s.lastIndexOf(".")).toInt()
            } else {
                s.toInt()
            }
        } catch (e: Exception) {
            defaultValue
        }
    }

    @JvmStatic
    fun toInt(o: Any?): Int {
        return toInt(o, 0)
    }
}
