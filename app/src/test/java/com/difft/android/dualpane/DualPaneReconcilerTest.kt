package com.difft.android.dualpane

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [DualPaneReconciler] against the real `activity_index.xml`, inflated on a vanilla
 * [FragmentActivity] (IndexActivity is `@AndroidEntryPoint` and the app's Robolectric tests
 * deliberately never boot it). `@Config(qualifiers)` picks the layout variant the way a device
 * configuration does; `RuntimeEnvironment.setQualifiers` mid-test is the window-size change the
 * OS did not relaunch us for — the ColorOS fold and the floating-window resize.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DualPaneReconcilerTest {

    private val relaunches = mutableListOf<Unit>()

    @Before
    fun setUp() {
        DualPaneReconciler.resetProcessStateForTest()
        relaunches.clear()
        // Robolectric starts elapsedRealtime() near 0 and the throttle compares against a reset
        // stamp of 0, so without this the first rebuild of every case would be throttled.
        idleFor(2_000)
    }

    @After
    fun tearDown() = DualPaneReconciler.resetProcessStateForTest()

    private class Host(val activity: FragmentActivity, val root: View, val reconciler: DualPaneReconciler) {
        var started = false
    }

    private fun host(started: Boolean): Host {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setContentView(R.layout.activity_index)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        lateinit var host: Host
        val reconciler = DualPaneReconciler(activity, root, isStarted = { host.started }) { relaunches += Unit }
        host = Host(activity, root, reconciler).apply { this.started = started }
        return host
    }

    private fun Host.staleTheChrome() {
        val rail = checkNotNull(activity.findViewById<View>(R.id.navigation_rail_container)) { "needs the dual-pane tree" }
        rail.layoutParams = rail.layoutParams.apply { width += 7 }
    }

    private fun Host.freshenTheChrome() {
        val rail = checkNotNull(activity.findViewById<View>(R.id.navigation_rail_container)) { "needs the dual-pane tree" }
        rail.layoutParams = rail.layoutParams.apply { width = activity.resources.getDimensionPixelSize(R.dimen.dual_pane_rail_width) }
    }

    /** One reconcile per throttle window, so this measures the breaker and not the throttle. */
    private fun Host.spendRebuildBudget(attempts: Int): Int {
        val before = relaunches.size
        repeat(attempts) {
            reconciler.reconcile("test")
            idleFor(1_100)
        }
        return relaunches.size - before
    }

    private fun idleFor(ms: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun Host.settleFirstTraversal() {
        idleFor(2_000)
        check(root.width > 0) { "the first traversal must have laid the pane root out" }
        relaunches.clear()
    }

    // ---------------------------------------------------------------- the fold nobody relaunched

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `unfold delivered as a configuration change only - single-pane tree rebuilds once`() {
        val host = host(started = true)
        host.reconciler.attach()
        host.settleFirstTraversal()
        assertNull("precondition: the folded qualifier inflates the phone layout", host.activity.findViewById(R.id.detail_pane))

        // ColorOS fold/unfold: the configuration moves to the inner display, the activity stays.
        RuntimeEnvironment.setQualifiers("w763dp-h691dp")
        host.reconciler.reconcile("onStart")

        assertEquals(
            "the gate now resolves dual-pane against a single-pane tree, and the only way to gain a" +
                " detail pane is to inflate again — exactly one relaunch",
            1,
            relaunches.size,
        )
    }

    @Test
    @Config(qualifiers = "w763dp-h691dp")
    fun `fold delivered as a configuration change only - dual-pane tree rebuilds once`() {
        val host = host(started = true)
        host.reconciler.attach()
        host.settleFirstTraversal()
        assertNotNull("precondition: the unfolded qualifier inflates the dual-pane layout", host.activity.findViewById(R.id.detail_pane))

        RuntimeEnvironment.setQualifiers("w360dp-h840dp")
        host.reconciler.reconcile("onStart")

        assertEquals("a dual-pane tree in a 360dp window must re-inflate as single pane", 1, relaunches.size)
    }

    @Test
    @Config(qualifiers = "w763dp-h691dp")
    fun `an agreeing tree is left alone`() {
        val host = host(started = true)
        host.reconciler.attach()
        host.settleFirstTraversal()
        host.reconciler.reconcile("onStart")
        assertEquals("gate == tree with fresh chrome is the fixed point; nothing may relaunch", 0, relaunches.size)
    }

    // ---------------------------------------------------------------- lifecycle guards

    @Test
    @Config(qualifiers = "w673dp-h480dp")
    fun `a rebuild is deferred while the host is stopped and retried on start`() {
        val host = host(started = false)
        host.reconciler.attach()
        host.staleTheChrome()
        host.reconciler.reconcile("test")
        assertEquals(
            "recreate() is a silent no-op outside [ON_START..ON_STOP], so a rebuild requested while" +
                " stopped must be deferred, not issued and lost",
            0,
            relaunches.size,
        )
        host.started = true
        host.reconciler.reconcile("onStart")
        assertEquals("onStart must retry the deferred rebuild", 1, relaunches.size)
    }

    @Test
    @Config(qualifiers = "w673dp-h480dp")
    fun `two rebuilds inside the throttle window issue one relaunch and re-arm a retry`() {
        val host = host(started = true)
        host.reconciler.attach()
        host.staleTheChrome()
        host.settleFirstTraversal()
        host.reconciler.reconcile("test")
        assertEquals("the first genuine disagreement must relaunch", 1, relaunches.size)
        host.reconciler.reconcile("test")
        assertEquals("a second rebuild inside the throttle window must be suppressed", 1, relaunches.size)
        idleFor(1_100)
        assertEquals("a suppressed rebuild must re-arm a retry: nothing else acts on the standing disagreement", 2, relaunches.size)
    }

    @Test
    @Config(qualifiers = "w673dp-h480dp")
    fun `the layout listener skips the 0x0 to WxH first frame`() {
        val host = host(started = true)
        host.reconciler.attach()
        host.staleTheChrome()
        // The first traversal itself is the 0x0 -> WxH frame: it must not schedule a reconcile.
        idleFor(2_000)
        assertEquals("inflation is not a resize; onStart owns the first reconcile of an instance", 0, relaunches.size)
    }

    @Test
    @Config(qualifiers = "w673dp-h480dp")
    fun `the layout listener coalesces a drag onto its settled frame`() {
        val host = host(started = true)
        host.reconciler.attach()
        host.staleTheChrome()
        host.settleFirstTraversal()
        repeat(5) { step -> host.root.layout(0, 0, host.root.width - 10 * (step + 1), host.root.height) }
        idleFor(100)
        assertEquals("frames inside the debounce window must not relaunch", 0, relaunches.size)
        idleFor(300)
        assertEquals("the settled frame reconciles exactly once", 1, relaunches.size)
    }

    @Test
    @Config(qualifiers = "w673dp-h480dp")
    fun `an agreement re-arms the breaker for the next genuine disagreement`() {
        val host = host(started = true)
        host.reconciler.attach()
        host.settleFirstTraversal()
        host.staleTheChrome()
        assertEquals("one unchanged disagreement buys 3 rebuilds, then trips", 3, host.spendRebuildBudget(attempts = 5))
        host.freshenTheChrome()
        val beforeAgreement = relaunches.size
        host.reconciler.reconcile("agreement")
        idleFor(1_100)
        assertEquals("an agreement pass must rebuild nothing", beforeAgreement, relaunches.size)
        host.staleTheChrome()
        assertEquals("the agreement must have re-armed the breaker", 3, host.spendRebuildBudget(attempts = 5))
    }

    @Test
    @Config(qualifiers = "w673dp-h480dp")
    fun `detach stops the layout listener from reconciling again`() {
        val host = host(started = true)
        host.reconciler.attach()
        host.staleTheChrome()
        host.settleFirstTraversal()
        host.reconciler.detach()
        host.root.layout(0, 0, 900, 800)
        idleFor(3_000)
        assertEquals("detach must remove the listener and both posted runnables", 0, relaunches.size)
    }
}
