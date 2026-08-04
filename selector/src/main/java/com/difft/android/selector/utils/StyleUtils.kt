package com.difft.android.selector.utils

import android.content.Context
import android.graphics.ColorFilter
import android.text.TextUtils

import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat

import java.util.regex.Pattern

object StyleUtils {
    private const val INVALID = 0

    @JvmStatic
    fun checkStyleValidity(resource: Int): Boolean {
        return resource != INVALID
    }

    @JvmStatic
    fun checkTextValidity(text: String?): Boolean {
        return !TextUtils.isEmpty(text)
    }

    /** Number of dynamic format specifiers in [text]. */
    @JvmStatic
    fun getFormatCount(text: String): Int {
        val pattern = "%[^%]*\\d"
        val compile = Pattern.compile(pattern)
        val matcher = compile.matcher(text)
        var count = 0
        while (matcher.find()) {
            count++
        }
        return count
    }

    @JvmStatic
    fun checkSizeValidity(size: Int): Boolean {
        return size > INVALID
    }

    @JvmStatic
    fun checkArrayValidity(array: IntArray?): Boolean {
        return array != null && array.isNotEmpty()
    }

    @JvmStatic
    fun getColorFilter(context: Context, color: Int): ColorFilter? {
        return BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
            ContextCompat.getColor(context, color), BlendModeCompat.SRC_ATOP
        )
    }
}
