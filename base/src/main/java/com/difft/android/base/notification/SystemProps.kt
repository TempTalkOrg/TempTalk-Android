package com.difft.android.base.notification

import android.annotation.SuppressLint
import com.difft.android.base.log.lumberjack.L

/** Reads Android system properties through reflection; returns "" when unavailable. */
object SystemProps {

    private val VALID_KEY = Regex("^[a-zA-Z0-9._-]+$")

    @SuppressLint("PrivateApi")
    fun get(prop: String): String {
        if (!prop.matches(VALID_KEY)) return ""
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java)
            get.invoke(null, prop) as? String ?: ""
        } catch (e: Exception) {
            L.w { "[SystemProps] get failed prop=$prop error=${e.javaClass.simpleName}" }
            ""
        }
    }
}
