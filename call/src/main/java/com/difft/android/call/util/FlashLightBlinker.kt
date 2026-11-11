package com.difft.android.call.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException


object FlashLightBlinker {

    @Volatile
    private var isBlinking = false

    @Volatile
    private var currentToken: Long = 0L

    
    private val mutex = Mutex()

    private val blinkerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    
    private val blinkJobRef = AtomicReference<Job?>(null)

    @Volatile
    private var cameraId: String? = null

    








    fun startBlinking(
        context: Context,
        intervalMs: Long = 400,
        durationMs: Long? = null,
        admitted: () -> Boolean = { true },
    ) {
        val token = System.currentTimeMillis()

        blinkerScope.launch {
            
            var admittedFirst = false
            val oldJob: Job? = mutex.withLock {
                admittedFirst = admitted()
                if (admittedFirst) blinkJobRef.getAndSet(null) else null
            }
            if (!admittedFirst) {
                L.i { "[FlashLightBlinker] start skipped reason=not_admitted" }
                return@launch
            }

            oldJob?.cancelAndJoin()

            val started = mutex.withLock {
                if (!admitted()) return@withLock false
                val previousToken = currentToken
                val wasBlinking = isBlinking
                currentToken = token
                isBlinking = true
                
                
                admitted().also {
                    if (it) {
                        turnOffTorch(context)
                    } else {
                        currentToken = previousToken
                        isBlinking = wasBlinking
                    }
                }
            }
            if (!started) {
                L.i { "[FlashLightBlinker] start skipped reason=not_admitted" }
                return@launch
            }

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run {
                L.e { "[FlashLightBlinker] No camera with flash found." }
                return@launch
            }

            cameraId = id

            blinkJobRef.set(launch {
                val endTime = durationMs?.let { System.currentTimeMillis() + it }

                try {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        if (endTime != null && now >= endTime) break
                        if (!isBlinking || token != currentToken || !admitted()) break

                        cameraManager.setTorchMode(id, true)
                        delay(intervalMs.coerceAtLeast(50))

                        if (!isBlinking || token != currentToken) break

                        cameraManager.setTorchMode(id, false)
                        delay(intervalMs.coerceAtLeast(50))
                    }
                } catch (_: CancellationException) {
                    L.i { "[FlashLightBlinker] Blink canceled" }
                } catch (e: Exception) {
                    L.e { "[FlashLightBlinker] Blink failed: ${e.message}" }
                } finally {
                    try {
                        cameraManager.setTorchMode(id, false)
                    } catch (e: Exception) {
                        L.w { "[FlashLightBlinker] setTorchMode off failed in finally: ${e.stackTraceToString()}" }
                    }
                    if (token == currentToken) {
                        isBlinking = false
                        cameraId = null
                    }
                    L.i { "[FlashLightBlinker] Blinking stopped" }
                }
            })
        }
    }

    





    fun stopBlinking() {
        isBlinking = false
        blinkJobRef.getAndSet(null)?.cancel()
    }

    private fun turnOffTorch(context: Context) {
        cameraId?.let { id ->
            try {
                val cameraManager =
                    context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager.setTorchMode(id, false)
            } catch (e: Exception) {
                L.w { "[FlashLightBlinker] turnOffTorch failed: ${e.stackTraceToString()}" }
            }
        }
    }

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isBlinking() = isBlinking
}