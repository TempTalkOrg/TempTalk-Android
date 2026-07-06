package com.difft.android.chat.invite

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.log.lumberjack.L
import java.util.concurrent.Executors

/**
 * Owns the CameraX scan pipeline: [ProcessCameraProvider], a [Preview] + [ImageAnalysis] bound to
 * [lifecycleOwner], a dedicated single-thread analysis executor, and the main-thread result hop.
 *
 * [bindToLifecycle] subsumes the manual start/stop camera dance entirely — the camera is released
 * automatically on lifecycle destroy; [shutdown] makes the unbind/executor-teardown ordering
 * deterministic.
 */
class ScanCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onResult: (String) -> Unit, // delivered on the main thread
    private val onError: () -> Unit = {}, // camera open/bind failure; delivered on the main thread
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var analyzer: QrCodeAnalyzer? = null

    fun start() {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER // no distortion in either orientation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            // The Activity may have been destroyed during the async getInstance() gap (rapid
            // open→back, rotation recreate). Binding against a DESTROYED lifecycle would assign
            // cameraProvider without ever unbinding → camera-resource leak. Bail before assigning.
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            try {
                future.get().let { provider ->
                    cameraProvider = provider
                    bindUseCases(provider)
                }
            } catch (e: Exception) {
                // getInstance()/bindToLifecycle() failed → preview would stay a frozen black surface.
                // Surface it so the Activity can toast + finish (restores the old open-camera-error UX),
                // instead of silently leaving the user staring at a dead preview. Already on the main
                // thread (addListener uses the main executor), so onError can touch UI directly.
                L.e { "[Scan] camera bind failed: ${e.stackTraceToString()}" }
                onError()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        preview = Preview.Builder().setTargetRotation(rotation).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analyzer = QrCodeAnalyzer { text ->
            ContextCompat.getMainExecutor(context).execute { onResult(text) } // hop to main
        }.also { this.analyzer = it }
        // Request a higher analysis resolution (~1280x720) so small / distant codes carry enough pixels
        // to decode. CameraX 1.4.2 has no built-in auto-zoom (the old BGA view auto-zoomed) — true
        // auto-zoom is a follow-up; this is a mitigation. KEEP_ONLY_LATEST drops backlogged frames so
        // the higher resolution does not blow up CPU. ResolutionStrategy snaps to the closest supported
        // size at-or-above the target (FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER).
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
        )
        L.i { "[Scan] camera bound rotation=$rotation" }
    }

    /**
     * Updates the target rotation from an OrientationEventListener for rotations that do NOT trigger a
     * config-change recreate (e.g. 180° flip, reverse-portrait on some devices).
     *
     * [surfaceRotation] MUST be a [Surface] ROTATION_* constant, not raw sensor degrees; the caller
     * maps via `Int.toSurfaceRotation()` (see ScanActivity).
     */
    fun updateTargetRotation(surfaceRotation: Int) {
        preview?.targetRotation = surfaceRotation
        imageAnalysis?.targetRotation = surfaceRotation
    }

    /**
     * Re-enables scanning after a one-shot decode whose result branch did not finish the Activity.
     * Called from the Activity's onResume (a lifecycle edge), never per-frame. No-op until the camera
     * has bound and an analyzer exists.
     */
    fun rearm() {
        analyzer?.rearm()
    }

    fun shutdown() {
        // Order matters: unbind the camera FIRST (stops the analyzer dispatching new frames to the
        // executor), THEN shut the executor down — prevents a RejectedExecutionException from an
        // in-flight frame being submitted to an already-shut-down executor.
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
