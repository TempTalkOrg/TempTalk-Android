package com.difft.android.call.core

import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.ControllerState
import com.difft.android.call.layout.Posture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The layout controller as the owner of the controls' visibility (design §4.4). */
internal class LayoutControlsOwner(private val layout: CallLayoutController) : CallControlsOwner {
    /** Before the first plan the controller has nothing to hide, and publishes nothing. */
    override fun toggleControls(): Boolean {
        if (layout.state.value.plan == null) return false
        layout.toggleControls()
        return true
    }

    override fun setControlsHidden(hidden: Boolean) = layout.setControlsHidden(hidden)
}

/** What the page mirrors from a layout state into its bars; [laidOut] false while there is no plan. */
internal data class LayoutControls(val hidden: Boolean, val docked: Boolean, val laidOut: Boolean)

/**
 * The controls state of each layout state, on change. A plan coming back (after none, or an empty
 * list) is a change even with the same hidden/docked pair, so bars toggled meanwhile are re-synced.
 */
internal fun Flow<ControllerState>.layoutControls(): Flow<LayoutControls> =
    map { LayoutControls(it.controlsHidden, it.plan?.posture == Posture.TABLETOP, it.plan != null) }.distinctUntilChanged()
