package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object TextSizeUtil {

    const val TEXT_SIZE_DEFAULT = 0
    const val TEXT_SIZE_LAGER = 1

    // StateFlow with default value, so new collectors get current value immediately
    private val _textSizeState = MutableStateFlow(TEXT_SIZE_DEFAULT)

    /**
     * StateFlow that emits text size changes.
     * New collectors will immediately receive the current value.
     * Collect in Activity/Fragment scope, not in individual views.
     */
    val textSizeState: StateFlow<Int> = _textSizeState.asStateFlow()

    /**
     * Get current text size value synchronously (safe to call, returns cached value).
     * Use this in adapters and views instead of collecting the flow.
     */
    val currentTextSize: Int
        get() = _textSizeState.value

    /**
     * Check if current text size is larger.
     * Use this in adapters and views instead of collecting the flow.
     */
    val isLarger: Boolean
        get() = _textSizeState.value == TEXT_SIZE_LAGER

    /**
     * Load text size from UserManager and emit to all collectors.
     * Should be called during app startup in a background thread.
     * This avoids ANR by centralizing UserManager access.
     */
    fun loadAndEmitTextSize() {
        appScope.launch(Dispatchers.IO) {
            try {
                val textSize = globalServices.userManager.getUserData()?.textSize ?: TEXT_SIZE_DEFAULT
                _textSizeState.value = textSize
            } catch (e: Exception) {
                // On error, emit default value
                L.w { "[TextSizeUtil] error: ${e.stackTraceToString()}" }
                _textSizeState.value = TEXT_SIZE_DEFAULT
            }
        }
    }

    /**
     * Update text size setting and notify all collectors.
     */
    fun updateTextSize(textSize: Int) {
        globalServices.userManager.update {
            this.textSize = textSize
        }
        _textSizeState.value = textSize
    }
}

