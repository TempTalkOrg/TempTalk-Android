package com.difft.android.base.utils

import kotlinx.coroutines.flow.StateFlow

/**
 * Per-user persistence + StateFlow surface for the dual-pane list-collapse state.
 *
 * Collapsed means the conversation list pane is hidden and the detail pane takes the whole
 * width; the user enters it by dragging the pane divider past the list minimum and leaves it
 * by dragging back out, tapping the divider handle, or tapping the Chats rail tab. The state
 * survives recreation (rotate / fold) and is simply ignored while the window is single-pane.
 * Storage and load timing: see [PersistedDualPaneFlag].
 */
object DualPaneCollapseUtil {

    private val flag = PersistedDualPaneFlag(
        tag = "DualPaneCollapse",
        read = { it.dualPaneListCollapsed },
        write = { dualPaneListCollapsed = it },
    )

    val collapsedState: StateFlow<Boolean>
        get() = flag.state

    val isCollapsed: Boolean
        get() = flag.value

    /** Load the persisted state from UserData and emit. Called once during Activity onCreate. */
    fun loadAndEmit() = flag.loadAndEmit()

    /** Persist and emit. Called when a collapse/expand gesture or tap completes. */
    fun setCollapsed(collapsed: Boolean) = flag.set(collapsed)
}
