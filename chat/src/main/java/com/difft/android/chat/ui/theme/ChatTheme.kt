package com.difft.android.chat.ui.theme

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.ui.ChatBackgroundDrawable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow






enum class ChatTheme {
    CLASSIC,
    GRADIENT;

    companion object {
        fun fromStored(value: Int): ChatTheme = entries.getOrNull(value) ?: CLASSIC
    }
}






object ChatThemeSetting {

    private val state: MutableStateFlow<ChatTheme> by lazy { MutableStateFlow(readStored()) }

    
    val theme: StateFlow<ChatTheme> by lazy { state.asStateFlow() }

    val current: ChatTheme get() = state.value

    fun loadAndEmit() {
        state.value = readStored()
    }

    
    fun update(theme: ChatTheme) {
        try {
            globalServices.userManager.update { chatTheme = theme.ordinal }
        } catch (e: Exception) {
            L.w { "[ChatTheme] persist failed: ${e.stackTraceToString()}" }
        }
        state.value = theme
        ChatBackgroundDrawable.themeChanged()
        L.i { "[ChatTheme] set theme=$theme" }
    }

    private fun readStored(): ChatTheme = try {
        ChatTheme.fromStored(globalServices.userManager.getUserData()?.chatTheme ?: 0)
    } catch (e: Exception) {
        L.w { "[ChatTheme] load failed: ${e.stackTraceToString()}" }
        ChatTheme.CLASSIC
    }
}
