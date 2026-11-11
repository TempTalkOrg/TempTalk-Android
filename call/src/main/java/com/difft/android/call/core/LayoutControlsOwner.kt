package com.difft.android.call.core

import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.ControllerState
import com.difft.android.call.layout.Posture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map


internal class LayoutControlsOwner(private val layout: CallLayoutController) : CallControlsOwner {
    
    override fun toggleControls(): Boolean {
        if (layout.state.value.plan == null) return false
        layout.toggleControls()
        return true
    }

    override fun setControlsHidden(hidden: Boolean) = layout.setControlsHidden(hidden)
}


internal data class LayoutControls(val hidden: Boolean, val docked: Boolean, val laidOut: Boolean)





internal fun Flow<ControllerState>.layoutControls(): Flow<LayoutControls> =
    map { LayoutControls(it.controlsHidden, it.plan?.posture == Posture.TABLETOP, it.plan != null) }.distinctUntilChanged()
