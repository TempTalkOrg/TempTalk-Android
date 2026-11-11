package com.difft.android.chat.translate

import android.content.Context
import androidx.annotation.StringRes
import com.difft.android.chat.R










@StringRes
fun translateValueLabelRes(value: Int): Int = when (value) {
    1 -> R.string.chat_translate_setting_zh
    2 -> R.string.chat_translate_setting_en
    else -> R.string.chat_translate_setting_off
}


@StringRes
fun translateValueDescRes(value: Int): Int = when (value) {
    1 -> R.string.chat_translate_setting_desc_zh
    2 -> R.string.chat_translate_setting_desc_en
    else -> R.string.chat_translate_setting_desc_off
}


fun translateEntryValue(context: Context, value: Int): String =
    context.getString(translateValueLabelRes(value))
