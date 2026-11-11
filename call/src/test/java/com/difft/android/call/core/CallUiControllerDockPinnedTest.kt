package com.difft.android.call.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test






class CallUiControllerDockPinnedTest {

    private val ui = CallUiController()

    @Test
    fun `pinning shows the bottom bar`() {
        ui.toggleOverlays()
        assertFalse(ui.showBottomToolBarViewEnabled.value)
        ui.applyLayoutControls(hidden = false, docked = true)
        assertTrue(ui.dockPinned.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
    }

    @Test
    fun `opening a panel while pinned leaves hidden controls hidden`() {
        var hiddenNow = true
        ui.setControlsOwner(object : CallControlsOwner {
            override fun toggleControls(): Boolean = true
            override fun setControlsHidden(hidden: Boolean) {
                hiddenNow = hidden
            }
        })
        ui.applyLayoutControls(hidden = true, docked = true)
        ui.setShowUsersEnabled(true)
        ui.setShowInviteViewEnable(true)
        ui.setShowToolBarBottomViewEnable(true)
        assertTrue("panels opened from the console keep the stage enlarged", hiddenNow)
        ui.applyLayoutControls(hidden = true, docked = false)
        ui.setShowUsersEnabled(true)
        assertFalse("flat: a panel brings the controls back", hiddenNow)
    }

    @Test
    fun `a pinned tap flips only the title and keeps the panel open`() {
        ui.applyLayoutControls(hidden = false, docked = true)
        ui.setShowUsersEnabled(true)
        ui.toggleOverlays()
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
        assertTrue(ui.showUsersEnabled.value)
        ui.toggleOverlays()
        assertTrue(ui.showTopStatusViewEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
    }

    @Test
    fun `a pinned toolbar toggle flips only the title`() {
        ui.applyLayoutControls(hidden = false, docked = true)
        ui.setShowUsersEnabled(true)
        ui.toggleTopBottomBars()
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
        assertTrue(ui.showUsersEnabled.value)
    }

    @Test
    fun `a pinned tap still closes an open barrage first`() {
        ui.applyLayoutControls(hidden = false, docked = true)
        ui.setShowSimpleBarrageEnabled(true)
        ui.toggleOverlays()
        assertFalse(ui.showSimpleBarrageEnabled.value)
        assertTrue(ui.showTopStatusViewEnabled.value)
    }

    @Test
    fun `pinned auto-hide hides only the title`() {
        ui.applyLayoutControls(hidden = false, docked = true)
        ui.setShowUsersEnabled(true)
        ui.autoHideChrome()
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
        assertTrue(ui.showUsersEnabled.value)
    }

    @Test
    fun `unpinning puts both bars back in step with the controls state`() {
        ui.applyLayoutControls(hidden = true, docked = true)
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertTrue(ui.showBottomToolBarViewEnabled.value)
        ui.applyLayoutControls(hidden = true, docked = false)
        assertFalse(ui.dockPinned.value)
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertFalse(ui.showBottomToolBarViewEnabled.value)
    }

    @Test
    fun `a posture change during a share keeps the bars in step for its own toggle`() {
        val session = com.difft.android.call.session.CallSessionUiState()
        val sharing = CallUiController(session)
        session.setShareScreening(true)
        sharing.applyLayoutControls(hidden = false, docked = true)
        sharing.autoHideChrome()
        assertFalse(sharing.showTopStatusViewEnabled.value)
        assertTrue("docked: the toolbar stays", sharing.showBottomToolBarViewEnabled.value)
        
        sharing.applyLayoutControls(hidden = false, docked = false)
        assertFalse(sharing.showBottomToolBarViewEnabled.value)
        sharing.toggleOverlays()
        assertTrue(sharing.showTopStatusViewEnabled.value)
        assertTrue(sharing.showBottomToolBarViewEnabled.value)
    }

    @Test
    fun `unpinned behaviour is unchanged`() {
        ui.setShowUsersEnabled(true)
        ui.toggleOverlays()
        assertFalse(ui.showTopStatusViewEnabled.value)
        assertFalse(ui.showBottomToolBarViewEnabled.value)
        assertFalse(ui.showUsersEnabled.value)
        ui.autoHideChrome()
        assertFalse(ui.showBottomToolBarViewEnabled.value)
    }
}
