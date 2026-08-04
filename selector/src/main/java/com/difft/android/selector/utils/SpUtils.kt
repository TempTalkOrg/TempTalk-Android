package com.difft.android.selector.utils

import android.content.Context
import android.content.SharedPreferences

import com.difft.android.selector.config.PictureConfig

object SpUtils {
    private var pictureSpUtils: SharedPreferences? = null

    private fun getSp(context: Context): SharedPreferences {
        var sp = pictureSpUtils
        if (sp == null) {
            sp = context.getSharedPreferences(PictureConfig.SP_NAME, Context.MODE_PRIVATE)
            pictureSpUtils = sp
        }
        return sp
    }

    @JvmStatic
    fun putString(context: Context, key: String, value: String) {
        getSp(context).edit().putString(key, value).apply()
    }

    @JvmStatic
    fun putBoolean(context: Context, key: String, value: Boolean) {
        getSp(context).edit().putBoolean(key, value).apply()
    }

    @JvmStatic
    fun contains(context: Context, key: String): Boolean {
        return getSp(context).contains(key)
    }
}
