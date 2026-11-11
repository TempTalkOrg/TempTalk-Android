package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow










class PersistedDualPaneFlag(
    private val tag: String,
    private val read: (UserData) -> Boolean,
    private val write: UserData.(Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _state.asStateFlow()

    val value: Boolean
        get() = _state.value

    
    fun loadAndEmit() {
        _state.value = try {
            globalServices.userManager.getUserData()?.let(read) ?: false
        } catch (e: Exception) {
            L.w { "[$tag] load failed: ${e.stackTraceToString()}" }
            false
        }
    }

    
    fun set(value: Boolean) {
        if (_state.value == value) return
        try {
            globalServices.userManager.update { write(value) }
        } catch (e: Exception) {
            L.w { "[$tag] persist failed: ${e.stackTraceToString()}" }
        }
        _state.value = value
    }
}
