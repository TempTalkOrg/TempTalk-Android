package com.difft.android.base.utils

import android.app.Activity
import android.app.Application
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.compose.isRequestedVisible
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusBarSettingTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("status_bar_prefs", 0)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        StatusBarSetting.resetForTest()
    }

    @After
    fun tearDown() {
        StatusBarSetting.resetForTest()
        prefs.edit().clear().commit()
    }

    @Test
    fun `status bar is shown by default`() {
        assertFalse(StatusBarSetting.isHidden(context))
        assertFalse(StatusBarSetting.hidden.value)
    }

    @Test
    fun `setting persists and updates the flow and survives a fresh read`() {
        StatusBarSetting.setHidden(context, true)
        assertTrue(prefs.getBoolean("hidden", false))
        assertTrue(StatusBarSetting.hidden.value)
        StatusBarSetting.resetForTest()
        assertTrue(StatusBarSetting.isHidden(context))
        assertTrue(StatusBarSetting.hidden.value)
    }

    @Test
    fun `attach applies hidden preference without changing navigation visibility`() {
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            val window = activity.get().window
            StatusBarSetting.setHidden(context, true)
            StatusBarSetting.attach(context, window)
            assertFalse(window.isRequestedVisible(WindowInsets.Type.statusBars()))
            assertTrue(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
            assertEquals(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior,
            )
            assertEquals(
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                window.attributes.layoutInDisplayCutoutMode,
            )
        }
    }

    @Test
    fun `attach shows status bar while preserving hidden navigation bar`() {
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            val window = activity.get().window
            window.hideSystemBars()
            StatusBarSetting.attach(context, window)
            assertTrue(window.isRequestedVisible(WindowInsets.Type.statusBars()))
            assertFalse(window.isRequestedVisible(WindowInsets.Type.navigationBars()))
        }
    }

    @Test
    fun `setting reapplies to attached windows until detached`() {
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            val window = activity.get().window
            StatusBarSetting.attach(context, window)
            StatusBarSetting.setHidden(context, true)
            assertFalse(window.isRequestedVisible(WindowInsets.Type.statusBars()))
            StatusBarSetting.setHidden(context, false)
            assertTrue(window.isRequestedVisible(WindowInsets.Type.statusBars()))
            StatusBarSetting.detach(window)
            StatusBarSetting.setHidden(context, true)
            assertTrue(window.isRequestedVisible(WindowInsets.Type.statusBars()))
        }
    }
}
