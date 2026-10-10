package com.difft.android.base.utils

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Follows the foldable posture `WindowInfoTracker` reports for an activity and hands it to
 * [onPosture] once on each STARTED entry and then on every change (`null` = no folding
 * feature: a closed foldable, a phone, or a device without OEM window extensions). The
 * collector only runs while the activity is STARTED.
 *
 * On the Xiaomi 2608BPX34C the posture flipped to HALF_OPENED while the activity was still on
 * the outer panel, about 120 ms before the OS relaunched it on the inner display — early
 * enough for [OrientationPolicy] to release a portrait lock before the switch, which is what
 * [onPosture] is for. Multi-hinge devices report several features; the first one is handed on.
 *
 * Also the field diagnostic behind the `[Fold] posture` log line: one line per posture change
 * process-wide, plus the first report after process start as the positive control, so a silent
 * log reads as "no signal available", not "logging broken" (`features=0` is both a closed
 * foldable and a device without a folding feature; a later non-null line tells them apart).
 */
object FoldPostureWatcher {

    @Volatile
    private var lastLogged: String? = null

    fun attach(activity: ComponentActivity, onPosture: (FoldingFeature.State?) -> Unit = {}) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastState: FoldingFeature.State? = null
                var first = true
                // Must never take the app down: the flow is backed by the OEM's window
                // extensions, and a faulty vendor implementation may throw.
                try {
                    WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                        val folds = info.displayFeatures.filterIsInstance<FoldingFeature>()
                        log(folds, activity.javaClass.simpleName)
                        val state = folds.firstOrNull()?.state
                        if (first || state != lastState) {
                            first = false
                            lastState = state
                            deliver(state, onPosture, activity.javaClass.simpleName)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w { "[Fold] posture collection failed cls=${activity.javaClass.simpleName}: ${e.message}" }
                }
            }
        }
    }

    // A failing consumer must not end the collection (and the log) for the rest of this
    // STARTED span; it is reported on its own line.
    private fun deliver(state: FoldingFeature.State?, onPosture: (FoldingFeature.State?) -> Unit, cls: String) {
        try {
            onPosture(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[Fold] posture consumer failed cls=$cls state=$state: ${e.message}" }
        }
    }

    private fun log(folds: List<FoldingFeature>, cls: String) {
        // Keyed on the fold STATE only: bounds and orientation are window-relative and change on
        // every rotation, which is not a posture change.
        val posture = if (folds.isEmpty()) "features=0" else folds.joinToString(" ") { "state=${it.state}" }
        if (posture == lastLogged) return
        lastLogged = posture
        val detail = folds.joinToString(" ") {
            "orientation=${it.orientation} occlusion=${it.occlusionType} bounds=${it.bounds.toShortString()}"
        }
        L.i { "[Fold] posture $posture $detail cls=$cls" }
    }
}
