package com.difft.android.chat.ui

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewParent
import android.widget.FrameLayout
import com.difft.android.base.log.lumberjack.L
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Defensive subclass of [FrameLayout] that swallows [NullPointerException] thrown by
 * `FrameLayout.layoutChildren` when `mChildren[i]` is transiently null during a
 * re-entrant layout pass.
 *
 * **Known crash:** Firebase Crashlytics issue `32a90db4e84c4cbc05024a1f2ad8727b`
 *  - Stack: `FrameLayout.layoutChildren -> View.getVisibility()` → NPE
 *  - Repros on Android 16 / OPPO ColorOS 16 most frequently, but also observed on
 *    Android 13 / 14 (vivo). Any Android version is vulnerable; Android 16 only
 *    raises the probability.
 *
 * **Scope of defense:** [onLayout] only. We do NOT wrap [onMeasure] — the current
 * crash signature never stops there, and a catch-all there would mask unrelated
 * real bugs in the future.
 *
 * **No self-recovery:** we do NOT `post { requestLayout() }` and do NOT `invalidate()`
 * in the catch block. If the re-entrant condition persists, any recovery attempt
 * would re-enter the same layout pass and risk a loop. The next natural layout pass
 * (scroll, new message bind, text input change, etc.) will redraw correctly.
 *
 * **Reporting:** each catch is reported as a NON_FATAL via `FirebaseCrashlytics.recordException`
 * and logged via `L.e` (per `.claude/rules/logging-standards.md`). A 10-second per-instance
 * throttle prevents runaway flooding if the catch path hits every layout pass on a
 * sustained re-entrant condition.
 */
class SafeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Last reporting timestamp (monotonic clock). Access is confined to the UI thread
     * (layout pass runs on main thread), so no `@Volatile` / atomic is required.
     */
    private var lastReportedAtMs: Long = 0L

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            super.onLayout(changed, left, top, right, bottom)
        } catch (e: NullPointerException) {
            // AOSP FrameLayout.layoutChildren iterates mChildren[] without a null-check;
            // a re-entrant layout pass (e.g. setPaddingRelative / requestLayout inside
            // an ancestor's onMeasure) can leave mChildren transiently inconsistent.
            // The next natural layout pass will redraw. See issue 32a90db4.
            reportThrottled(e)
        }
    }

    private fun reportThrottled(e: NullPointerException) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportedAtMs < REPORT_COOLDOWN_MS) return
        lastReportedAtMs = now

        // Wrap in try-catch: parentChainSnapshot / childSnapshot walk a view tree that
        // is already inconsistent (that's why we're here) and can themselves throw —
        // telemetry must not escalate the caught NPE into a new fatal.
        try {
            val idLabel = resolveIdLabel()
            val parentChain = parentChainSnapshot()
            val childSnapshot = childSnapshot()
            val msg = "[SafeFrameLayout] onLayout NPE swallowed " +
                    "id=$idLabel parents=$parentChain childCount=$childCount $childSnapshot"
            L.e { "$msg: ${e.stackTraceToString()}" }
            // Diagnostics live in the exception message — setCustomKey is process-global
            // and would pollute unrelated crashes with stale values.
            FirebaseCrashlytics.getInstance().recordException(Exception(msg, e))
        } catch (t: Exception) {
            // Catch Exception (not Throwable) — let OOM / StackOverflowError propagate so JVM-level
            // failures aren't silently swallowed by telemetry.
            L.e { "[SafeFrameLayout] reportThrottled failed: ${t.stackTraceToString()}" }
        }
    }

    // Crashlytics truncates the stack at ~19 frames with no com.difft.* visible,
    // so the parent chain is the primary signal for locating the crashing view tree.
    private fun parentChainSnapshot(): String {
        val sb = StringBuilder()
        var p: ViewParent? = parent
        var hops = 0
        while (p != null && hops < MAX_PARENT_HOPS) {
            if (sb.isNotEmpty()) sb.append('>')
            val view = p as? View
            sb.append(view?.javaClass?.simpleName ?: p.javaClass.simpleName)
            view?.id?.takeIf { it != NO_ID }?.let { id ->
                sb.append('#').append(safeResourceName(id))
            }
            p = p.parent
            hops++
        }
        return sb.ifEmpty { "(no-parent)" }.toString()
    }

    private fun childSnapshot(): String {
        val sb = StringBuilder("children=[")
        for (i in 0 until childCount) {
            if (i > 0) sb.append(',')
            val c = try {
                getChildAt(i)
            } catch (_: Exception) {
                null
            }
            if (c == null) {
                sb.append("null")
            } else {
                sb.append(c.javaClass.simpleName)
                sb.append('@').append(visibilityChar(c.visibility))
            }
        }
        sb.append(']')
        return sb.toString()
    }

    private fun visibilityChar(v: Int): Char = when (v) {
        VISIBLE -> 'V'
        INVISIBLE -> 'I'
        GONE -> 'G'
        else -> '?'
    }

    private fun safeResourceName(id: Int): String =
        try {
            resources.getResourceEntryName(id)
        } catch (_: Resources.NotFoundException) {
            "0x${Integer.toHexString(id)}"
        } catch (_: NullPointerException) {
            "0x${Integer.toHexString(id)}"
        }

    private fun resolveIdLabel(): String =
        if (id == NO_ID) "NO_ID" else safeResourceName(id)

    companion object {
        /** Per-instance throttle window. Tuned for runtime volume, not for user-visible latency. */
        private const val REPORT_COOLDOWN_MS = 10_000L

        /** Cap parent-chain walk to avoid unbounded work in pathological hierarchies. */
        private const val MAX_PARENT_HOPS = 8
    }
}
