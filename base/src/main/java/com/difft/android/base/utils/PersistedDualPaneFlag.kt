package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One per-user persisted boolean of the dual-pane geometry (list pane collapsed, navigation
 * rail collapsed) with a StateFlow surface for the UI.
 *
 * Same store and same load timing as [DualPaneRatioUtil]: UserData is preloaded in memory, so
 * [loadAndEmit] is a synchronous snapshot lookup and the first layout pass already sees the
 * persisted value (no expanded-then-collapse flash). All dual-pane geometry flags must live in
 * this one store — they are halves of a single pane preference.
 */
class PersistedDualPaneFlag(
    private val tag: String,
    private val read: (UserData) -> Boolean,
    private val write: UserData.(Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _state.asStateFlow()

    val value: Boolean
        get() = _state.value

    /** Load the persisted value from UserData and emit. Call once during Activity onCreate. */
    fun loadAndEmit() {
        _state.value = try {
            globalServices.userManager.getUserData()?.let(read) ?: false
        } catch (e: Exception) {
            L.w { "[$tag] load failed: ${e.stackTraceToString()}" }
            false
        }
    }

    /** Persist and emit; a no-op when the value is unchanged. */
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
