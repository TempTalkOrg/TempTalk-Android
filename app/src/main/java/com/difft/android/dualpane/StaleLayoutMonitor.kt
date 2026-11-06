package com.difft.android.dualpane

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.OrientationPolicy

/**
 * Self-heal for #1454: a fold that resizes IndexActivity's window to the outer panel but never
 * delivers the matching Configuration or relaunch, leaving the dual-pane tree squeezed into a
 * phone-sized panel until the next unfold.
 *
 * The orientation policy's portrait request at the display event is the first rung (see
 * `OrientationPolicy.resolveShortSideDp`). This class runs the rest, once the panel has been
 * stable for [STALE_SETTLE_MS]:
 *  1. reconcile gate and tree, in case the configuration arrived in place (no other trigger
 *     sees that: no size change, no onStart, no onConfigurationChanged);
 *  2. detect the stale state with [StaleLayoutDecision] and log it once per panel;
 *  3. [STALE_VERIFY_MS] after detection, in whichever instance is alive by then, reconcile again
 *     and log the result;
 *  4. when still stale, recreate once per panel with a size override derived from the panel
 *     ([StaleLayoutOverride]); the override instance reports its own result and recreates
 *     without the override when it later sees another panel.
 *
 * The episode state is process-level because it has to survive the recreations it causes or
 * observes. Health is always judged on the base context's configuration (the platform's), never
 * on an applied override.
 */
