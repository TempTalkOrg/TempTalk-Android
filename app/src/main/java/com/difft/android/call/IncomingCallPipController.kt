package com.difft.android.call

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.databinding.CallActivityIncomingCallBinding
import kotlinx.coroutines.launch

/**
 * Manages Picture-in-Picture mode for [LIncomingCallActivity].
 *
 * Extracted to keep the Activity within the project's 500-line limit.
 */
internal class IncomingCallPipController(
    private val activity: AppCompatActivity,
    private val binding: CallActivityIncomingCallBinding,
    private val onPipDismissed: () -> Unit,
) {
    private lateinit var pipParams: PictureInPictureParams.Builder
    var isInPipMode = false
        private set

    private val isPipAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 26 &&
                activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun initialize() {
        if (!isPipAvailable) return
        val aspectRatio = Rational(16, 9)
        pipParams = PictureInPictureParams.Builder().apply { setAspectRatio(aspectRatio) }

        if (Build.VERSION.SDK_INT >= 31) {
            activity.lifecycleScope.launch {
                activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        pipParams.setAutoEnterEnabled(true)
                        applyParams()
                    }
                }
            }
        } else {
            applyParams()
        }
    }

    fun enterIfPossible(tag: String? = null): Boolean {
        L.i { "[Call] LCallActivity enterPipModeIfPossible tag:$tag" }
        if (!isPipAvailable) return false
        return try {
            activity.enterPictureInPictureMode(pipParams.build())
            true
        } catch (e: Exception) {
            L.i { "[Call] enterPipModeIfPossible Device lied to us about supporting PiP, $e" }
            false
        }
    }

    fun onPipModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        if (activity.lifecycle.currentState == Lifecycle.State.CREATED) {
            activity.finishAndRemoveTask()
            onPipDismissed()
        }

        binding.windowZoomOut.isVisible = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.acceptCallBtn.visibility = View.INVISIBLE
            binding.rejectCallBtn.visibility = View.INVISIBLE
        } else {
            binding.acceptCallBtn.visibility = View.VISIBLE
            binding.rejectCallBtn.visibility = View.VISIBLE
        }
    }

    private fun applyParams() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                activity.setPictureInPictureParams(pipParams.build())
            } catch (e: Exception) {
                L.i { "[call] tryToSetPictureInPictureParams System lied about having PiP available. $e" }
            }
        }
    }
}
