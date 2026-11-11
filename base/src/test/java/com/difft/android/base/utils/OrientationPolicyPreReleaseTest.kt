package com.difft.android.base.utils

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit










@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OrientationPolicyPreReleaseTest {

    
    @Before
    fun resetPending() = OrientationPolicy.clearUnfoldPending()

    @After
    fun clearPending() = OrientationPolicy.clearUnfoldPending()

    private fun createActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).create().get()

    private fun lockedActivity(): Activity = createActivity().apply {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `outer panel with a portrait lock — releases to UNSPECIFIED and reports the write`() {
        val activity = lockedActivity()

        val written = OrientationPolicy.preReleaseForUnfold(activity)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, written)
        assertEquals(
            "The lock must be gone before the OS switches to the inner display, otherwise the " +
                "unfolded display comes up in portrait and rotates afterwards",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            activity.requestedOrientation
        )
    }

    @Test
    @Config(qualifiers = "sw600dp-w859dp-h608dp")
    fun `inner panel — nothing to pre-release, applyTo owns the decision there`() {
        val activity = lockedActivity()

        val written = OrientationPolicy.preReleaseForUnfold(activity)

        assertNull(written)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }

    @Test
    @Config(qualifiers = "sw600dp-w859dp-h608dp")
    fun `frozen app bounds at the inner panel — the panel mode still identifies the outer panel`() {
        
        
        val activity = lockedActivity()
        val density = activity.resources.displayMetrics.density
        ShadowDisplayManager.setSupportedModes(
            Display.DEFAULT_DISPLAY,
            ShadowDisplayManager.ModeBuilder.modeBuilder(0)
                .setWidth((424 * density).toInt()).setHeight((622 * density).toInt()).setRefreshRate(60f).build(),
        )

        val written = OrientationPolicy.preReleaseForUnfold(activity)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, written)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `no portrait lock in force — the value is left alone`() {
        val activity = createActivity()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val written = OrientationPolicy.preReleaseForUnfold(activity)

        assertNull(written)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `replay on the same panel before the display switch keeps the release`() {
        
        
        val activity = lockedActivity()
        OrientationPolicy.preReleaseForUnfold(activity)

        val result = OrientationPolicy.applyTo(activity)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, result)
        assertEquals(
            "Re-locking here would undo the release right before the OS decides the new rotation",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            activity.requestedOrientation
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `fold closed again without a display switch — the next applyTo re-locks`() {
        val activity = lockedActivity()
        OrientationPolicy.preReleaseForUnfold(activity)

        OrientationPolicy.clearUnfoldPending()
        OrientationPolicy.applyTo(activity)

        assertEquals(
            "The panel still reads phone-sized, so the normal policy must restore the lock",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `time box over without a display switch — the next applyTo re-locks`() {
        val activity = lockedActivity()
        OrientationPolicy.preReleaseForUnfold(activity)

        ShadowSystemClock.advanceBy(Duration.ofMillis(OrientationPolicy.UNFOLD_PENDING_MS + 1))
        OrientationPolicy.applyTo(activity)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `screen created on the pending panel is held open, and re-locks once the time box is over`() {
        
        
        
        OrientationPolicy.preReleaseForUnfold(lockedActivity())
        val created = createActivity().apply { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED }

        val result = OrientationPolicy.applyTo(created)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, result)
        assertEquals(
            "A PORTRAIT written here is the request in force at the display switch and brings " +
                "the portrait-then-landscape turn back",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            created.requestedOrientation
        )
        assertTrue(OrientationPolicy.isUnfoldPending())

        ShadowSystemClock.advanceBy(Duration.ofMillis(OrientationPolicy.UNFOLD_PENDING_MS + 1))
        OrientationPolicy.applyTo(created)

        assertFalse(OrientationPolicy.isUnfoldPending())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, created.requestedOrientation)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `scheduleUnfoldRelock runs the re-apply only once the time box is over`() {
        
        
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().get()
        var runs = 0

        OrientationPolicy.scheduleUnfoldRelock(owner) { runs++ }

        ShadowLooper.idleMainLooper(OrientationPolicy.UNFOLD_PENDING_MS, TimeUnit.MILLISECONDS)
        assertEquals("Not before the release can have expired", 0, runs)
        ShadowLooper.idleMainLooper(OrientationPolicy.UNFOLD_RELOCK_SLACK_MS + 1, TimeUnit.MILLISECONDS)
        assertEquals(1, runs)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `relaunch on the inner panel ends the release — a fold-back inside the time box re-locks`() {
        
        
        
        val activity = lockedActivity()
        OrientationPolicy.preReleaseForUnfold(activity)
        val innerId = ShadowDisplayManager.addDisplay("sw600dp-w859dp-h608dp")
        val inner = activity.getSystemService(DisplayManager::class.java).getDisplay(innerId)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, OrientationPolicy.applyTo(activity, inner))

        OrientationPolicy.applyTo(activity)

        assertEquals(
            "Back on the outer panel inside the time box the lock must be restored",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a different phone-sized panel is not covered by the pending release`() {
        
        
        val activity = lockedActivity()
        OrientationPolicy.preReleaseForUnfold(activity)
        val otherId = ShadowDisplayManager.addDisplay("w412dp-h915dp")
        val other = activity.getSystemService(DisplayManager::class.java).getDisplay(otherId)

        OrientationPolicy.applyTo(activity, other)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }
}
