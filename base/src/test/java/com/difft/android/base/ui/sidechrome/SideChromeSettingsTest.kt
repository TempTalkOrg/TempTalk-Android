package com.difft.android.base.ui.sidechrome

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SideChromeSettingsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("side_chrome_prefs", 0).edit().clear().commit()
        SideChromeSettings.resetForTest()
    }

    @After
    fun tearDown() = SideChromeSettings.resetForTest()

    @Test
    fun `with nothing stored the navigation is on the right`() {
        assertEquals(NavigationPlacement.RIGHT, SideChromeSettings.placement(context))
    }

    @Test
    fun `a stored choice of the bottom survives a fresh read`() {
        SideChromeSettings.setPlacement(context, NavigationPlacement.DEFAULT)
        SideChromeSettings.resetForTest()

        assertEquals(NavigationPlacement.DEFAULT, SideChromeSettings.placement(context))
    }
}
