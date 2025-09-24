package com.difft.android.base.utils

import android.content.Context
import com.difft.android.base.user.NewGlobalConfig

interface IGlobalConfigsManager {
    fun getAndSaveGlobalConfigs(context: Context)
    fun getNewGlobalConfigs(): NewGlobalConfig?
    fun updateMostUseEmoji(emoji: String)
    fun getMostUseEmojis(): List<String>
}