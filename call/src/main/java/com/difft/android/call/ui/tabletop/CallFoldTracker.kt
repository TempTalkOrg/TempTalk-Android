package com.difft.android.call.ui.tabletop

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The call activity's folding features as plain data, collected while it is STARTED. One per
 * activity instance; the call UI reads [reports] through `LocalCallFoldReports`.
 *
 * Kept apart from `FoldPostureWatcher`, which hands on only the first feature's state and
 * dedups its log process-wide for the orientation policy. Every androidx.window object is read
 * here on the main thread, never inside an `L` lambda (those run on the logger thread).
 */
internal class CallFoldTracker(private val activity: ComponentActivity) {
    private val _reports = MutableStateFlow<CallFoldReport?>(null)

    /** null until the first report. */
    val reports: StateFlow<CallFoldReport?> = _reports.asStateFlow()

    private var lastLogged: CallFoldReport? = null
    private var logged = 0

    fun attach() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // OEM window extensions back this flow and may throw; a call must never go down for it.
                try {
                    WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { publish(it.toReport()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message
                    L.w { "[Call] fold collection failed error=$message" }
                    publish(CallFoldReport(emptyList()))
                }
            }
        }
    }

    private fun publish(report: CallFoldReport) {
        _reports.value = report
        if (report == lastLogged || logged >= MAX_LOG_LINES) return
        lastLogged = report
        logged++
        val window = runCatching { WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds }.getOrNull()
        val line = formatFoldLog(report, window?.width(), window?.height())
        L.i { line }
    }

    private companion object {
        const val MAX_LOG_LINES = 40
    }
}

internal fun WindowLayoutInfo.toReport(): CallFoldReport =
    CallFoldReport(displayFeatures.filterIsInstance<FoldingFeature>().map { it.toCallFold() })

internal fun FoldingFeature.toCallFold(): CallFold {
    val b = bounds
    return CallFold(
        state = if (state == FoldingFeature.State.HALF_OPENED) CallFoldState.HALF_OPENED else CallFoldState.FLAT,
        horizontal = orientation == FoldingFeature.Orientation.HORIZONTAL,
        occluding = occlusionType == FoldingFeature.OcclusionType.FULL,
        bounds = PxRect(b.left, b.top, b.right, b.bottom),
    )
}

/** `[Call] fold n=1 f0=HALF_OPENED/H/occl=false/0,1182,1672,1182 winPx=1672x2364`. */
internal fun formatFoldLog(report: CallFoldReport, windowWidthPx: Int?, windowHeightPx: Int?): String = buildString {
    append("[Call] fold n=").append(report.folds.size)
    report.folds.forEachIndexed { i, f ->
        val b = f.bounds
        append(" f").append(i).append('=').append(f.state.name)
            .append('/').append(if (f.horizontal) 'H' else 'V')
            .append("/occl=").append(f.occluding)
            .append('/').append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom)
    }
    append(" winPx=").append(windowWidthPx).append('x').append(windowHeightPx)
}
