package com.difft.android.call

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
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
    // Reused scratch Rect to avoid per-call allocation in getGlobalVisibleRect().
    private val sourceRectScratch = Rect()
    var isInPipMode = false
        private set

    private val isPipAvailable: Boolean
        get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

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
        // Refresh source rect at entry so the transition crops from the latest layout.
        updateSourceRectHint()
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

    /**
     * Updates [pipParams] with the source rect hint for a smooth PIP transition.
     * Without this hint, Android falls back to a jarring corner-zoom animation;
     * with it, the system crops from the source view's screen-space bounds.
     *
     * Note: there's no video surface on this ringer screen — the content root is the
     * natural source. If the view isn't laid out yet (rect is empty), we skip the hint
     * and Android falls back to its default animation, which is still acceptable.
     */
    private fun updateSourceRectHint() {
        val root = binding.root
        if (!root.isLaidOut) {
            // First-layout retry: schedule a one-shot applyParams after the view lays out.
            // doOnLayout auto-removes the listener after firing, no leak / loop risk.
            root.doOnLayout { applyParams() }
            return
        }
        sourceRectScratch.setEmpty()
        if (!root.getGlobalVisibleRect(sourceRectScratch) || sourceRectScratch.isEmpty) return
        pipParams.setSourceRectHint(sourceRectScratch)
    }

    private fun applyParams() {
        try {
            updateSourceRectHint()
            activity.setPictureInPictureParams(pipParams.build())
        } catch (e: Exception) {
            L.i { "[call] tryToSetPictureInPictureParams System lied about having PiP available. $e" }
        }
    }
}
