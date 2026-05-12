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

    // 协程 Mutex 仅用于 startBlinking 内部的 start-start 协调（含 cancelAndJoin suspend 调用）
    private val mutex = Mutex()

    private val blinkerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // AtomicReference 保证 stopBlinking 的 read-then-clear 与 startBlinking 的安装是原子的，
    // 避免新启动的 blinkJob 被并发 stopBlinking 静默孤立
    private val blinkJobRef = AtomicReference<Job?>(null)

    @Volatile
    private var cameraId: String? = null

    /**
     * 开始闪灯
     * @param context 上下文
     * @param intervalMs 闪烁间隔时间（每次亮灭的间隔）
     * @param durationMs 持续时长（到期自动停止），null 表示无限时长
     */
    fun startBlinking(
        context: Context,
        intervalMs: Long = 400,
        durationMs: Long? = null
    ) {
        val token = System.currentTimeMillis()

        blinkerScope.launch {
            val oldJob: Job? = mutex.withLock {
                blinkJobRef.getAndSet(null)
            }

            oldJob?.cancelAndJoin()

            mutex.withLock {
                currentToken = token
                turnOffTorch(context)
                isBlinking = true
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
                        if (!isBlinking || token != currentToken) break

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

    /**
     * 停止闪灯（同步非阻塞，可安全在主线程调用）。
     *
     * 不 launch 协程，避免 CoroutineScheduler.dispatch 在主线程触发
     * Object.notifyAll 导致低端设备 ANR。
     *
     * 不在主线程做 setTorchMode（binder IPC）：被 cancel 的 blinkJob 的 finally 块
     * 会在 IO 线程上调用 setTorchMode(id, false) 兜底关灯。
     *
     * 用 AtomicReference.getAndSet 原子交换，避免与并发的 startBlinking 安装新 Job
     * 之间产生 read-then-clear 竞态导致新 Job 被孤立。
     */
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