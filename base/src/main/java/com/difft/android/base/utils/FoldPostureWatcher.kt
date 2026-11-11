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








object FoldPostureWatcher {

    @Volatile
    private var lastLogged: String? = null

    fun attach(activity: ComponentActivity, onPosture: (FoldingFeature.State?) -> Unit = {}) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastState: FoldingFeature.State? = null
                var first = true
                
                
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
        
        
        val posture = if (folds.isEmpty()) "features=0" else folds.joinToString(" ") { "state=${it.state}" }
        if (posture == lastLogged) return
        lastLogged = posture
        val detail = folds.joinToString(" ") {
            "orientation=${it.orientation} occlusion=${it.occlusionType} bounds=${it.bounds.toShortString()}"
        }
        L.i { "[Fold] posture $posture $detail cls=$cls" }
    }
}
