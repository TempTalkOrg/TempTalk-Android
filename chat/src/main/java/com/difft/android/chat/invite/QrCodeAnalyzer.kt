package com.difft.android.chat.invite

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.qr.QrDecoder
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges CameraX YUV frames to [QrDecoder] and emits the FIRST successful decode.
 *
 * Confined to a single analysis thread (the dedicated executor [ScanCameraController] supplies);
 * owns one [QrDecoder] reused frame-to-frame. Never logs decoded contents at any level — only the
 * decoded length as a milestone.
 */
class QrCodeAnalyzer(
    private val onDecoded: (String) -> Unit, // invoked at most once, on the analysis thread
) : ImageAnalysis.Analyzer {

    private val decoder = QrDecoder()
    private val handled = AtomicBoolean(false)
    @Volatile
    private var loggedErrorThisRun = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        if (handled.get()) {
            image.close() // already hit — drain cheaply until the use case is unbound
            return
        }
        try {
            // Harden the whole frame-processing body: a malformed buffer can make either source
            // construction OR decode throw a non-NotFound error. Either way the loop must survive and
            // log at most once per run (NotFound is already swallowed inside QrDecoder.decode).
            val text = runCatching { image.toLumaSource()?.let { decoder.decode(it) } }.getOrElse { e ->
                if (!loggedErrorThisRun) {
                    loggedErrorThisRun = true
                    L.w { "[Scan] decode threw (suppressing repeats): ${e.message}" }
                }
                null
            }
            if (text != null && handled.compareAndSet(false, true)) {
                L.i { "[Scan] decoded len=${text.length}" } // length only — never content
                onDecoded(text)
            }
        } finally {
            image.close() // MUST close every frame or ImageAnalysis stalls after imageQueueDepth
        }
    }

    /**
     * Re-enables scanning after a one-shot decode. Called from ScanActivity.onResume() — a lifecycle
     * edge, NOT per-frame — so a result currently being handled is not re-processed in a tight loop.
     * Without this the latch stays set forever and an async result branch that does not finish the
     * Activity leaves the scanner permanently dead. The one-shot-per-foreground debounce is preserved:
     * within a single foreground session the latch still fires exactly once.
     */
    fun rearm() {
        loggedErrorThisRun = false
        handled.set(false)
    }

    /**
     * Y-plane → [PlanarYUVLuminanceSource]. Full-frame (matches the old isOnlyDecodeScanBoxArea=false).
     *
     * dataWidth is [androidx.camera.core.ImageProxy.PlaneProxy.getRowStride], NOT width: CameraX Y-plane
     * rows are often padded to a stride greater than width; using width would garble the luma so decode
     * never succeeds. The crop rect (0, 0, width, height) trims that row padding back out.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun ImageProxy.toLumaSource(): PlanarYUVLuminanceSource? {
        val plane = planes.firstOrNull() ?: return null // plane[0] == Y (luma)
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val rowStride = plane.rowStride // may exceed width (row padding)
        if (rowStride <= 0) return null // malformed plane — avoid div-by-zero below
        // Clamp dataHeight/cropHeight to what the backing array can actually supply. On some devices
        // buffer.remaining() < rowStride * height (cropped/partial planes); passing the full image
        // height would make ZXing read past `data` (AIOOBE → swallowed by runCatching → silently never
        // decodes on that device). safeHeight is the largest fully-populated row count.
        val safeHeight = minOf(height, data.size / rowStride)
        if (safeHeight <= 0) return null
        // Crop width can never exceed dataWidth (= rowStride); clamp so left+width <= dataWidth holds.
        val cropWidth = minOf(width, rowStride)
        return PlanarYUVLuminanceSource(
            data,
            rowStride, // dataWidth = rowStride, NOT width
            safeHeight, // dataHeight clamped to populated rows
            0,
            0,
            cropWidth,
            safeHeight,
            false,
        )
    }
}
