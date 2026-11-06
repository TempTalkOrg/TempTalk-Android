package com.difft.android.dualpane

import android.app.Activity
import android.os.SystemClock
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L

/** What a reconcile or rebuild request did; [PROCEED] means `recreate()` was called. */
internal enum class PaneRebuildOutcome { NONE, PROCEED, THROTTLED, DEFERRED, BLOCKED }

/**
 * Keeps IndexActivity's inflated pane tree in agreement with the window it is shown in.
 *
 * Dual-pane mode is decided once, at inflation, by the `layout-w673dp-h480dp` qualifier. That
 * is correct as long as every window-size change relaunches the activity — which AOSP does for
 * a fold/unfold, but ColorOS does not (fold/unfold arrives as onConfigurationChanged only), and
 * no OS does for a floating-window / split-screen resize. In those cases the resource gate
 * `@bool/dual_pane_layout_active` re-resolves against the new size while the tree keeps the old
 * structure: launched folded then unfolded stays a stretched single pane, launched unfolded then
 * folded keeps a dual-pane tree in a 360dp window.
 *
 * Two triggers cover the cases the OS does not report: [reconcile] from `onStart` (a resize
 * that happened while we were off screen) and a debounced root-layout listener (a resize while
 * visible). A disagreement is fixed with `recreate()` — the same path an OS relaunch takes, so
 * saved instance state, the persisted split ratio and the collapsed flag all restore as usual.
 * The relaunch is throttled and bounded by [PaneRebuildBreaker] so a resource overlay that
 * makes gate and tree disagree forever cannot become a relaunch loop.
 *
 * The opposite case, a window resize whose Configuration never arrives (#1454), leaves gate and
 * tree agreeing on the stale size; [StaleLayoutMonitor] owns that episode and uses [reconcile]
 * and [rebuildForStaleLayout] from here.
 */
