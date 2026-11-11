package com.difft.android.call.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





class CallUiControllerPanelOnHideTest {

    private val ui = CallUiController()

    private fun openPanel() {
        ui.setShowUsersEnabled(true)
        assertTrue(ui.showUsersEnabled.value)
    }

    @Test
    fun `tap-to-hide closes the panel`() {
        openPanel()
        ui.toggleOverlays()
        assertFalse(ui.showBottomToolBarViewEnabled.value)
        assertFalse(ui.showUsersEnabled.value)
    }

    @Test
    fun `tap-to-show leaves the panel as it is`() {
        ui.toggleOverlays()
        openPanel()
        ui.toggleOverlays()
        assertTrue(ui.showBottomToolBarViewEnabled.value)
        assertTrue(ui.showUsersEnabled.value)
    }

    @Test
    fun `a tap that only closes the barrage keeps the chrome and the panel`() {
        openPanel()
        ui.setShowSimpleBarrageEnabled(true)
        ui.toggleOverlays()
        assertFalse(ui.showSimpleBarrageEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
        assertTrue(ui.showUsersEnabled.value)
    }

    @Test
    fun `toggleTopBottomBars closes the panel when it hides the bars`() {
        openPanel()
        ui.toggleTopBottomBars()
        assertFalse(ui.showUsersEnabled.value)
        openPanel()
        ui.toggleTopBottomBars()
        assertTrue(ui.showUsersEnabled.value)
    }

    @Test
    fun `screen-share auto-hide hides both bars and closes the panel`() {
        openPanel()
        ui.autoHideChrome()
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertFalse(ui.showBottomToolBarViewEnabled.value)
        assertFalse(ui.showUsersEnabled.value)
    }

    @Test
    fun `opening the panel or its mute menu does not hide the chrome`() {
        openPanel()
        ui.setParticipantMenuOpen(true)
        assertTrue(ui.showTopStatusViewEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
        ui.setParticipantMenuOpen(false)
        assertTrue(ui.showUsersEnabled.value)
    }

    @Test
    fun `the group end menu hiding the bar leaves the panel open`() {
        openPanel()
        
        ui.setShowBottomToolBarViewEnabled(false)
        assertTrue(ui.showUsersEnabled.value)
    }
}
