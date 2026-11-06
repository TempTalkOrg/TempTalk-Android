package com.difft.android.base.utils

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.window.layout.WindowMetricsCalculator
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Field diagnostics for #1454 (a fold that leaves an activity on the other panel's layout for ~40 s).
 * After a panel change it samples what the activity was told (configuration, window, root) next to
 * the panel and main-looper latency, to tell "the platform withheld the configuration or relaunch"
 * from "the main thread stalled". Log-only: it never writes orientation, recreates or reconciles.
 *
 * Every value is read on the main thread and formatted before `L`; androidx.window is touched there too.
 * The one exception is the stall sampler, which reads the main thread's stack from a background coroutine.
 */
object FoldDiagnostics {

    /** Activity-specific extras appended to each snapshot line. */
    interface Host {
        fun foldDiagExtras(): String
    }

    private const val STALL_WAIT_MS = 1_000L

    private val throttle = FoldDiagThrottle()
    private val seqCounter = AtomicInteger()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val stallScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var latest: FoldDiagSession? = null

    /** Starts a session for [activity] after its host panel changed [from] -> [to]. Main thread. */
    fun onPanelChanged(activity: Activity, display: Display, from: String, to: String) {
        val now = SystemClock.uptimeMillis()
        when (throttle.tryStart(now)) {
            FoldDiagThrottle.Decision.DROP -> return
            FoldDiagThrottle.Decision.THROTTLED -> {
                L.i { "[Fold] diag throttled" }
                return
            }
            FoldDiagThrottle.Decision.START -> Unit
        }
        val session = FoldDiagSession(seqCounter.incrementAndGet(), now, "$from->$to", WeakReference(activity))
        latest = session
        snapshot(activity, session, offsetMs = 0L, lagMs = 0L, display = display)
        FOLD_DIAG_OFFSETS_MS.forEach { schedule(session, it) }
    }

    /**
     * A configuration reached [activity] ([via] `onConfigurationChanged`, or `create` for a relaunch)
     * while a session is live. [restored]: created from saved state.
     */
    fun onConfigurationArrived(activity: Activity, via: String, restored: Boolean = false) {
        val session = latest ?: return
        val now = SystemClock.uptimeMillis()
        if (!session.isLive(now) || !session.tryConfigLine()) return
        try {
            val cfg = activity.resources.configuration
            val line = "[Fold] diag configArrived seq=${session.seq} via=$via sinceMs=${now - session.startUptime} " +
                "cls=${activity.javaClass.simpleName} inst=${instanceId(activity)} cfgSw=${cfg.smallestScreenWidthDp} " +
                "cfgDp=${cfg.screenWidthDp}x${cfg.screenHeightDp} restored=$restored"
            L.i { line }
        } catch (e: Exception) {
            val trace = e.stackTraceToString()
            L.e { "[Fold] diag configArrived failed error=$trace" }
        }
    }

    private fun schedule(session: FoldDiagSession, offsetMs: Long) {
        val planned = session.startUptime + offsetMs
        val ran = AtomicBoolean(false)
        handler.postAtTime({
            ran.set(true)
            runSnapshot(session, offsetMs, planned)
        }, session.token, planned)
        // Main-looper latency probe: a snapshot still pending a second after its time samples the main stack.
        stallScope.launch {
            delay(planned + STALL_WAIT_MS - SystemClock.uptimeMillis())
            if (!ran.get() && !session.destroyed.get() && session.tryStallLine()) {
                val waited = SystemClock.uptimeMillis() - planned
                val stack = formatStack(Looper.getMainLooper().thread.stackTrace)
                val line = "[Fold] diag mainStall seq=${session.seq} t=$offsetMs waitedMs=$waited stack=$stack"
                L.w { line }
            }
        }
    }

    private fun runSnapshot(session: FoldDiagSession, offsetMs: Long, planned: Long) {
        val lagMs = SystemClock.uptimeMillis() - planned
        val activity = session.activity.get()
        if (activity == null || activity.isDestroyed) {
            if (session.destroyed.compareAndSet(false, true)) {
                handler.removeCallbacksAndMessages(session.token)
                if (session.trySnapshotLine()) {
                    val line = "[Fold] diag snap seq=${session.seq} t=$offsetMs lagMs=$lagMs destroyed=true"
                    L.i { line }
                }
            }
            return
        }
        val sides = snapshot(activity, session, offsetMs, lagMs, display = null) ?: return
        if (offsetMs == FOLD_DIAG_LONG_TAIL_DECIDER_MS &&
            needsLongTail(activity.resources.configuration.smallestScreenWidthDp, sides.modeDp, activity.isInMultiWindowMode)
        ) {
            FOLD_DIAG_LONG_TAIL_MS.forEach { schedule(session, it) }
        }
    }

    /** Logs one snapshot line and returns the short sides it read; null when nothing was logged. */
    private fun snapshot(activity: Activity, session: FoldDiagSession, offsetMs: Long, lagMs: Long, display: Display?): OrientationPolicy.ShortSides? {
        if (!session.trySnapshotLine()) return null
        return try {
            val sides = OrientationPolicy.readShortSides(activity, display ?: ContextCompat.getDisplayOrDefault(activity))
            val cfg = activity.resources.configuration
            val win = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
            val root = activity.window?.decorView
            val header = FoldDiagHeader(
                seq = session.seq,
                offsetMs = offsetMs,
                lagMs = lagMs,
                cls = activity.javaClass.simpleName,
                inst = instanceId(activity),
                life = (activity as? LifecycleOwner)?.lifecycle?.currentState?.name.toString(),
                finishing = activity.isFinishing,
                panel = session.panel,
            )
            val window = FoldDiagWindow(
                density = activity.resources.displayMetrics.density,
                cfgWidthDp = cfg.screenWidthDp,
                cfgHeightDp = cfg.screenHeightDp,
                cfgSw = cfg.smallestScreenWidthDp,
                cfgOrientation = cfg.orientation,
                winPx = "${win.width()}x${win.height()}",
                rootPx = "${root?.width}x${root?.height}",
            )
            val policy = FoldDiagPolicy(
                sides = sides,
                // Logged even where applyTo would return early unchanged.
                target = OrientationPolicy.targetForDisplayShortSide(sides.resolvedDp),
                requested = activity.requestedOrientation,
                multiWindow = activity.isInMultiWindowMode,
                pip = activity.isInPictureInPictureMode,
                unfoldPending = OrientationPolicy.isUnfoldPending(),
            )
            val extras = (activity as? Host)?.foldDiagExtras().orEmpty()
            val line = formatFoldSnapshot(header, window, policy, extras)
            L.i { line }
            sides
        } catch (e: Exception) {
            val trace = e.stackTraceToString()
            L.e { "[Fold] diag snap failed seq=${session.seq} t=$offsetMs error=$trace" }
            null
        }
    }

    private fun instanceId(activity: Activity): String = Integer.toHexString(System.identityHashCode(activity))
}
