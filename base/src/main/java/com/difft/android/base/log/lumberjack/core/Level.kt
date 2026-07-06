package com.difft.android.base.log.lumberjack.core

import android.graphics.Color
import androidx.core.graphics.toColorInt
import com.difft.android.base.log.lumberjack.core.CoreUtil.isColorDark

enum class Level(
    val level: Int,
    private val color: Int? = null,
    private val colorDark: Int? = color,
    private val marker: String? = null
) {

    TRACE(0),
    DEBUG(1),
    INFO(2, Color.BLUE, "#ADD8E6".toColorInt() /* light blue */),
    WARN(3, "#FFA500".toColorInt() /* orange */),
    ERROR(4, Color.RED),
    WTF(5, Color.RED, marker = "WTF-ERROR"),
    UNKNOWN(-1, android.R.color.transparent)
    ;

    fun getTitleColor(textColor: Int): Int {
        return (if (textColor.isColorDark()) color else colorDark) ?: textColor
    }

    fun getTextColor(textColor: Int): Int {
        val c = getTitleColor(textColor)
        return if (c == android.R.color.transparent)
            textColor
        else c
    }

    fun getFileMarker() = marker ?: name
}