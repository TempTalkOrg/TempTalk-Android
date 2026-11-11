package com.difft.android.setting.theme

import android.app.Activity
import com.difft.android.base.ui.sidechrome.NavigationPlacement
import com.difft.android.base.ui.sidechrome.SideChrome
import com.difft.android.base.utils.StatusBarSetting
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.density.DisplayDensityNormalizer
import com.difft.android.base.utils.density.DisplaySize
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
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
class AppearanceActionsTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()
    private val other: Activity = Robolectric.buildActivity(Activity::class.java).create().get()

    @Before
    fun setUp() {
        mockkObject(TextSizeUtil, DisplayDensityNormalizer, SideChrome, StatusBarSetting)
        every { TextSizeUtil.updateTextSize(any()) } just Runs
        every { DisplayDensityNormalizer.setDisplaySize(any(), any()) } just Runs
        every { DisplayDensityNormalizer.recreateManagedActivities() } returns listOf(activity)
        every { SideChrome.applyPlacement(any(), any()) } returns listOf(activity)
        every { StatusBarSetting.setHidden(any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `large text is index one and default is index zero`() {
        val actions = AppearanceActions(activity)

        actions.setLargeText(true)
        actions.setLargeText(false)

        verifyOrder {
            TextSizeUtil.updateTextSize(TextSizeUtil.TEXT_SIZE_LAGER)
            TextSizeUtil.updateTextSize(TextSizeUtil.TEXT_SIZE_DEFAULT)
        }
    }

    @Test
    fun `display size is stored before the windows are recreated`() {
        assertTrue(AppearanceActions(activity).setDisplaySize(DisplaySize.LARGE))

        verifyOrder {
            DisplayDensityNormalizer.setDisplaySize(activity, DisplaySize.LARGE)
            DisplayDensityNormalizer.recreateManagedActivities()
        }
    }

    @Test
    fun `navigation position goes through the side chrome and the status bar applies at once`() {
        val actions = AppearanceActions(activity)

        assertTrue(actions.setNavigationPlacement(NavigationPlacement.DEFAULT))
        actions.setStatusBarHidden(true)

        verify(exactly = 1) { SideChrome.applyPlacement(activity, NavigationPlacement.DEFAULT) }
        verify(exactly = 1) { StatusBarSetting.setHidden(activity, true) }
    }

    @Test
    fun `once its window is being recreated further recreating changes are ignored`() {
        val actions = AppearanceActions(activity)

        assertTrue(actions.setDisplaySize(DisplaySize.LARGE))
        assertFalse(actions.setDisplaySize(DisplaySize.SMALL))
        assertFalse(actions.setNavigationPlacement(NavigationPlacement.DEFAULT))

        verify(exactly = 1) { DisplayDensityNormalizer.setDisplaySize(any(), any()) }
        verify(exactly = 1) { DisplayDensityNormalizer.recreateManagedActivities() }
        verify(exactly = 0) { SideChrome.applyPlacement(any(), any()) }
    }

    @Test
    fun `a change that did not recreate its window holds nothing back`() {
        every { DisplayDensityNormalizer.recreateManagedActivities() } returns listOf(other)
        every { SideChrome.applyPlacement(any(), any()) } returns emptyList()
        val actions = AppearanceActions(activity)

        assertTrue(actions.setDisplaySize(DisplaySize.LARGE))
        assertTrue(actions.setNavigationPlacement(NavigationPlacement.DEFAULT))
        assertTrue(actions.setDisplaySize(DisplaySize.SMALL))

        verify(exactly = 2) { DisplayDensityNormalizer.setDisplaySize(any(), any()) }
        verify(exactly = 1) { SideChrome.applyPlacement(activity, NavigationPlacement.DEFAULT) }
    }
}
