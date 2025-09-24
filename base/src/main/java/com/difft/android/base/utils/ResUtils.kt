package com.difft.android.base.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import androidx.annotation.IntegerRes
import java.util.Locale

object ResUtils {
    @JvmStatic
    fun getString(id: Int): String {
        return application.resources.getString(id)
    }

    @JvmStatic
    fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String {
        return application.resources.getQuantityString(id, quantity, *formatArgs)
    }

    @JvmStatic
    fun getString(id: Int, vararg formatArgs: Any?): String {
        return application.getString(id, *formatArgs)
    }

//    @JvmStatic
//    @ColorInt
//    fun getColor(id: Int): Int {
//        return com.difft.android.base.utils.application.resources.getColor(id)
//    }

//    @JvmStatic
//    @ColorInt
//    fun getColor(context: Context, id: Int): Int {
//        return ContextCompat.getColor(context, id)
//    }

    fun getDrawable(id: Int): Drawable {
        return application.resources.getDrawable(id)
    }

    fun getDimenPx(resId: Int): Int {
        return application.resources.getDimensionPixelSize(resId)
    }

    fun getColorStateList(id: Int): ColorStateList {
        return application.resources.getColorStateList(id)
    }

    fun getIdentifier(name: String?, defType: String?): Int {
        return application.resources.getIdentifier(
            name, defType,
            application.applicationContext.packageName
        )
    }

    fun getInteger(@IntegerRes id: Int): Int {
        return application.resources.getInteger(id)
    }

    fun getStringArray(id: Int): Array<String> {
        return application.resources.getStringArray(id)
    }

    /**
     * 根据资源 id 获取资源名
     *
     * @param id
     * @return
     */
    fun getResourceEntryName(id: Int): String {
        return application.resources.getResourceEntryName(id)
    }

    fun getLocaleStringResource(context: Context, locale: Locale, id: Int): String {
        val config = context.resources.configuration
        config.setLocale(locale)
        return context.createConfigurationContext(config).getText(id).toString()
    }
}