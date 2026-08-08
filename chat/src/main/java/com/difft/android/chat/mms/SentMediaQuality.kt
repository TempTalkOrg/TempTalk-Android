package com.difft.android.chat.mms

import android.content.Context
import androidx.annotation.StringRes
import com.difft.android.chat.R

/**
 * Quality levels to send media at.
 */
enum class SentMediaQuality(val code: Int, @param:StringRes private val label: Int) {
    STANDARD(0, R.string.DataAndStorageSettingsFragment__standard),
    HIGH(1, R.string.DataAndStorageSettingsFragment__high);

    companion object {
        @JvmStatic
        fun fromCode(code: Int): SentMediaQuality = if (HIGH.code == code) HIGH else STANDARD

        @JvmStatic
        fun getLabels(context: Context): Array<String> =
            values().map { context.getString(it.label) }.toTypedArray()
    }
}
