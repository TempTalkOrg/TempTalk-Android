package com.difft.android.base.utils

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Pure parts of [FoldDiagnostics] (#1454): caps, throttle, long-tail rule and line format. */

/** Snapshot offsets after a panel change, ms; +0 runs synchronously. */
internal val FOLD_DIAG_OFFSETS_MS = longArrayOf(500L, 2_000L, 5_000L)

/** Extra offsets while the activity configuration still describes the other panel at +5000. */
internal val FOLD_DIAG_LONG_TAIL_MS = longArrayOf(15_000L, 40_000L)

/** The offset whose snapshot decides the long tail. */
internal const val FOLD_DIAG_LONG_TAIL_DECIDER_MS = 5_000L

/** A short-side gap above this between the activity configuration and the panel means "still on the other panel". */
internal const val FOLD_DIAG_STALE_GAP_DP = 48

/**
 * Whether to keep sampling after +5000: outside multi-window, the activity's smallest width still
 * differs from the panel's short side by more than [FOLD_DIAG_STALE_GAP_DP]. The incident lasted ~40 s.
 */
internal fun needsLongTail(cfgSw: Int, modeDp: Int, multiWindow: Boolean): Boolean =
    !multiWindow && modeDp > 0 && abs(cfgSw - modeDp) > FOLD_DIAG_STALE_GAP_DP

/** One diagnostic session: the snapshots after one panel change of one activity instance. */
internal class FoldDiagSession(
    val seq: Int,
    val startUptime: Long,
    val panel: String,
    val activity: WeakReference<Activity>,
) {
    /** Handler token for this session's posted snapshots. */
    val token = Any()
    val destroyed = AtomicBoolean(false)
    private val snapshots = AtomicInteger()
    private val stalls = AtomicInteger()
    private val configs = AtomicInteger()

    fun trySnapshotLine(): Boolean = snapshots.incrementAndGet() <= MAX_SNAPSHOT_LINES
    fun tryStallLine(): Boolean = stalls.incrementAndGet() <= MAX_STALL_LINES
    fun tryConfigLine(): Boolean = configs.incrementAndGet() <= MAX_CONFIG_LINES
    fun isLive(now: Long): Boolean = now - startUptime <= SESSION_WINDOW_MS

    companion object {
        /** +0, +500, +2000, +5000, two long-tail offsets and the destroyed line. */
        const val MAX_SNAPSHOT_LINES = 7
        const val MAX_STALL_LINES = 1
        const val MAX_CONFIG_LINES = 6

        /** configArrived lines are logged this long after +0. */
        const val SESSION_WINDOW_MS = 60_000L
    }
}

/** At most [maxSessions] sessions start per [windowMs]; beyond that one throttled line per window. */
internal class FoldDiagThrottle(private val maxSessions: Int = 3, private val windowMs: Long = 30_000L) {
    enum class Decision { START, THROTTLED, DROP }

    private val starts = ArrayDeque<Long>()
    private var throttledWindow: Long? = null

    @Synchronized
    fun tryStart(now: Long): Decision {
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
        if (starts.size < maxSessions) {
            starts.addLast(now)
            return Decision.START
        }
        // The window is keyed by its oldest start, so the line repeats only once that start ages out.
        val window = starts.first()
        if (throttledWindow == window) return Decision.DROP
        throttledWindow = window
        return Decision.THROTTLED
    }
}

/** Identity of a snapshot: session, timing and the activity instance. */
internal data class FoldDiagHeader(
    val seq: Int,
    val offsetMs: Long,
    val lagMs: Long,
    val cls: String,
    val inst: String,
    val life: String,
    val finishing: Boolean,
    val panel: String,
)

/** What the activity was told: its configuration, window and root view. */
internal data class FoldDiagWindow(
    val density: Float,
    val cfgWidthDp: Int,
    val cfgHeightDp: Int,
    val cfgSw: Int,
    val cfgOrientation: Int,
    val winPx: String,
    val rootPx: String,
)

/** What the orientation policy would decide now (read-only), and the platform modes. */
internal data class FoldDiagPolicy(
    val sides: OrientationPolicy.ShortSides,
    val target: Int,
    val requested: Int,
    val multiWindow: Boolean,
    val pip: Boolean,
    val unfoldPending: Boolean,
)

/**
 * `[Fold] diag snap seq=3 t=500 lagMs=4 cls=IndexActivity inst=1a2b3c life=STARTED fin=false panel=A->B
 * den=2.75 cfgDp=608x859 cfgSw=608 cfgOri=1 winPx=1168x1712 rootPx=1168x1712 modeDp=424 boundsDp=424
 * windowDp=608 resolvedDp=608 target=-1 req=-1 mw=false pip=false unfoldPending=false <extras>`.
 */
internal fun formatFoldSnapshot(h: FoldDiagHeader, w: FoldDiagWindow, p: FoldDiagPolicy, extras: String): String = buildString {
    append("[Fold] diag snap seq=").append(h.seq).append(" t=").append(h.offsetMs).append(" lagMs=").append(h.lagMs)
    append(" cls=").append(h.cls).append(" inst=").append(h.inst).append(" life=").append(h.life)
    append(" fin=").append(h.finishing).append(" panel=").append(h.panel)
    append(" den=").append(w.density).append(" cfgDp=").append(w.cfgWidthDp).append('x').append(w.cfgHeightDp)
    append(" cfgSw=").append(w.cfgSw).append(" cfgOri=").append(w.cfgOrientation)
    append(" winPx=").append(w.winPx).append(" rootPx=").append(w.rootPx)
    append(" modeDp=").append(p.sides.modeDp).append(" boundsDp=").append(p.sides.boundsDp)
    append(" windowDp=").append(p.sides.windowDp).append(" resolvedDp=").append(p.sides.resolvedDp)
    append(" target=").append(p.target).append(" req=").append(p.requested)
    append(" mw=").append(p.multiWindow).append(" pip=").append(p.pip).append(" unfoldPending=").append(p.unfoldPending)
    if (extras.isNotEmpty()) append(' ').append(extras.trim())
}

/** Main-thread stack frames as `Class.method:line|…`: no arguments or user data. */
internal fun formatStack(frames: Array<StackTraceElement>, limit: Int = 12): String =
    frames.take(limit).joinToString("|") { "${it.className}.${it.methodName}:${it.lineNumber}" }