internal class DualPaneReconciler(
    private val activity: FragmentActivity,
    private val paneRoot: View,
    private val isStarted: () -> Boolean,
    private val recreate: () -> Unit = activity::recreate,
) {
    private val resources get() = activity.resources

    private val reconcileRunnable = Runnable { reconcile("layout") }
    private val rebuildRetryRunnable = Runnable { reconcile("rebuildRetry") }

    private val rootLayoutListener = View.OnLayoutChangeListener { v, l, t, r, b, oL, oT, oR, oB ->
        val sizeChanged = (r - l) != (oR - oL) || (b - t) != (oB - oT)
        // 0x0 -> WxH is inflation, not a resize; onStart reconciles that instance unconditionally.
        val firstFrame = (oR - oL) == 0 && (oB - oT) == 0
        if (sizeChanged && !firstFrame) {
            // A new frame supersedes BOTH pending passes: a throttle retry armed mid-drag would
            // otherwise fire recreate() against a transient intermediate width.
            v.removeCallbacks(reconcileRunnable)
            v.removeCallbacks(rebuildRetryRunnable)
            v.postDelayed(reconcileRunnable, RECONCILE_DEBOUNCE_MS)
        }
    }

    /** Call once after `setContentView`; the listener is the only trigger for a visible resize. */
    fun attach() {
        paneRoot.addOnLayoutChangeListener(rootLayoutListener)
    }

    fun detach() {
        paneRoot.removeOnLayoutChangeListener(rootLayoutListener)
        paneRoot.removeCallbacks(reconcileRunnable)
        paneRoot.removeCallbacks(rebuildRetryRunnable)
    }

    fun reconcile(reason: String): PaneRebuildOutcome {
        if (activity.isFinishing || activity.isChangingConfigurations) return PaneRebuildOutcome.NONE
        val gate = gateIsDualPane()
        val tree = treeIsDualPane(activity)
        val chromeStale = tree && isPaneChromeStale()
        val action = PaneReconcileDecision.decide(gate, tree, chromeStale)
        if (action != PaneReconcileAction.NONE) {
            val cfg = resources.configuration
            L.i {
                "[IndexActivity] paneReconcile reason=$reason action=$action gate=$gate tree=$tree" +
                    " chromeStale=$chromeStale rootPx=${paneRoot.width}x${paneRoot.height}" +
                    " configDp=${cfg.screenWidthDp}x${cfg.screenHeightDp}"
            }
        }
        return when (action) {
            PaneReconcileAction.NONE -> {
                rebuildBreaker.onAgreement()
                PaneRebuildOutcome.NONE
            }
            PaneReconcileAction.REBUILD_TREE -> rebuildPaneTree(gate, tree, chromeStale)
        }
    }

    fun gateIsDualPane(): Boolean = resources.getBoolean(R.bool.dual_pane_layout_active)

    /**
     * The #1454 size-override recreate: through the same throttle and breaker as a gate/tree
     * rebuild, under its own breaker signature.
     */
    fun rebuildForStaleLayout(): PaneRebuildOutcome {
        val tree = treeIsDualPane(activity)
        return rebuildPaneTree(gateIsDualPane(), tree, tree && isPaneChromeStale(), staleLayout = true)
    }

    private fun rebuildPaneTree(gate: Boolean, tree: Boolean, chromeStale: Boolean, staleLayout: Boolean = false): PaneRebuildOutcome {
        val outcome = requestRebuild(gate, tree, chromeStale, staleLayout)
        // A stale-layout override is only for the recreate it was recorded for; a later,
        // unrelated recreate must not pick it up.
        if (outcome != PaneRebuildOutcome.PROCEED) StaleLayoutOverride.clear()
        return outcome
    }

    private fun requestRebuild(gate: Boolean, tree: Boolean, chromeStale: Boolean, staleLayout: Boolean): PaneRebuildOutcome {
        // recreate() is a silent no-op outside [ON_START..ON_STOP] (the check runs when the posted
        // RELAUNCH_ACTIVITY message is handled), so a stopped host defers to its onStart reconcile.
        if (!isStarted()) {
            L.i { "[IndexActivity] paneReconcile rebuild deferred, not started - onStart retries" }
            return PaneRebuildOutcome.DEFERRED
        }
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - rebuildAtMs
        if (sinceLast < REBUILD_MIN_INTERVAL_MS) {
            // Re-arm: nothing else acts on the standing disagreement, and the deferral path's
            // retry edge (onStart) does not fire for a suppression. Idempotent — an agreement
            // by then decides NONE and logs nothing.
            val remaining = REBUILD_MIN_INTERVAL_MS - sinceLast
            paneRoot.removeCallbacks(rebuildRetryRunnable)
            paneRoot.postDelayed(rebuildRetryRunnable, remaining)
            L.w { "[IndexActivity] paneReconcile rebuild suppressed, ${sinceLast}ms since last, retry in ${remaining}ms" }
            return PaneRebuildOutcome.THROTTLED
        }
        // Counted AFTER the throttle so it means "relaunches that did not change the outcome".
        when (rebuildBreaker.onRebuild(gate, tree, chromeStale, staleLayout)) {
            PaneRebuildVerdict.PROCEED -> Unit
            PaneRebuildVerdict.TRIP -> {
                L.e {
                    "[IndexActivity] paneReconcile CIRCUIT BREAKER OPEN - rebuilding did not reach" +
                        " agreement (gate=$gate tree=$tree chromeStale=$chromeStale); pane structure" +
                        " frozen as-is to stop a relaunch loop. Suspect a @bool/@dimen overlay, or drift" +
                        " between res/layout-w673dp-h480dp/ and res/values-w673dp-h480dp/."
                }
                return PaneRebuildOutcome.BLOCKED
            }
            PaneRebuildVerdict.SUPPRESSED -> return PaneRebuildOutcome.BLOCKED
        }
        rebuildAtMs = now
        L.w { "[IndexActivity] paneReconcile rebuilding pane tree via recreate() staleLayout=$staleLayout" }
        recreate()
        return PaneRebuildOutcome.PROCEED
    }

    /**
     * The pane dimens are resolved at inflation; a different dimens band means re-inflate.
     * list_pane carries the band it was inflated with as its tag (`@string/dual_pane_band`,
     * overridden in values-w900dp/), so the check never reads a width that runtime sizing
     * (saved split, large text, collapse) has already changed.
     */
    private fun isPaneChromeStale(): Boolean {
        val listPane = activity.findViewById<View>(R.id.list_pane) ?: return false
        val inflatedBand = listPane.tag as? String ?: return false
        return inflatedBand != resources.getString(R.string.dual_pane_band)
    }

    companion object {
        private const val RECONCILE_DEBOUNCE_MS = 200L
        private const val REBUILD_MIN_INTERVAL_MS = 1_000L

        // Process-scoped on purpose: both have to survive the very recreate() they guard.
        @Volatile
        private var rebuildAtMs = 0L
        private val rebuildBreaker = PaneRebuildBreaker()

        /** list_pane is the two-pane marker: detail_pane and its container exist in both trees. */
        fun treeIsDualPane(activity: Activity): Boolean = activity.findViewById<View>(R.id.list_pane) != null

        @VisibleForTesting
        internal fun resetProcessStateForTest() {
            rebuildAtMs = 0L
            rebuildBreaker.onAgreement()
            StaleLayoutMonitor.resetProcessStateForTest()
            StaleLayoutOverride.resetForTest()
        }
    }
}
