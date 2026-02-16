package com.difft.android.call.manager

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.jvm.Synchronized
import java.lang.ref.WeakReference

/**
 * 管理距离传感器，用于检测通话时手机是否靠近脸部
 * 当靠近时禁用触摸并调暗屏幕，防止误触
 */
class ProximitySensorManager(
    context: android.content.Context,
    window: Window,
    private val isScreenSharingProvider: () -> Boolean,
    externalScope: CoroutineScope? = null,
    lifecycle: Lifecycle? = null
) {
    constructor(
        activity: android.app.Activity,
        isScreenSharingProvider: () -> Boolean,
        externalScope: CoroutineScope? = (activity as? LifecycleOwner)?.lifecycleScope,
        lifecycle: Lifecycle? = (activity as? LifecycleOwner)?.lifecycle
    ) : this(
        context = activity,
        window = activity.window,
        isScreenSharingProvider = isScreenSharingProvider,
        externalScope = externalScope,
        lifecycle = lifecycle
    )
    // 使用 WeakReference 防止内存泄漏
    private val contextRef: WeakReference<android.content.Context> = WeakReference(context)
    private val windowRef: WeakReference<Window> = WeakReference(window)

    @Volatile
    private var sensorManager: SensorManager? = null

    @Volatile
    private var proximitySensor: Sensor? = null

    @Volatile
    private var isRegistered = false

    @Volatile
    private var sensorJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appliedIsNear: Boolean? = null

    @Volatile
    private var lastIsNear: Boolean? = null

    @Volatile
    private var wasScreenSharingActive = false


    private val ownsScope = externalScope == null
    private val windowScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val desiredIsNearFlow = MutableStateFlow<Boolean?>(null)

    private val windowStateJob = windowScope.launch {
        val flow = desiredIsNearFlow.filterNotNull()
        if (lifecycle != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                collectDesiredState(flow)
            }
        } else {
            collectDesiredState(flow)
        }
    }

    /**
     * 初始化传感器管理器
     * 检查设备是否支持距离传感器
     */
    fun initialize() {
        val context = contextRef.get() ?: run {
            L.w { "[Call] ProximitySensorManager: Context reference is null, cannot initialize" }
            return
        }

        // 初始化 SensorManager
        sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? SensorManager

        val manager = sensorManager ?: run {
            L.e { "[Call] ProximitySensorManager: Failed to get SensorManager" }
            return
        }

        proximitySensor = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) {
            L.i { "[Call] ProximitySensorManager: Proximity sensor not available" }
        } else {
            L.i { "[Call] ProximitySensorManager: Proximity sensor initialized" }
        }
    }

    /**
     * 注册传感器监听器
     * 应在 Activity 的 onResume() 中调用
     */
    @Synchronized
    fun register() {
        // 检查是否已注册
        if (isRegistered) {
            L.w { "[Call] ProximitySensorManager: Sensor already registered" }
            return
        }
        // 先恢复到默认亮度，避免残留黑屏状态
        resetWindowState()

        val sensor = proximitySensor
        val manager = sensorManager

        if (sensor == null || manager == null) {
            L.w { "[Call] ProximitySensorManager: Cannot register, sensor or manager not available" }
            return
        }

        sensorJob?.cancel()
        sensorJob = windowScope.launch {
            createProximityFlow(manager, sensor)
                .buffer(Channel.CONFLATED)
                .collect { isNear ->
                updateDesiredState(isNear)
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                isRegistered = false
                if (cause == null || cause is CancellationException) {
                    L.i { "[Call] ProximitySensorManager: Sensor job completed (normal)" }
                } else {
                    L.e { "[Call] ProximitySensorManager: Sensor job failed: ${cause.message}" }
                }
            }
        }
        isRegistered = true
        L.i { "[Call] ProximitySensorManager: Sensor register requested" }
    }

    /**
     * 注销传感器监听器
     * 应在 Activity 的 onPause() 中调用
     */
    @Synchronized
    fun unregister() {
        // 检查是否已注册
        if (!isRegistered) {
            resetWindowState()
            return
        }
        sensorJob?.cancel()
        sensorJob = null
        isRegistered = false
        L.i { "[Call] ProximitySensorManager: Sensor unregistered" }
        resetWindowState()
    }

    /**
     * 释放所有资源
     * 应在 Activity 的 onDestroy() 中调用
     */
    @Synchronized
    fun release() {
        // 先注销监听器
        unregister()
        resetWindowState()

        // 清理所有引用
        proximitySensor = null
        sensorManager = null
        sensorJob?.cancel()
        sensorJob = null
        windowStateJob.cancel()
        if (ownsScope) {
            windowScope.cancel()
        }
        // 清理 WeakReference
        windowRef.clear()
        contextRef.clear()
        L.i { "[Call] ProximitySensorManager: Released" }
    }

    /**
     * 将传感器事件转为协程流
     */
    private fun createProximityFlow(
        manager: SensorManager,
        sensor: Sensor
    ) = callbackFlow<Boolean> {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val value = event?.values?.getOrNull(0) ?: return
                val isNear = value < sensor.maximumRange
                trySend(isNear).isSuccess
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // 不需要处理精度变化
            }
        }

        val registerAction = Runnable {
            try {
                val success = manager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                if (!success) {
                    close(IllegalStateException("registerListener returned false"))
                }
            } catch (e: Exception) {
                L.e { "[Call] ProximitySensorManager: Failed to register sensor: ${e.message}" }
                close(e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            registerAction.run()
        } else {
            mainHandler.post(registerAction)
        }

        awaitClose {
            val unregisterAction = Runnable {
                try {
                    manager.unregisterListener(listener)
                } catch (e: Exception) {
                    L.e { "[Call] ProximitySensorManager: Failed to unregister sensor: ${e.message}" }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                unregisterAction.run()
            } else {
                mainHandler.post(unregisterAction)
            }
        }
    }

    /**
     * 当设备靠近用户脸部时，禁用触摸并保持屏幕常亮
     * 1. 添加 FLAG_NOT_TOUCHABLE 禁用所有触摸事件
     * 2. 保持屏幕常亮 (FLAG_KEEP_SCREEN_ON)
     * 3. 将屏幕亮度设置为最小值 (0.0f)
     */
    private fun disableWindowForTouchAndKeepScreenOn() {
        val window = windowRef.get() ?: run {
            L.w { "[Call] ProximitySensorManager: Window reference is null, cannot disable touch" }
            return
        }

        try {
            // 禁用触摸
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            // 保持屏幕常亮
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 将屏幕亮度调整为最小值
            window.attributes = window.attributes.apply {
                screenBrightness = 0.0f
            }
            L.d { "[Call] ProximitySensorManager: Window disabled for touch, brightness dimmed" }
        } catch (e: Exception) {
            L.e { "[Call] ProximitySensorManager: Failed to disable window: ${e.message}" }
        }
    }

    /**
     * 当设备远离用户脸部时，恢复正常的屏幕交互和亮度设置
     * 1. 清除窗口的不可触摸标志 (FLAG_NOT_TOUCHABLE) 以恢复触摸功能
     * 2. 保持屏幕常亮 (FLAG_KEEP_SCREEN_ON)
     * 3. 将屏幕亮度恢复为系统默认值 (BRIGHTNESS_OVERRIDE_NONE)
     */
    private fun enableWindowForTouchAndKeepScreenOn() {
        val window = windowRef.get() ?: run {
            L.w { "[Call] ProximitySensorManager: Window reference is null, cannot enable touch" }
            return
        }

        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            L.d { "[Call] ProximitySensorManager: Window enabled for touch, brightness restored" }
        } catch (e: Exception) {
            L.e { "[Call] ProximitySensorManager: Failed to enable window: ${e.message}" }
        }
    }

    private fun resetWindowState() {
        appliedIsNear = null
        lastIsNear = null
        wasScreenSharingActive = false
        desiredIsNearFlow.value = null
        // 立即恢复，避免 RESUMED 之外无法生效
        mainHandler.post {
            enableWindowForTouchAndKeepScreenOn()
        }
    }

    private suspend fun collectDesiredState(flow: Flow<Boolean>) {
        flow.collect { isNear ->
            applyWindowState(isNear)
        }
    }

    private fun applyWindowState(isNear: Boolean) {
        if (appliedIsNear == isNear) {
            return
        }
        if (isScreenSharingProvider()) {
            if (appliedIsNear == false) {
                return
            }
            enableWindowForTouchAndKeepScreenOn()
            appliedIsNear = false
            return
        }
        if (isNear) {
            disableWindowForTouchAndKeepScreenOn()
        } else {
            enableWindowForTouchAndKeepScreenOn()
        }
        appliedIsNear = isNear
    }

    private fun updateDesiredState(isNear: Boolean) {
        lastIsNear = isNear
        val screenSharingActive = isScreenSharingProvider()
        if (screenSharingActive) {
            if (!wasScreenSharingActive || desiredIsNearFlow.value != false) {
                desiredIsNearFlow.value = false
            }
            wasScreenSharingActive = true
            return
        }
        if (wasScreenSharingActive) {
            wasScreenSharingActive = false
            val restored = lastIsNear
            if (restored != null && desiredIsNearFlow.value != restored) {
                desiredIsNearFlow.value = restored
            }
            return
        }
        if (desiredIsNearFlow.value == isNear) {
            return
        }
        desiredIsNearFlow.value = isNear
    }
}