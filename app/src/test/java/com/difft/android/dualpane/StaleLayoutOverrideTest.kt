package com.difft.android.dualpane

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import com.difft.android.base.utils.OrientationPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [StaleLayoutOverride]: the size override built from the panel, and the attach-time predicate,
 * on [OverrideProbeActivity], which calls `applyIfActive` from `attachBaseContext` the way
 * IndexActivity does (IndexActivity is `@AndroidEntryPoint` and is not booted in these tests).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w860dp-h608dp")
class StaleLayoutOverrideTest {

    @Before
    fun setUp() {
        DualPaneReconciler.resetProcessStateForTest()
        OverrideProbeActivity.probePanel = OUTER
    }

    @After
    fun tearDown() = DualPaneReconciler.resetProcessStateForTest()

    // ---------------------------------------------------------------- the override configuration

    @Test
    fun `outer panel at 2_75 in natural rotation is 424x622 dp portrait`() {
        val override = StaleLayoutOverride.sizeOverride(1168, 1712, Surface.ROTATION_0, densityDpi = 440)
        assertEquals(424, override.screenWidthDp)
        assertEquals(622, override.screenHeightDp)
        assertEquals(424, override.smallestScreenWidthDp)
        assertEquals(Configuration.ORIENTATION_PORTRAIT, override.orientation)
    }

    @Test
    fun `a quarter turn swaps the sides and turns landscape`() {
        val override = StaleLayoutOverride.sizeOverride(1168, 1712, Surface.ROTATION_90, densityDpi = 440)
        assertEquals(622, override.screenWidthDp)
        assertEquals(424, override.screenHeightDp)
        assertEquals(424, override.smallestScreenWidthDp)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, override.orientation)
    }

    @Test
    fun `only size and orientation are set - locale, font scale, night mode and density pass through`() {
        val override = StaleLayoutOverride.sizeOverride(1168, 1712, Surface.ROTATION_0, densityDpi = 440)
        val base = Configuration(RuntimeEnvironment.getApplication().resources.configuration).apply {
            fontScale = 1.3f
            densityDpi = 440
        }
        val merged = Configuration(base).apply { updateFrom(override) }
        val changed = base.diff(merged)
        val allowed = ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_ORIENTATION
        assertEquals(
            "the override may change only screen size, smallest width and orientation",
            0,
            changed and allowed.inv(),
        )
        assertEquals(1.3f, merged.fontScale, 0.001f)
        assertEquals(440, merged.densityDpi)
        assertEquals(base.locales, merged.locales)
        assertEquals(base.uiMode, merged.uiMode)
    }

    // ---------------------------------------------------------------- attach-time predicate

    @Test
    fun `a record for the current panel is applied while the incoming configuration exceeds it`() {
        StaleLayoutOverride.record(OUTER)
        val activity = Robolectric.buildActivity(OverrideProbeActivity::class.java).setup().get()

        assertEquals(OUTER.key, activity.appliedPanel)
        assertEquals("the activity resolves for the panel", 424, activity.resources.configuration.screenWidthDp)
        assertEquals("the base stays the platform's", 860, activity.baseContext.resources.configuration.screenWidthDp)
        activity.setContentView(R.layout.activity_index)
        assertNull("the one-pane layout inflates", activity.findViewById<View>(R.id.list_pane))
    }

    @Test
    fun `a second attach on the same stale panel re-applies the record (theme recreate)`() {
        StaleLayoutOverride.record(OUTER)
        Robolectric.buildActivity(OverrideProbeActivity::class.java).setup().get()
        val again = Robolectric.buildActivity(OverrideProbeActivity::class.java).setup().get()

        assertEquals(OUTER.key, again.appliedPanel)
        assertNotNull("the record stays active while both conditions hold", StaleLayoutOverride.active)
    }

    @Test
    fun `a record for another panel is dropped unused (relaunch onto the inner panel)`() {
        StaleLayoutOverride.record(OUTER)
        OverrideProbeActivity.probePanel = INNER
        val activity = Robolectric.buildActivity(OverrideProbeActivity::class.java).setup().get()

        assertNull(activity.appliedPanel)
        assertNull(StaleLayoutOverride.active)
        assertEquals(860, activity.resources.configuration.screenWidthDp)
    }

    @Test
    fun `a record is dropped when the host panel changed even while the configuration still exceeds it`() {
        StaleLayoutOverride.record(OUTER)
        OverrideProbeActivity.probePanel = OrientationPolicy.HostPanel("430x630", 430, 630, 1f, Surface.ROTATION_0)
        val activity = Robolectric.buildActivity(OverrideProbeActivity::class.java).setup().get()

        assertNull("an override is only for the panel it was recorded on", activity.appliedPanel)
        assertNull(StaleLayoutOverride.active)
    }

    @Test
    @Config(qualifiers = "w424dp-h622dp")
    fun `a record is dropped once the incoming configuration fits the panel`() {
        StaleLayoutOverride.record(OUTER)
        val activity = Robolectric.buildActivity(OverrideProbeActivity::class.java).setup().get()

        assertNull(activity.appliedPanel)
        assertNull(StaleLayoutOverride.active)
    }

    @Test
    fun `no record, no override`() {
        val activity = Robolectric.buildActivity(OverrideProbeActivity::class.java).setup().get()
        assertNull(activity.appliedPanel)
        assertEquals(860, activity.resources.configuration.screenWidthDp)
    }

    /** Applies the override exactly where IndexActivity does: right after `super.attachBaseContext`. */
    class OverrideProbeActivity : AppCompatActivity() {
        var appliedPanel: String? = null

        override fun attachBaseContext(newBase: Context) {
            super.attachBaseContext(newBase)
            appliedPanel = StaleLayoutOverride.applyIfActive(this, newBase) { probePanel }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(androidx.appcompat.R.style.Theme_AppCompat)
            super.onCreate(savedInstanceState)
        }

        companion object {
            var probePanel: OrientationPolicy.HostPanel? = null
        }
    }

    companion object {
        // mdpi test configuration: px == dp.
        val OUTER = OrientationPolicy.HostPanel("424x622", 424, 622, 1f, Surface.ROTATION_0)
        val INNER = OrientationPolicy.HostPanel("859x608", 859, 608, 1f, Surface.ROTATION_0)
    }
}
