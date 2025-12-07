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
import kotlin.coroutines.cancellation.CancellationException


object FlashLightBlinker {

    // 当前闪烁状态
    private var isBlinking = false

    // 用于区分不同启动请求的唯一 token
    private var currentToken: Long = 0L

    // 协程同步锁，确保状态一致性
    private val mutex = Mutex()

    // 全局协程作用域
    private val blinkerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 当前闪灯任务
    private var blinkJob: Job? = null

    // 当前使用的摄像头 ID
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
            // Step 1 提取旧 job 引用（不在锁外 cancel）
            val oldJob: Job? = mutex.withLock {
                val previousJob = blinkJob
                blinkJob = null
                previousJob
            }

            // Step 2 在锁外取消旧任务，避免死锁
            oldJob?.cancelAndJoin()

            // Step 3 旧任务停止后再更新 token，防止旧任务提前退出
            mutex.withLock {
                currentToken = token
                stopInternal(context)
                isBlinking = true
            }

            // Step 4 获取可用摄像头（支持闪光灯）
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run {
                L.e { "[FlashLightBlinker] No camera with flash found." }
                return@launch
            }

            cameraId = id

            // Step 5 启动新的闪烁任务
            blinkJob = launch {
                val endTime = durationMs?.let { System.currentTimeMillis() + it }

                try {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        if (endTime != null && now >= endTime) break

                        // 开灯（加锁防止 stop 竞争）
                        mutex.withLock {
                            if (!isBlinking || token != currentToken) return@launch
                            cameraManager.setTorchMode(id, true)
                        }

                        delay(intervalMs.coerceAtLeast(50))

                        // 关灯（加锁防止 stop 竞争）
                        mutex.withLock {
                            if (!isBlinking || token != currentToken) return@launch
                            cameraManager.setTorchMode(id, false)
                        }

                        delay(intervalMs.coerceAtLeast(50))
                    }
                } catch (e: CancellationException) {
                    // 协程被主动取消（正常情况）
                    L.i { "[FlashLightBlinker] Blink canceled" }
                } catch (e: Exception) {
                    L.e { "[FlashLightBlinker] Blink failed: ${e.message}" }
                } finally {
                    // 确保闪光灯关闭并清理状态
                    try {
                        cameraManager.setTorchMode(id, false)
                    } catch (_: Exception) {
                    }
                    mutex.withLock {
                        isBlinking = false
                        cameraId = null
                    }
                    L.i { "[FlashLightBlinker] Blinking stopped" }
                }
            }
        }
    }

    /**
     * 手动停止闪灯（立即生效）
     */
    fun stopBlinking(context: Context) {
        blinkerScope.launch {
            // cancel() 可立即中断 delay
            val jobToCancel: Job? = mutex.withLock {
                val job = blinkJob
                blinkJob = null
                job
            }

            jobToCancel?.cancel()
            stopInternal(context)
        }
    }

    /**
     * 内部关闭闪光灯并清理状态
     */
    private fun stopInternal(context: Context) {
        isBlinking = false
        cameraId?.let { id ->
            try {
                val cameraManager =
                    context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager.setTorchMode(id, false)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 检查摄像头权限是否已授权
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 当前闪灯状态
     */
    fun isBlinking() = isBlinking
}