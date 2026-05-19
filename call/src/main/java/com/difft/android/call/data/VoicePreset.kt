package com.difft.android.call.data

import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R

enum class VoicePreset(
    val sdkKey: String,
    val emoji: String,
    val displayNameResId: Int
) {
    ORIGINAL("original", "", R.string.call_voice_preset_original),
    GODDESS("goddess", "🐿️", R.string.call_voice_preset_higher),
    UNCLE("uncle", "🐻", R.string.call_voice_preset_deeper);
    val isEnabled: Boolean get() = this != ORIGINAL

    fun displayText(): String {
        val name = ResUtils.getString(displayNameResId)
        return if (emoji.isEmpty()) name else "$emoji $name"
    }

    companion object {
        fun fromSdkKey(key: String): VoicePreset =
            entries.firstOrNull { it.sdkKey == key } ?: ORIGINAL
    }
}
