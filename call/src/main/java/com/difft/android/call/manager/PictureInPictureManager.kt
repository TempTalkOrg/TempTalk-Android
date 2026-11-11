package com.difft.android.call.manager

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Rational
import androidx.lifecycle.Lifecycle
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PictureInPictureManager(
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val onPipModeChanged: (Boolean) -> Unit
) {
    private lateinit var pipBuilderParams: PictureInPictureParams.Builder
    private var isInitialized = false
    private var isPipParamsSet = false
    private var pipParamsJob: Job? = null
    private var currentAspectRatio: Rational? = null

    
    fun initialize() {
        if (isInitialized) {
            L.w { "[Call] PictureInPictureManager already initialized" }
            return
        }

        if (!isSystemPipEnabledAndAvailable()) {
            L.i { "[Call] PictureInPictureManager: PIP not available on this device" }
            return
        }

        pipBuilderParams = PictureInPictureParams.Builder()
        pipBuilderParams.setAspectRatio(DEFAULT_ASPECT_RATIO)
        currentAspectRatio = DEFAULT_ASPECT_RATIO

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pipBuilderParams.setAutoEnterEnabled(false)
        }

        pipParamsJob = scope.launch(Dispatchers.Main) { tryToSetPictureInPictureParams() }

        isInitialized = true
        L.i { "[Call] PictureInPictureManager initialized" }
    }

    fun isSystemPipEnabledAndAvailable(): Boolean {
        return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    fun enterPipMode(tag: String? = null): Boolean {
        L.i { "[Call] PictureInPictureManager enterPipMode tag:$tag" }

        if (!isSystemPipEnabledAndAvailable()) {
            L.w { "[Call] PictureInPictureManager: PIP not available" }
            return false
        }

        if (!isInitialized) {
            L.w { "[Call] PictureInPictureManager: Not initialized, cannot enter PIP mode" }
            return false
        }

        return try {
            activity.enterPictureInPictureMode(pipBuilderParams.build())
            L.i { "[Call] PictureInPictureManager: Successfully entered PIP mode" }
            true
        } catch (e: Exception) {
            L.e { "[Call] PictureInPictureManager: Failed to enter PIP mode: ${e.message}" }
            false
        }
    }

    




    fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        L.i { "[Call] PictureInPictureManager onPictureInPictureModeChanged isInPictureInPictureMode:$isInPictureInPictureMode" }

        activity.runOnUiThread {
            onPipModeChanged(isInPictureInPictureMode)
        }

        if (!isInPictureInPictureMode) {
            
            
            
            val stopped = lifecycle.currentState == Lifecycle.State.CREATED
            L.i { "[Call] PictureInPictureManager: PIP window gone, stopped=$stopped, call continues" }
        }
    }

    



    fun updateAspectRatio(aspect: Float?) {
        if (!isInitialized) return
        val ratio = aspect?.let {
            val clamped = it.coerceIn(MIN_ASPECT, MAX_ASPECT)
            Rational((clamped * ASPECT_QUANTUM).roundToInt(), ASPECT_QUANTUM)
        } ?: DEFAULT_ASPECT_RATIO
        if (ratio == currentAspectRatio) return
        val previous = currentAspectRatio ?: DEFAULT_ASPECT_RATIO
        pipBuilderParams.setAspectRatio(ratio)
        try {
            activity.setPictureInPictureParams(pipBuilderParams.build())
            currentAspectRatio = ratio
            L.i { "[Call] PictureInPictureManager updateAspect aspect=$ratio result=applied" }
        } catch (e: IllegalArgumentException) {
            
            pipBuilderParams.setAspectRatio(previous)
            L.w { "[Call] PictureInPictureManager updateAspect aspect=$ratio result=rejected ${e.stackTraceToString()}" }
        } catch (e: IllegalStateException) {
            pipBuilderParams.setAspectRatio(previous)
            L.w { "[Call] PictureInPictureManager updateAspect aspect=$ratio result=failed ${e.stackTraceToString()}" }
        }
    }

    
    private fun tryToSetPictureInPictureParams() {
        if (isPipParamsSet) return
        try {
            activity.setPictureInPictureParams(pipBuilderParams.build())
            isPipParamsSet = true
            L.i { "[Call] PictureInPictureManager: PIP params set successfully" }
        } catch (e: Exception) {
            L.e { "[Call] PictureInPictureManager: Failed to set PIP params: ${e.message}" }
        }
    }

    fun release() {
        isInitialized = false
        isPipParamsSet = false
        pipParamsJob?.cancel()
        pipParamsJob = null
        L.i { "[Call] PictureInPictureManager released" }
    }
}

private val DEFAULT_ASPECT_RATIO = Rational(16, 9)


private const val MIN_ASPECT = 1f / 2.3f
private const val MAX_ASPECT = 2.3f
private const val ASPECT_QUANTUM = 100
