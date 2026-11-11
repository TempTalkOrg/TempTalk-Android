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









class ScanCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onResult: (String) -> Unit, 
    private val onError: () -> Unit = {}, 
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var analyzer: QrCodeAnalyzer? = null

    fun start() {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER 
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            
            
            
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            try {
                future.get().let { provider ->
                    cameraProvider = provider
                    bindUseCases(provider)
                }
            } catch (e: Exception) {
                
                
                
                
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
            ContextCompat.getMainExecutor(context).execute { onResult(text) } 
        }.also { this.analyzer = it }
        
        
        
        
        
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

    






    fun updateTargetRotation(surfaceRotation: Int) {
        preview?.targetRotation = surfaceRotation
        imageAnalysis?.targetRotation = surfaceRotation
    }

    




    fun rearm() {
        analyzer?.rearm()
    }

    
    fun skip(text: String) {
        analyzer?.skip(text)
    }

    fun shutdown() {
        
        
        
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