internal class StaleLayoutMonitor(
    private val activity: FragmentActivity,
    private val reconciler: DualPaneReconciler,
    private val isStarted: () -> Boolean,
    /** The panel key this instance applied a [StaleLayoutOverride] for in `attachBaseContext`, or null. */
    private var activeOverridePanel: String?,
    private val readPanel: () -> OrientationPolicy.HostPanel? = {
        OrientationPolicy.readHostPanel(activity, ContextCompat.getDisplayOrDefault(activity).displayId)
    },
    private val baseConfiguration: () -> Configuration = { activity.baseContext.resources.configuration },
    private val recreate: () -> Unit = activity::recreate,
    /** Receives each finished `[IndexActivity] staleLayout` line; formatted on the main thread. */
    private val warn: (String) -> Unit = { line -> L.w { line } },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val settleRunnable = Runnable { onSettled() }
    private val verifyRunnable = Runnable { onVerify() }
    private val overrideRetryRunnable = Runnable { retryOverride() }
    private val instanceId = System.identityHashCode(activity)

    /** The last panel this instance armed for; reset in [onStop] so the onStart replay arms again. */
    private var armedPanelKey: String? = null

    /**
     * Host display changed or replayed at onStart. Arms the settled check only when the panel
     * changed: refresh-rate and other same-panel display events must not postpone it. Guards are
     * evaluated when the check fires, because the onStart replay arrives before the host has
     * marked itself started.
     */
    fun onHostDisplayChanged() {
        val panel = readPanel() ?: return
        if (panel.key == armedPanelKey) return
        armedPanelKey = panel.key
        StaleLayoutOverride.clearIfNotFor(panel.key)
        retireEpisodesNotFor(panel.key)
        cancel()
        handler.postDelayed(settleRunnable, STALE_SETTLE_MS)
    }

    fun onStop() {
        cancel()
        armedPanelKey = null
    }

    fun detach() = cancel()

    private fun cancel() {
        handler.removeCallbacks(settleRunnable)
        handler.removeCallbacks(verifyRunnable)
        handler.removeCallbacks(overrideRetryRunnable)
    }

    private fun canAct(): Boolean =
        !activity.isFinishing && !activity.isDestroyed && !activity.isChangingConfigurations && isStarted() &&
            !activity.isInMultiWindowMode && !activity.isInPictureInPictureMode

    private fun onSettled() {
        if (!canAct()) return
        val panel = readPanel() ?: return
        val override = activeOverridePanel
        if (override != null && override != panel.key) {
            exitOverride(override, panel.key)
            return
        }
        val config = baseConfiguration()
        if (reconciler.reconcile("panelChange") == PaneRebuildOutcome.PROCEED) {
            noteInPlaceArrival(config)
            return
        }
        val tree = DualPaneReconciler.treeIsDualPane(activity)
        if (StaleLayoutDecision.isStale(tree, config, panel)) {
            if (staleReportedPanelKey != panel.key) {
                staleReportedPanelKey = panel.key
                verifyPending = VerifyPending(panel.key, SystemClock.uptimeMillis(), instanceId, config.smallestScreenWidthDp)
                logDetected(panel, config, tree)
            }
        } else if (!StaleLayoutDecision.exceeds(config, panel)) {
            // The platform's configuration fits the panel: any episode is over, also one on a
            // panel the device has since left, so a later stale fold there is detected again.
            staleReportedPanelKey = null
            overrideTriedPanelKey = null
        }
        scheduleVerify(panel.key)
    }

    private fun scheduleVerify(panelKey: String) {
        val pending = verifyPending ?: return
        if (pending.panelKey != panelKey) {
            // The device left the detected panel; there is nothing left to verify.
            verifyPending = null
            return
        }
        handler.removeCallbacks(verifyRunnable)
        val delay = pending.detectedAtMs + STALE_VERIFY_MS - SystemClock.uptimeMillis()
        handler.postDelayed(verifyRunnable, delay.coerceAtLeast(0L))
    }

    private fun onVerify() {
        // Not able to act now: stays pending, the next onStart replay picks it up.
        if (!canAct()) return
        val pending = verifyPending ?: return
        val panel = readPanel() ?: return
        if (pending.panelKey != panel.key) {
            verifyPending = null
            return
        }
        val config = baseConfiguration()
        when (reconciler.reconcile("staleVerify")) {
            // The resulting instance verifies.
            PaneRebuildOutcome.PROCEED -> {
                noteInPlaceArrival(config)
                return
            }
            PaneRebuildOutcome.THROTTLED, PaneRebuildOutcome.DEFERRED -> {
                handler.postDelayed(verifyRunnable, STALE_VERIFY_RETRY_MS)
                return
            }
            PaneRebuildOutcome.NONE, PaneRebuildOutcome.BLOCKED -> Unit
        }
        val tree = DualPaneReconciler.treeIsDualPane(activity)
        val gate = reconciler.gateIsDualPane()
        val viaOverride = activeOverridePanel == panel.key
        val fits = !StaleLayoutDecision.exceeds(config, panel)
        val healed = if (viaOverride) fits || gate == tree else fits && gate == tree
        val arrived = when {
            pending.arrivedInPlace -> "inplace"
            pending.instanceId != instanceId -> "relaunch"
            config.smallestScreenWidthDp != pending.cfgSw -> "inplace"
            else -> "none"
        }
        verifyPending = null
        val line = "[IndexActivity] staleLayout result panel=${panel.key} arrived=$arrived healed=$healed " +
            "dualPane=$tree sinceMs=${SystemClock.uptimeMillis() - pending.detectedAtMs}" +
            if (viaOverride) " via=override" else ""
        warn(line)
        // Whatever arrived says: a local recreation (theme) is a relaunch that kept the stale configuration.
        if (!viaOverride && overrideTriedPanelKey != panel.key && StaleLayoutDecision.isStale(tree, config, panel)) {
            recreateWithOverride(panel, config)
        }
    }

    /** A same-instance configuration change since detection is an in-place arrival; keep that for the result. */
    private fun noteInPlaceArrival(config: Configuration) {
        val pending = verifyPending ?: return
        if (pending.instanceId == instanceId && config.smallestScreenWidthDp != pending.cfgSw) {
            verifyPending = pending.copy(arrivedInPlace = true)
        }
    }

    private fun recreateWithOverride(panel: OrientationPolicy.HostPanel, config: Configuration) {
        StaleLayoutOverride.record(panel)
        // A rebuild that does not proceed clears the record (DualPaneReconciler) and leaves the
        // panel unmarked; a throttled one is retried once the throttle window has passed, a
        // breaker-blocked one waits for the next episode.
        when (reconciler.rebuildForStaleLayout()) {
            PaneRebuildOutcome.PROCEED -> {
                overrideTriedPanelKey = panel.key
                verifyPending = VerifyPending(panel.key, SystemClock.uptimeMillis(), instanceId, config.smallestScreenWidthDp)
            }
            PaneRebuildOutcome.THROTTLED -> handler.postDelayed(overrideRetryRunnable, STALE_VERIFY_RETRY_MS)
            PaneRebuildOutcome.NONE, PaneRebuildOutcome.DEFERRED, PaneRebuildOutcome.BLOCKED -> Unit
        }
    }

    private fun retryOverride() {
        if (!canAct()) return
        val panel = readPanel() ?: return
        val config = baseConfiguration()
        val tree = DualPaneReconciler.treeIsDualPane(activity)
        if (activeOverridePanel == null && overrideTriedPanelKey != panel.key && StaleLayoutDecision.isStale(tree, config, panel)) {
            recreateWithOverride(panel, config)
        }
    }

    /**
     * The override pins its panel's size for this instance's life, re-applied over any in-place
     * configuration. On another panel (unfolded in place) recreate without it, directly: the
     * rebuild throttle or an open breaker would drop the exit silently. Only from the settled
     * check, which dies with the instance if the platform relaunches first.
     */
    private fun exitOverride(old: String, new: String) {
        StaleLayoutOverride.clear()
        activeOverridePanel = null
        val line = "[IndexActivity] staleLayout override exit panel=$old->$new"
        warn(line)
        recreate()
    }

    private fun logDetected(panel: OrientationPolicy.HostPanel, config: Configuration, tree: Boolean) {
        val px = StaleLayoutDecision.ConfigPx.of(config)
        val root = activity.window?.decorView
        val line = "[IndexActivity] staleLayout detected panel=${panel.key} panelDp=${panel.widthDp}x${panel.heightDp} " +
            "configDp=${config.screenWidthDp}x${config.screenHeightDp} cfgPx=${px.widthPx}x${px.heightPx} " +
            "rootPx=${root?.width}x${root?.height} requested=${activity.requestedOrientation} " +
            "gate=${reconciler.gateIsDualPane()} tree=$tree"
        warn(line)
    }

    private data class VerifyPending(
        val panelKey: String,
        val detectedAtMs: Long,
        val instanceId: Int,
        val cfgSw: Int,
        val arrivedInPlace: Boolean = false,
    )

    companion object {
        const val STALE_SETTLE_MS = 1_000L
        const val STALE_VERIFY_MS = 3_000L
        private const val STALE_VERIFY_RETRY_MS = 1_100L

        // Process-level: an episode spans the recreations it causes or observes.
        @Volatile
        private var staleReportedPanelKey: String? = null

        @Volatile
        private var verifyPending: VerifyPending? = null

        /** Set only when the override recreate proceeded: once per panel per episode. */
        @Volatile
        private var overrideTriedPanelKey: String? = null

        /**
         * Leaving a panel ends its episode: an unfold followed by a quick refold (before the
         * healthy settled check ran) must detect, verify and heal the new stale fold again.
         */
        private fun retireEpisodesNotFor(panelKey: String) {
            if (staleReportedPanelKey != null && staleReportedPanelKey != panelKey) staleReportedPanelKey = null
            if (overrideTriedPanelKey != null && overrideTriedPanelKey != panelKey) overrideTriedPanelKey = null
            if (verifyPending?.panelKey?.let { it != panelKey } == true) verifyPending = null
        }

        @VisibleForTesting
        internal fun reportedPanelForTest(): String? = staleReportedPanelKey

        @VisibleForTesting
        internal fun resetProcessStateForTest() {
            staleReportedPanelKey = null
            verifyPending = null
            overrideTriedPanelKey = null
        }
    }
}
