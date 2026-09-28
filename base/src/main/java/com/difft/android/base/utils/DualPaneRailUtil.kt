package com.difft.android.base.utils

import kotlinx.coroutines.flow.StateFlow

/**
 * Per-user persistence + StateFlow surface for the dual-pane navigation-rail collapse state.
 *
 * Collapsed means the Chats / Contacts / Me rail on the far left is hidden and the detail pane
 * absorbs its width; the list pane keeps its width and its header, whose leading toggle button
 * is the way back (the rail entries do not move into the header — the Mac client's mini-menu
 * is deliberately not mirrored). Independent of the list-pane collapse: both may be collapsed
 * at once. Ignored while the window is single-pane. Storage and load timing: see
 * [PersistedDualPaneFlag].
 */
object DualPaneRailUtil {

    private val flag = PersistedDualPaneFlag(
        tag = "DualPaneRail",
        read = { it.dualPaneRailCollapsed },
        write = { dualPaneRailCollapsed = it },
    )

    val collapsedState: StateFlow<Boolean>
        get() = flag.state

    val isCollapsed: Boolean
        get() = flag.value

    /** Load the persisted state from UserData and emit. Called once during Activity onCreate. */
    fun loadAndEmit() = flag.loadAndEmit()

    /** Persist and emit. Called when the header toggle is tapped. */
    fun setCollapsed(collapsed: Boolean) = flag.set(collapsed)
}
