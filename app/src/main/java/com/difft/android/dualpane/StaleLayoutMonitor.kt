package com.difft.android.dualpane

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.OrientationPolicy






















internal class StaleLayoutMonitor(
    private val activity: FragmentActivity,
    private val reconciler: DualPaneReconciler,
    private val isStarted: () -> Boolean,
    
    private var activeOverridePanel: String?,
    private val readPanel: () -> OrientationPolicy.HostPanel? = {
        OrientationPolicy.readHostPanel(activity, ContextCompat.getDisplayOrDefault(activity).displayId)
    },
    private val baseConfiguration: () -> Configuration = { platformConfiguration(activity) },
    private val recreate: () -> Unit = activity::recreate,
    
    private val warn: (String) -> Unit = { line -> L.w { line } },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val settleRunnable = Runnable { onSettled() }
    private val verifyRunnable = Runnable { onVerify() }
    private val overrideRetryRunnable = Runnable { retryOverride() }
    private val instanceId = System.identityHashCode(activity)

    
    private var armedPanelKey: String? = null

    





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
            
            
            staleReportedPanelKey = null
            overrideTriedPanelKey = null
        }
        scheduleVerify(panel.key)
    }

    private fun scheduleVerify(panelKey: String) {
        val pending = verifyPending ?: return
        if (pending.panelKey != panelKey) {
            
            verifyPending = null
            return
        }
        handler.removeCallbacks(verifyRunnable)
        val delay = pending.detectedAtMs + STALE_VERIFY_MS - SystemClock.uptimeMillis()
        handler.postDelayed(verifyRunnable, delay.coerceAtLeast(0L))
    }

    private fun onVerify() {
        
        if (!canAct()) return
        val pending = verifyPending ?: return
        val panel = readPanel() ?: return
        if (pending.panelKey != panel.key) {
            verifyPending = null
            return
        }
        val config = baseConfiguration()
        when (reconciler.reconcile("staleVerify")) {
            
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
        
        if (!viaOverride && overrideTriedPanelKey != panel.key && StaleLayoutDecision.isStale(tree, config, panel)) {
            recreateWithOverride(panel, config)
        }
    }

    
    private fun noteInPlaceArrival(config: Configuration) {
        val pending = verifyPending ?: return
        if (pending.instanceId == instanceId && config.smallestScreenWidthDp != pending.cfgSw) {
            verifyPending = pending.copy(arrivedInPlace = true)
        }
    }

    private fun recreateWithOverride(panel: OrientationPolicy.HostPanel, config: Configuration) {
        StaleLayoutOverride.record(panel)
        
        
        
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

        
        @Volatile
        private var staleReportedPanelKey: String? = null

        @Volatile
        private var verifyPending: VerifyPending? = null

        
        @Volatile
        private var overrideTriedPanelKey: String? = null

        



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






private fun platformConfiguration(activity: FragmentActivity): Configuration {
    val base = activity.baseContext.resources.configuration
    val platformDpi = (activity as? BaseActivity)?.platformDensityDpi ?: 0
    if (platformDpi <= 0 || platformDpi == base.densityDpi) return base
    return Configuration(base).apply { densityDpi = platformDpi }
}
