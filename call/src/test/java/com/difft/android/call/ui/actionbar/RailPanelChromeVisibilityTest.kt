package com.difft.android.call.ui.actionbar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import com.difft.android.call.core.CallUiController
import com.difft.android.call.service.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Beside the side rail an open sheet (More, Emoji, End menu) neither fades the chrome nor frees the
 * rail's band; with the bar it fades the chrome as before (#1451, #1196). The window is
 * driven through [LocalWindowInfo], so one composition can switch between rail and bar mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [30], qualifiers = "w860dp-h608dp-land-xhdpi")
class RailPanelChromeVisibilityTest {

    @get:Rule
    val rule = createComposeRule()

    private val ui = CallUiController()

    // xhdpi: 860x608dp shows the rail, 860x400dp is a wide window below the rail's minimum height.
    private val railWindow = IntSize(1720, 1216)
    private val barWindow = IntSize(1720, 800)
    private var window by mutableStateOf(railWindow)

    private var rail: SideRail? = null
    private var topVisible = false
    private var bottomVisible = false
    private var bottomAlpha = -1f
    private var railBand = false

    private val windowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean = true
        override val containerSize: IntSize get() = window
    }

    private fun setChrome() {
        rule.setContent {
            CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
                val chrome = rememberCallChromeLayout(isGroup = true)
                rail = chrome.rail
                val top by rememberChromeVisible(ui, ui.showTopStatusViewEnabled)
                val bottom by rememberChromeVisible(ui, ui.showBottomToolBarViewEnabled)
                topVisible = top
                bottomVisible = bottom
                val alpha = rememberChromeAlpha(ui, ui.showBottomToolBarViewEnabled)
                bottomAlpha = alpha.value
                railBand = rememberRailBandReserved(ui, chrome.rail)
            }
        }
        rule.waitForIdle()
    }

    private fun assertChromeShown(label: String) {
        assertTrue(label, topVisible)
        assertTrue(label, bottomVisible)
        assertEquals(label, 1f, bottomAlpha, 0f)
    }

    private val panels: List<Pair<String, (Boolean) -> Unit>> = listOf(
        "more" to ui::setShowToolBarBottomViewEnable,
        "emoji" to ui::setShowSimpleBarrageEnabled,
        "end" to ui::setShowBottomCallEndViewEnable,
    )

    @Test
    fun `rail - an open panel keeps the chrome and the rail's band`() {
        setChrome()
        assertNotNull(rail)
        for ((name, open) in panels) {
            open(true)
            rule.waitForIdle()
            assertChromeShown(name)
            assertTrue(name, railBand)
            open(false)
            rule.waitForIdle()
        }
        // The user's toggle still hides the chrome and frees the rail's band.
        ui.toggleOverlays()
        rule.waitForIdle()
        assertFalse(topVisible)
        assertFalse(bottomVisible)
        assertFalse(railBand)
    }

    @Test
    fun `bar - an open panel fades the chrome as before`() {
        window = barWindow
        setChrome()
        assertNull(rail)
        for ((name, open) in panels) {
            open(true)
            rule.waitForIdle()
            assertFalse(name, topVisible)
            assertFalse(name, bottomVisible)
            assertEquals(name, 0f, bottomAlpha, 0f)
            open(false)
            rule.waitForIdle()
            assertChromeShown(name)
        }
    }

    @Test
    fun `a rail - bar switch with a panel open follows the current mode`() {
        setChrome()
        ui.setShowToolBarBottomViewEnable(true)
        rule.waitForIdle()
        assertChromeShown("rail")
        window = barWindow
        rule.waitForIdle()
        assertNull(rail)
        assertFalse(topVisible)
        assertFalse(bottomVisible)
        assertEquals(0f, bottomAlpha, 0f)
        window = railWindow
        rule.waitForIdle()
        assertNotNull(rail)
        assertChromeShown("rail again")
        ui.setShowToolBarBottomViewEnable(false)
        rule.waitForIdle()
        assertChromeShown("closed")
    }
}
