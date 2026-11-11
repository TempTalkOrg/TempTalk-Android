package com.difft.android.call.core

import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.CallMember
import com.difft.android.call.layout.ControllerState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import com.difft.android.call.layout.Insets
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.Rect
import com.difft.android.call.layout.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test





class CallControlsOwnershipTest {

    private val layout = CallLayoutController(now = { 0.0 })
    private val ui = CallUiController()

    
    private fun mirror() {
        val state = layout.state.value
        ui.applyLayoutControls(state.controlsHidden, state.plan?.posture == Posture.TABLETOP)
    }

    @Before
    fun setUp() {
        ui.setControlsOwner(LayoutControlsOwner(layout))
        layout.setContent(Rect(12.0, 104.0, 387.0, 643.0), Rect(12.0, 56.0, 387.0, 835.0))
        layout.setWindow(Size(411.0, 923.0), Insets(top = 48.0, bottom = 24.0), null)
        layout.setParticipants(listOf(CallMember("me", isLocal = true, cameraOn = false), CallMember("a", isLocal = false, cameraOn = false)))
        mirror()
    }

    @Test
    fun `a tap hides and shows both bars through the controller`() {
        ui.toggleOverlays()
        assertTrue(layout.state.value.controlsHidden)
        mirror()
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertFalse(ui.showBottomToolBarViewEnabled.value)

        ui.toggleOverlays()
        mirror()
        assertFalse(layout.state.value.controlsHidden)
        assertTrue(ui.showTopStatusViewEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
    }

    @Test
    fun `hiding closes the participants panel`() {
        ui.setShowUsersEnabled(true)
        ui.toggleOverlays()
        mirror()
        assertFalse(ui.showUsersEnabled.value)
    }

    @Test
    fun `a panel opening reveals the controls`() {
        ui.toggleOverlays()
        mirror()
        ui.setShowToolBarBottomViewEnable(true)
        assertFalse(layout.state.value.controlsHidden)
        mirror()
        assertTrue(ui.showTopStatusViewEnabled.value)

        ui.setShowToolBarBottomViewEnable(false)
        ui.toggleOverlays()
        mirror()
        ui.setShowUsersEnabled(true)
        assertFalse(layout.state.value.controlsHidden)
    }

    @Test
    fun `before the controller has laid anything out a tap toggles the bars themselves`() {
        val early = CallUiController().apply { setControlsOwner(LayoutControlsOwner(CallLayoutController(now = { 0.0 }))) }
        early.toggleOverlays()
        assertFalse(early.showTopStatusViewEnabled.value)
        assertFalse(early.showBottomToolBarViewEnabled.value)
    }

    @Test
    fun `a plan coming back re-syncs the bars even with the same controls state`() = runTest {
        val laidOut = layout.state.value
        val states = flowOf(laidOut, ControllerState(), laidOut).layoutControls().toList()
        assertEquals(
            listOf(LayoutControls(false, false, true), LayoutControls(false, false, false), LayoutControls(false, false, true)),
            states,
        )
    }

    @Test
    fun `a first tap closes an open barrage instead`() {
        ui.setShowSimpleBarrageEnabled(true)
        ui.toggleOverlays()
        assertFalse(ui.showSimpleBarrageEnabled.value)
        assertFalse(layout.state.value.controlsHidden)
    }

    @Test
    fun `the tabletop posture keeps the toolbar while the title hides`() {
        val window = Size(673.0, 841.0)
        layout.setWindow(window, Insets(top = 32.0), Rect(0.0, 400.0, window.w, 1.0))
        layout.setContent(Rect(12.0, 100.0, 649.0, 600.0), Rect(12.0, 40.0, 649.0, 660.0))
        assertEquals(Posture.TABLETOP, layout.state.value.plan?.posture)
        mirror()
        assertTrue(ui.dockPinned.value)

        ui.toggleOverlays()
        mirror()
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
    }
}
