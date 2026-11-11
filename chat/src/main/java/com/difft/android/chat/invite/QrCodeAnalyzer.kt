package com.difft.android.chat.invite

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.qr.QrDecoder
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.atomic.AtomicBoolean








class QrCodeAnalyzer(
    private val onDecoded: (String) -> Unit, 
) : ImageAnalysis.Analyzer {

    private val decoder = QrDecoder()
    private val handled = AtomicBoolean(false)
    @Volatile
    private var loggedErrorThisRun = false

    
    @Volatile
    private var ignored: String? = null

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        if (handled.get()) {
            image.close() 
            return
        }
        try {
            
            
            
            val text = runCatching { image.toLumaSource()?.let { decoder.decode(it) } }.getOrElse { e ->
                if (!loggedErrorThisRun) {
                    loggedErrorThisRun = true
                    L.w { "[Scan] decode threw (suppressing repeats): ${e.message}" }
                }
                null
            }
            if (text != null && text != ignored && handled.compareAndSet(false, true)) {
                L.i { "[Scan] decoded len=${text.length}" } 
                onDecoded(text)
            }
        } finally {
            image.close() 
        }
    }

    






    fun rearm() {
        loggedErrorThisRun = false
        handled.set(false)
    }

    
    fun skip(text: String) {
        ignored = text
        handled.set(false)
    }

    






    @OptIn(ExperimentalGetImage::class)
    private fun ImageProxy.toLumaSource(): PlanarYUVLuminanceSource? {
        val plane = planes.firstOrNull() ?: return null 
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val rowStride = plane.rowStride 
        if (rowStride <= 0) return null 
        
        
        
        
        val safeHeight = minOf(height, data.size / rowStride)
        if (safeHeight <= 0) return null
        
        val cropWidth = minOf(width, rowStride)
        return PlanarYUVLuminanceSource(
            data,
            rowStride, 
            safeHeight, 
            0,
            0,
            cropWidth,
            safeHeight,
            false,
        )
    }
}
