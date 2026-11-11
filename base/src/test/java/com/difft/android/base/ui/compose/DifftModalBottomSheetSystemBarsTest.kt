package com.difft.android.base.ui.compose

import android.app.Application
import android.view.Window
import android.view.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.StatusBarSetting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DifftModalBottomSheetSystemBarsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var sheetWindow: Window? = null

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("status_bar_prefs", 0).edit().clear().commit()
        StatusBarSetting.resetForTest()
    }

    @After
    fun tearDown() {
        StatusBarSetting.resetForTest()
        context.getSharedPreferences("status_bar_prefs", 0).edit().clear().commit()
    }

    @Test
    fun `hidden preference hides only the status bar and updates an open sheet`() {
        showSheet(hideSystemBars = false)
        val window = requireNotNull(sheetWindow)
        assertTrue(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        composeTestRule.runOnIdle { StatusBarSetting.setHidden(context, true) }
        composeTestRule.waitForIdle()
        assertFalse(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        assertTrue(window.isRequestedVisible(WindowInsets.Type.navigationBars()))

        composeTestRule.runOnIdle { StatusBarSetting.setHidden(context, false) }
        composeTestRule.waitForIdle()

        assertTrue(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        assertTrue(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
    }

    @Test
    fun `sheet opened with hidden preference hides only the status bar`() {
        StatusBarSetting.setHidden(context, true)
        showSheet(hideSystemBars = false)
        val window = requireNotNull(sheetWindow)
        assertFalse(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        assertTrue(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
    }

    @Test
    fun `fully immersive sheet ignores preference changes`() {
        StatusBarSetting.setHidden(context, true)
        showSheet(hideSystemBars = true)
        val window = requireNotNull(sheetWindow)
        composeTestRule.runOnIdle { StatusBarSetting.setHidden(context, false) }
        composeTestRule.waitForIdle()

        assertFalse(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        assertFalse(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
    }

    @Test
    fun `leaving fully immersive mode retains a hidden status bar preference`() {
        StatusBarSetting.setHidden(context, true)
        var immersive by mutableStateOf(false)
        composeTestRule.setContent {
            DifftTheme { Sheet(hideSystemBars = immersive, hideNavigationBar = true) }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { immersive = true }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { immersive = false }
        composeTestRule.waitForIdle()

        val window = requireNotNull(sheetWindow)
        assertFalse(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        assertFalse(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
    }

    @Test
    fun `hideSystemBars true hides the status and navigation bars on the sheet window`() {
        showSheet(hideSystemBars = true)

        val window = requireNotNull(sheetWindow)
        assertFalse(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        assertFalse(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
        
        
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior,
        )
    }

    @Test
    fun `hideSystemBars false leaves the system bars visible on the sheet window`() {
        showSheet(hideSystemBars = false)

        val window = requireNotNull(sheetWindow)
        assertTrue(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        assertTrue(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
    }

    @Test
    fun `turning hideSystemBars off while open shows the status bar and keeps the navigation bar hidden`() {
        var hide by mutableStateOf(true)
        composeTestRule.setContent {
            DifftTheme { Sheet(hideSystemBars = hide, hideNavigationBar = true) }
        }
        composeTestRule.waitForIdle()
        val window = requireNotNull(sheetWindow)
        assertFalse(window.isRequestedVisible(WindowInsets.Type.statusBars()))

        hide = false
        composeTestRule.waitForIdle()

        assertTrue(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        assertFalse(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
    }

    private fun showSheet(hideSystemBars: Boolean) {
        composeTestRule.setContent {
            DifftTheme { Sheet(hideSystemBars = hideSystemBars, hideNavigationBar = false) }
        }
        composeTestRule.waitForIdle()
    }

    @Composable
    private fun Sheet(hideSystemBars: Boolean, hideNavigationBar: Boolean) {
        DifftModalBottomSheet(
            onDismissRequest = {},
            hideSystemBars = hideSystemBars,
            hideNavigationBar = hideNavigationBar,
        ) {
            sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            Box(modifier = Modifier.height(120.dp))
        }
    }
}


internal fun Window.isRequestedVisible(type: Int): Boolean {
    val controller = requireNotNull(insetsController)
    val requested = controller.javaClass.getMethod("getRequestedVisibleTypes").invoke(controller) as Int
    return requested and type != 0
}
