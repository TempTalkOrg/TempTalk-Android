package com.difft.android.base.utils.density

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.LanguageUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplay
import org.robolectric.shadows.ShadowDisplayManager





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w424dp-h622dp-440dpi")
class DisplayDensityNormalizerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    class EligibleActivity : Activity()
    class HandlesPanelChangesActivity : Activity()

    @Before
    fun setUp() {
        DisplayDensityNormalizer.resetForTest()
        val display = ShadowDisplay.getDefaultDisplay()
        shadowOf(display).setXdpi(385.288f)
        shadowOf(display).setYdpi(385.162f)
        ShadowDisplayManager.setSupportedModes(
            Display.DEFAULT_DISPLAY,
            ShadowDisplayManager.ModeBuilder.modeBuilder(0).setWidth(1168).setHeight(1712).setRefreshRate(60f).build(),
        )
        register(
            EligibleActivity::class.java,
            ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SCREEN_LAYOUT,
        )
        register(HandlesPanelChangesActivity::class.java, ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE)
    }

    @After
    fun tearDown() {
        DisplayDensityNormalizer.resetForTest()
    }

    private fun register(activityClass: Class<*>, configChanges: Int) {
        val info = ActivityInfo().apply {
            name = activityClass.name
            packageName = context.packageName
            this.configChanges = configChanges
        }
        shadowOf(context.packageManager).addOrUpdateActivity(info)
        
        context.packageManager.getActivityInfo(ComponentName(context, activityClass), 0)
    }

    @Test
    fun `on by default`() {
        assertEquals(DisplayDensityNormalizer.Mode.ON, DisplayDensityNormalizer.mode(context))
    }

    @Test
    fun `turned off keeps the system density`() {
        DisplayDensityNormalizer.setMode(context, DisplayDensityNormalizer.Mode.OFF)
        val attach = DisplayDensityNormalizer.attach(context, EligibleActivity::class.java)
        assertFalse(attach.managed)
        assertEquals(Configuration.DENSITY_DPI_UNDEFINED, attach.overrideDpi)
        assertEquals(440, attach.systemDensityDpi)
    }

    @Test
    fun `an eligible activity on a biased panel gets the normalized density`() {
        DisplayDensityNormalizer.setMode(context, DisplayDensityNormalizer.Mode.ON)
        val attach = DisplayDensityNormalizer.attach(context, EligibleActivity::class.java)
        assertTrue(attach.managed)
        assertEquals(387, attach.overrideDpi)
        assertEquals(440, attach.systemDensityDpi)
    }

    @Test
    fun `the override changes density only and the screen dp stay in platform dp`() {
        DisplayDensityNormalizer.setMode(context, DisplayDensityNormalizer.Mode.ON)
        val attach = DisplayDensityNormalizer.attach(context, EligibleActivity::class.java)
        val platform = context.resources.configuration
        val configured = LanguageUtils.createConfiguredContext(context, attach.overrideDpi)

        assertEquals(387, configured.resources.displayMetrics.densityDpi)
        assertEquals(387 / 160f, configured.resources.displayMetrics.density)
        assertEquals(
            "the dual-pane gate and every w/h/sw qualifier keep resolving on platform dp",
            platform.screenWidthDp,
            configured.resources.configuration.screenWidthDp,
        )
        assertEquals(platform.screenHeightDp, configured.resources.configuration.screenHeightDp)
        assertEquals(platform.smallestScreenWidthDp, configured.resources.configuration.smallestScreenWidthDp)
    }

    @Test
    fun `an activity that handles panel changes in place keeps the system density`() {
        DisplayDensityNormalizer.setMode(context, DisplayDensityNormalizer.Mode.ON)
        val attach = DisplayDensityNormalizer.attach(context, HandlesPanelChangesActivity::class.java)
        assertFalse(attach.managed)
        assertEquals(Configuration.DENSITY_DPI_UNDEFINED, attach.overrideDpi)
    }

    @Test
    fun `an unreadable panel stays managed but keeps the system density`() {
        DisplayDensityNormalizer.setMode(context, DisplayDensityNormalizer.Mode.ON)
        shadowOf(ShadowDisplay.getDefaultDisplay()).setXdpi(320f)
        shadowOf(ShadowDisplay.getDefaultDisplay()).setYdpi(320f)
        val attach = DisplayDensityNormalizer.attach(context, EligibleActivity::class.java)
        assertTrue("still reconciled on a later panel change", attach.managed)
        assertEquals(Configuration.DENSITY_DPI_UNDEFINED, attach.overrideDpi)
    }

    @Test
    fun `eligibility follows configChanges`() {
        assertTrue(DisplayDensityNormalizer.isEligibleConfigChanges(ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE))
        assertFalse(DisplayDensityNormalizer.isEligibleConfigChanges(ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE))
        assertFalse(DisplayDensityNormalizer.isEligibleConfigChanges(ActivityInfo.CONFIG_DENSITY))
    }

    @Test
    fun `the first attach picks the size from the system Display size and keeps it`() {
        assertEquals(null, DisplayDensityNormalizer.storedDisplaySize(context))
        DisplayDensityNormalizer.attach(context, EligibleActivity::class.java)
        DisplayDensityNormalizer.resetForTest()
        assertEquals(DisplaySize.STANDARD, DisplayDensityNormalizer.storedDisplaySize(context))
    }

    @Test
    fun `the chosen size applies on the next attach`() {
        DisplayDensityNormalizer.setDisplaySize(context, DisplaySize.SMALL)
        assertEquals(348, DisplayDensityNormalizer.attach(context, EligibleActivity::class.java).overrideDpi)
        DisplayDensityNormalizer.setDisplaySize(context, DisplaySize.LARGE)
        
        assertEquals(445, DisplayDensityNormalizer.attach(context, EligibleActivity::class.java).overrideDpi)
    }

    @Test
    fun `a density above the cap is reconciled however small the difference`() {
        
        
        assertTrue(DisplayDensityNormalizer.recreateNeeded(rendered = 395, target = 389, cap = 389))
        
        assertFalse(DisplayDensityNormalizer.recreateNeeded(rendered = 389, target = 395, cap = 395))
        assertFalse(DisplayDensityNormalizer.recreateNeeded(rendered = 387, target = 383, cap = 440))
        assertTrue(DisplayDensityNormalizer.recreateNeeded(rendered = 387, target = 440, cap = 440))
    }

    @Test
    fun `the loop guard holds back only the relaunched instance`() {
        val key = "ChatActivity:389"
        DisplayDensityNormalizer.markRecreateForTest(key, at = 10_000L)
        
        assertTrue(DisplayDensityNormalizer.isRecreateLoop(key, now = 11_000L, createdAt = 10_200L))
        
        assertFalse(DisplayDensityNormalizer.isRecreateLoop(key, now = 11_000L, createdAt = 5_000L))
        
        assertFalse(DisplayDensityNormalizer.isRecreateLoop(key, now = 13_500L, createdAt = 10_200L))
        assertFalse(DisplayDensityNormalizer.isRecreateLoop("ChatActivity:395", now = 11_000L, createdAt = 10_200L))
    }

    @Test
    fun `an activity on another display stays managed at the system density`() {
        val secondId = ShadowDisplayManager.addDisplay("w800dp-h600dp-240dpi")
        val secondDisplay = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(secondId)
        val attach = DisplayDensityNormalizer.attach(context.createDisplayContext(secondDisplay), EligibleActivity::class.java)
        assertTrue("reconciled after a move back to the phone panel", attach.managed)
        assertEquals(Configuration.DENSITY_DPI_UNDEFINED, attach.overrideDpi)
    }

    @Test
    fun `the mode survives a new process`() {
        DisplayDensityNormalizer.setMode(context, DisplayDensityNormalizer.Mode.OFF)
        DisplayDensityNormalizer.resetForTest()
        assertEquals(DisplayDensityNormalizer.Mode.OFF, DisplayDensityNormalizer.mode(context))
    }
}
