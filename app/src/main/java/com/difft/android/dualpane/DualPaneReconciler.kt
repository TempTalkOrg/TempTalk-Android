package com.difft.android.dualpane

import android.app.Activity
import android.os.SystemClock
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L


internal enum class PaneRebuildOutcome { NONE, PROCEED, THROTTLED, DEFERRED, BLOCKED }























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
        
        val firstFrame = (oR - oL) == 0 && (oB - oT) == 0
        if (sizeChanged && !firstFrame) {
            
            
            v.removeCallbacks(reconcileRunnable)
            v.removeCallbacks(rebuildRetryRunnable)
            v.postDelayed(reconcileRunnable, RECONCILE_DEBOUNCE_MS)
        }
    }

    
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

    



    fun rebuildForStaleLayout(): PaneRebuildOutcome {
        val tree = treeIsDualPane(activity)
        return rebuildPaneTree(gateIsDualPane(), tree, tree && isPaneChromeStale(), staleLayout = true)
    }

    private fun rebuildPaneTree(gate: Boolean, tree: Boolean, chromeStale: Boolean, staleLayout: Boolean = false): PaneRebuildOutcome {
        val outcome = requestRebuild(gate, tree, chromeStale, staleLayout)
        
        
        if (outcome != PaneRebuildOutcome.PROCEED) StaleLayoutOverride.clear()
        return outcome
    }

    private fun requestRebuild(gate: Boolean, tree: Boolean, chromeStale: Boolean, staleLayout: Boolean): PaneRebuildOutcome {
        
        
        if (!isStarted()) {
            L.i { "[IndexActivity] paneReconcile rebuild deferred, not started - onStart retries" }
            return PaneRebuildOutcome.DEFERRED
        }
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - rebuildAtMs
        if (sinceLast < REBUILD_MIN_INTERVAL_MS) {
            
            
            
            val remaining = REBUILD_MIN_INTERVAL_MS - sinceLast
            paneRoot.removeCallbacks(rebuildRetryRunnable)
            paneRoot.postDelayed(rebuildRetryRunnable, remaining)
            L.w { "[IndexActivity] paneReconcile rebuild suppressed, ${sinceLast}ms since last, retry in ${remaining}ms" }
            return PaneRebuildOutcome.THROTTLED
        }
        
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

    





    private fun isPaneChromeStale(): Boolean {
        val listPane = activity.findViewById<View>(R.id.list_pane) ?: return false
        val inflatedBand = listPane.tag as? String ?: return false
        return inflatedBand != resources.getString(R.string.dual_pane_band)
    }

    companion object {
        private const val RECONCILE_DEBOUNCE_MS = 200L
        private const val REBUILD_MIN_INTERVAL_MS = 1_000L

        
        @Volatile
        private var rebuildAtMs = 0L
        private val rebuildBreaker = PaneRebuildBreaker()

        
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
