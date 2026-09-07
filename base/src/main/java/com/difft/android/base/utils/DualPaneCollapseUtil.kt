package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-user persistence + StateFlow surface for the dual-pane list-collapse state.
 * Mirrors the [DualPaneRatioUtil] pattern (same store, same load timing) — the two are
 * halves of one pane-geometry preference and must not live in different storages.
 *
 * Collapsed means the conversation list pane is hidden and the detail pane takes the whole
 * width; the user enters it by dragging the pane divider past the list minimum and leaves it
 * by dragging back out, tapping the divider handle, or tapping the Chats rail tab. The state
 * survives recreation (rotate / fold) and is simply ignored while the window is single-pane.
 */
object DualPaneCollapseUtil {

    private val _collapsedState = MutableStateFlow(false)
    val collapsedState: StateFlow<Boolean> = _collapsedState.asStateFlow()

    val isCollapsed: Boolean
        get() = _collapsedState.value

    /**
     * Load the persisted state from UserData and emit. Called once during Activity onCreate,
     * synchronously — UserData is preloaded in memory, so this is a snapshot lookup and the
     * first applyListPaneWidth() sees the persisted state (no expanded-then-collapse flash).
     */
    fun loadAndEmit() {
        try {
            _collapsedState.value =
                globalServices.userManager.getUserData()?.dualPaneListCollapsed ?: false
        } catch (e: Exception) {
            L.w { "[DualPaneCollapse] load failed: ${e.stackTraceToString()}" }
            _collapsedState.value = false
        }
    }

    /** Persist and emit. Called when a collapse/expand gesture or tap completes. */
    fun setCollapsed(collapsed: Boolean) {
        if (_collapsedState.value == collapsed) return
        try {
            globalServices.userManager.update { this.dualPaneListCollapsed = collapsed }
        } catch (e: Exception) {
            L.w { "[DualPaneCollapse] persist failed: ${e.stackTraceToString()}" }
        }
        _collapsedState.value = collapsed
    }
}
