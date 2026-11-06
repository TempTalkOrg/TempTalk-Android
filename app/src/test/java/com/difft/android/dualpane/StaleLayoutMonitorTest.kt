package com.difft.android.dualpane

import android.app.PictureInPictureParams
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import com.difft.android.base.utils.OrientationPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [StaleLayoutMonitor] on the real `activity_index.xml` (the [DualPaneReconcilerTest] harness).
 * The default qualifiers are the #1454 state: an inner-panel configuration (860x608 dp, dual-pane
 * tree) while the injected panel reader says the outer panel (424x622; mdpi, so px == dp).
 * `rebuilds` counts recreates through the reconciler, `exits` the monitor's direct override exit.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w860dp-h608dp")
class StaleLayoutMonitorTest {

    private val rebuilds = mutableListOf<Unit>()
    private val exits = mutableListOf<Unit>()
    private var panel: OrientationPolicy.HostPanel? = OUTER
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        DualPaneReconciler.resetProcessStateForTest()
        StaleLayoutOverrideTest.OverrideProbeActivity.probePanel = OUTER
        // The rebuild throttle compares against a reset stamp of 0 (see DualPaneReconcilerTest).
        idleFor(2_000)
    }

    @After
    fun tearDown() {
        DualPaneReconciler.resetProcessStateForTest()
    }

    private class Host(val activity: FragmentActivity, val reconciler: DualPaneReconciler, val monitor: StaleLayoutMonitor) {
        var started = true
    }

    private fun host(
        started: Boolean = true,
        activityClass: Class<out FragmentActivity> = FragmentActivity::class.java,
    ): Host {
        val activity = Robolectric.buildActivity(activityClass).setup().get()
        val overridePanel = (activity as? StaleLayoutOverrideTest.OverrideProbeActivity)?.appliedPanel
        activity.setContentView(R.layout.activity_index)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        lateinit var host: Host
        val reconciler = DualPaneReconciler(activity, root, isStarted = { host.started }) { rebuilds += Unit }
        val monitor = StaleLayoutMonitor(
            activity,
            reconciler,
            isStarted = { host.started },
            activeOverridePanel = overridePanel,
            readPanel = { panel },
            recreate = { exits += Unit },
            warn = { lines += it },
        )
        host = Host(activity, reconciler, monitor).apply { this.started = started }
        return host
    }

    /** The instance a rung-3 recreate produces: the override applied in attachBaseContext. */
    private fun overrideInstance(): Host {
        val host = host(activityClass = StaleLayoutOverrideTest.OverrideProbeActivity::class.java)
        val applied = (host.activity as StaleLayoutOverrideTest.OverrideProbeActivity).appliedPanel
        assertEquals("precondition: the override applied", OUTER.key, applied)
        return host
    }

    private fun idleFor(ms: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    /** The monitor's `staleLayout` lines, captured synchronously instead of through `L`'s logger thread. */
    private fun logged(fragment: String): List<String> = lines.filter { it.contains(fragment) }

    /** Detection at settle (t = 1 s), verification at t = 4 s. */
    private fun Host.runToDetection() {
        monitor.onHostDisplayChanged()
        idleFor(1_001)
    }

    // ---------------------------------------------------------------- arming and detection

    @Test
    fun `a panel change arms one settled check that detects once`() {
        val host = host()
        host.monitor.onHostDisplayChanged()
        idleFor(990)
        assertEquals("nothing before the settle delay", 0, logged("staleLayout detected").size)
        idleFor(20)
        val detected = logged("staleLayout detected")
        assertEquals(1, detected.size)
        assertTrue(detected.single(), detected.single().contains("panel=424x622 panelDp=424x622 configDp=860x608"))
        assertEquals("detection alone never recreates", 0, rebuilds.size)
    }

    @Test
    fun `a same-panel display event does not re-arm the settle`() {
        val host = host()
        host.monitor.onHostDisplayChanged()
        idleFor(600)
        host.monitor.onHostDisplayChanged()
        idleFor(450)
        assertEquals("a refresh-rate event must not postpone the check", 1, logged("staleLayout detected").size)
    }

    @Test
    fun `detection is logged once per panel across instances`() {
        host().runToDetection()
        host().runToDetection()
        assertEquals(1, logged("staleLayout detected").size)
    }

    @Test
    fun `the onStart replay arrives before started - guards are checked when the check fires`() {
        val host = host(started = false)
        host.monitor.onHostDisplayChanged()
        host.started = true
        idleFor(1_001)
        assertEquals(1, logged("staleLayout detected").size)
    }

    @Test
    fun `not started at fire time - nothing`() {
        host(started = false).runToDetection()
        assertEquals(0, logged("staleLayout detected").size)
    }

    @Test
    fun `finishing, multi-window and picture-in-picture skip the check`() {
        val finishing = host()
        finishing.activity.finish()
        finishing.runToDetection()
        val multiWindow = host()
        Shadows.shadowOf(multiWindow.activity).setInMultiWindowMode(true)
        multiWindow.runToDetection()
        val pip = host()
        pip.activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        pip.runToDetection()
        assertEquals(0, logged("staleLayout detected").size)
    }

    @Test
    fun `detach before the settle - nothing`() {
        val host = host()
        host.monitor.onHostDisplayChanged()
        host.monitor.detach()
        idleFor(6_000)
        assertEquals(0, logged("staleLayout").size)
        assertEquals(0, rebuilds.size)
    }

    @Test
    fun `onStop cancels the pending check and the next onStart replay arms again`() {
        val host = host()
        host.monitor.onHostDisplayChanged()
        host.monitor.onStop()
        idleFor(1_100)
        assertEquals(0, logged("staleLayout detected").size)
        host.monitor.onHostDisplayChanged()
        idleFor(1_100)
        assertEquals(1, logged("staleLayout detected").size)
    }

    @Test
    fun `an in-place arrival before the settle rebuilds through the gate and tree path`() {
        val host = host()
        host.monitor.onHostDisplayChanged()
        RuntimeEnvironment.setQualifiers("w424dp-h622dp")
        idleFor(1_001)
        assertEquals(1, rebuilds.size)
        assertEquals(0, logged("staleLayout detected").size)
        assertNull(StaleLayoutOverride.active)
    }

    // ---------------------------------------------------------------- verification and rung 3

    @Test
    fun `verification logs one result, then the override recreate runs once`() {
        val host = host()
        host.runToDetection()
        idleFor(2_890)
        assertEquals("no recreate on the stale predicate alone before verification", 0, rebuilds.size)
        idleFor(200)
        val result = logged("staleLayout result")
        assertEquals(1, result.size)
        assertTrue(result.single(), result.single().contains("arrived=none healed=false dualPane=true"))
        assertEquals("rung 3 recreates once", 1, rebuilds.size)
        assertNotNull("the override is recorded for the new instance", StaleLayoutOverride.active)
        idleFor(10_000)
        assertEquals(1, logged("staleLayout result").size)
        assertEquals(1, rebuilds.size)
    }

    @Test
    fun `an unfold and quick refold after a recovery detects and heals the new stale fold again`() {
        host().runToDetection()
        idleFor(3_100)
        assertEquals("first episode: rung 3 ran", 1, rebuilds.size)
        // Unfold: the platform relaunches onto the inner panel; refold before its settled check runs.
        panel = INNER
        val inner = host()
        inner.monitor.onHostDisplayChanged()
        idleFor(400)
        inner.monitor.detach()
        StaleLayoutOverride.clearIfNotFor(INNER.key)
        panel = OUTER
        val refolded = host()
        refolded.runToDetection()
        assertEquals("the new stale fold is detected again", 2, logged("staleLayout detected").size)
        idleFor(3_100)
        assertEquals("and rung 3 runs for it again", 2, rebuilds.size)
    }

    @Test
    fun `an unrelated relaunch with the configuration still stale does not suppress rung 3`() {
        val first = host()
        first.runToDetection()
        first.monitor.detach() // a theme recreate destroys the detecting instance
        val second = host()
        second.monitor.onHostDisplayChanged() // its onStart replay
        idleFor(3_100)
        val result = logged("staleLayout result")
        assertEquals(1, result.size)
        assertTrue(result.single(), result.single().contains("arrived=relaunch healed=false"))
        assertEquals(1, rebuilds.size)
        assertEquals(1, logged("staleLayout detected").size)
    }

    @Test
    fun `the override instance reports its own result via the override, then nothing`() {
        host().runToDetection()
        idleFor(3_100)
        assertEquals(1, rebuilds.size)
        val override = overrideInstance()
        override.monitor.onHostDisplayChanged()
        idleFor(4_000)
        val results = logged("staleLayout result")
        assertEquals(2, results.size)
        assertTrue(results.last(), results.last().contains("arrived=relaunch healed=true dualPane=false"))
        assertTrue(results.last(), results.last().endsWith("via=override"))
        assertEquals(
            "the override instance's own one-pane state must not close the platform's episode",
            OUTER.key,
            StaleLayoutMonitor.reportedPanelForTest(),
        )
        idleFor(10_000)
        assertEquals(2, logged("staleLayout result").size)
        assertEquals(1, rebuilds.size)
        assertEquals(0, exits.size)
    }

    @Test
    fun `a throttled override recreate clears the record and retries after the throttle`() {
        val host = host()
        host.runToDetection()
        idleFor(2_500)
        // Another rebuild 500 ms before verification opens the throttle window.
        assertEquals(PaneRebuildOutcome.PROCEED, host.reconciler.rebuildForStaleLayout())
        StaleLayoutOverride.clear()
        rebuilds.clear()
        idleFor(600)
        assertEquals(1, logged("staleLayout result").size)
        assertEquals("throttled", 0, rebuilds.size)
        assertNull("a rebuild that did not proceed must not leave a record behind", StaleLayoutOverride.active)
        idleFor(1_200)
        assertEquals("retried once the throttle window passed", 1, rebuilds.size)
        assertNotNull(StaleLayoutOverride.active)
    }

    @Test
    fun `a late in-place arrival before verification rebuilds through the gate and tree path`() {
        val host = host()
        host.runToDetection()
        RuntimeEnvironment.setQualifiers("w424dp-h622dp")
        idleFor(3_100)
        assertEquals(1, rebuilds.size)
        assertNull("no override for an arrived configuration", StaleLayoutOverride.active)
        assertEquals("the resulting instance verifies", 0, logged("staleLayout result").size)
        host.monitor.detach()
        host().monitor.onHostDisplayChanged()
        idleFor(1_100)
        val result = logged("staleLayout result")
        assertEquals(1, result.size)
        assertTrue(result.single(), result.single().contains("arrived=inplace healed=true dualPane=false"))
        assertNull("a healthy check ends the episode", StaleLayoutMonitor.reportedPanelForTest())
    }

    // ---------------------------------------------------------------- override exit

    @Test
    fun `the override instance exits on another panel from the settled check only, despite the throttle`() {
        StaleLayoutOverride.record(OUTER)
        val override = overrideInstance()
        override.monitor.onHostDisplayChanged()
        idleFor(1_100)
        assertEquals(PaneRebuildOutcome.PROCEED, override.reconciler.rebuildForStaleLayout()) // throttle open
        panel = INNER
        override.monitor.onHostDisplayChanged()
        idleFor(500)
        assertEquals("the display event alone never exits", 0, exits.size)
        idleFor(600)
        assertEquals("exits once, directly", 1, exits.size)
        assertNull("the next attach has no record", StaleLayoutOverride.active)
        panel = OUTER
        override.monitor.onHostDisplayChanged()
        idleFor(1_100)
        panel = INNER
        override.monitor.onHostDisplayChanged()
        idleFor(1_100)
        assertEquals("the exit is not repeated", 1, exits.size)
        assertEquals(1, logged("staleLayout override exit panel=424x622->859x608").size)
    }

    @Test
    fun `the override exit is not stopped by an open breaker`() {
        StaleLayoutOverride.record(OUTER)
        val override = overrideInstance()
        repeat(5) {
            override.reconciler.rebuildForStaleLayout()
            idleFor(1_100)
        }
        assertEquals("precondition: the breaker is open", PaneRebuildOutcome.BLOCKED, override.reconciler.rebuildForStaleLayout())
        idleFor(1_100)
        panel = INNER
        override.monitor.onHostDisplayChanged()
        idleFor(1_100)
        assertEquals(1, exits.size)
    }

    @Test
    fun `the tree marker is list_pane, not detail_pane`() {
        val dual = host()
        assertTrue(DualPaneReconciler.treeIsDualPane(dual.activity))
        assertNotNull(dual.activity.findViewById<View>(R.id.detail_pane))
        RuntimeEnvironment.setQualifiers("w424dp-h622dp")
        val single = host()
        assertNotNull("detail_pane exists in the one-pane tree too", single.activity.findViewById<View>(R.id.detail_pane))
        assertEquals(false, DualPaneReconciler.treeIsDualPane(single.activity))
    }

    private companion object {
        val OUTER = OrientationPolicy.HostPanel("424x622", 424, 622, 1f, Surface.ROTATION_0)
        val INNER = OrientationPolicy.HostPanel("859x608", 859, 608, 1f, Surface.ROTATION_0)
    }
}
