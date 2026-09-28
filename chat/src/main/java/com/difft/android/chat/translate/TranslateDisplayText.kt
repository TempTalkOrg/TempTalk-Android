package com.difft.android.chat.translate

import android.content.Context
import androidx.annotation.StringRes
import com.difft.android.chat.R

/**
 * Display text for the conversation auto-translate setting, shared by the two entry rows
 * (1:1 settings, group info), the settings screen itself and the local system message.
 *
 * Keeping the mapping here is what lets the entry rows render the value without injecting the
 * model store or duplicating the `when` in two Activities.
 */

/** Option label: "Off" / "中文" / "English". Unknown values (written by a newer build) read as off. */
@StringRes
fun translateValueLabelRes(value: Int): Int = when (value) {
    1 -> R.string.chat_translate_setting_zh
    2 -> R.string.chat_translate_setting_en
    else -> R.string.chat_translate_setting_off
}

/** Explanation shown under the options, and reused verbatim as the system-message text. */
@StringRes
fun translateValueDescRes(value: Int): Int = when (value) {
    1 -> R.string.chat_translate_setting_desc_zh
    2 -> R.string.chat_translate_setting_desc_en
    else -> R.string.chat_translate_setting_desc_off
}

/** Value text for a settings entry row: the option label, nothing else. */
fun translateEntryValue(context: Context, value: Int): String =
    context.getString(translateValueLabelRes(value))
