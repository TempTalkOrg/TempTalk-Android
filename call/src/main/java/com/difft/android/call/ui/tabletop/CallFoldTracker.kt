package com.difft.android.call.ui.tabletop

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt













internal class CallFoldTracker(private val activity: ComponentActivity) {
    private val _reports = MutableStateFlow<CallFoldReport?>(null)

    
    val reports: StateFlow<CallFoldReport?> = _reports.asStateFlow()

    private var lastLogged: CallFoldReport? = null
    private var logged = 0

    fun attach() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                
                try {
                    
                    
                    
                    var heldByHinge = _reports.value?.folds?.any { it.byHinge } == true
                    combine(WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity), hingeAngles(activity)) { info, hinge ->
                        val report = info.toReport().withHingeFallback(hinge, heldByHinge)
                        heldByHinge = report.folds.any { it.byHinge }
                        report to hinge
                    }.collect { (report, hinge) -> publish(report, hinge) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message
                    L.w { "[Call] fold collection failed error=$message" }
                    publish(CallFoldReport(emptyList()), null)
                }
            }
        }
    }

    
    private fun publish(report: CallFoldReport, hingeDeg: Float?) {
        _reports.value = report
        if (report == lastLogged || logged >= MAX_LOG_LINES) return
        lastLogged = report
        logged++
        val window = runCatching { WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds }.getOrNull()
        val line = formatFoldLog(report, window?.width(), window?.height(), hingeDeg)
        L.i { line }
    }

    private companion object {
        const val MAX_LOG_LINES = 40
    }
}


private const val HINGE_FIRST_EVENT_WAIT_MS = 200L








private fun hingeAngles(context: Context): Flow<Float?> = callbackFlow {
    val manager = context.getSystemService(SensorManager::class.java)
    val sensor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) manager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) else null
    if (manager == null || sensor == null) {
        send(null)
        awaitClose()
        return@callbackFlow
    }
    val firstEventWait = launch {
        delay(HINGE_FIRST_EVENT_WAIT_MS)
        send(null)
    }
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            firstEventWait.cancel()
            event.values.firstOrNull()?.let { trySend(it) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    val registered = runCatching { manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
    if (!registered) {
        firstEventWait.cancel()
        send(null)
    }
    awaitClose { manager.unregisterListener(listener) }
}

internal fun WindowLayoutInfo.toReport(): CallFoldReport =
    CallFoldReport(displayFeatures.filterIsInstance<FoldingFeature>().map { it.toCallFold() })

internal fun FoldingFeature.toCallFold(): CallFold {
    val b = bounds
    return CallFold(
        state = if (state == FoldingFeature.State.HALF_OPENED) CallFoldState.HALF_OPENED else CallFoldState.FLAT,
        horizontal = orientation == FoldingFeature.Orientation.HORIZONTAL,
        occluding = occlusionType == FoldingFeature.OcclusionType.FULL,
        bounds = PxRect(b.left, b.top, b.right, b.bottom),
    )
}





internal fun formatFoldLog(report: CallFoldReport, windowWidthPx: Int?, windowHeightPx: Int?, hingeDeg: Float? = null): String = buildString {
    append("[Call] fold n=").append(report.folds.size)
    report.folds.forEachIndexed { i, f ->
        val b = f.bounds
        append(" f").append(i).append('=').append(f.state.name)
            .append('/').append(if (f.horizontal) 'H' else 'V')
            .append("/occl=").append(f.occluding)
            .append('/').append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom)
        if (f.byHinge) append("/byHinge")
    }
    append(" winPx=").append(windowWidthPx).append('x').append(windowHeightPx)
    if (hingeDeg != null) append(" hinge=").append(hingeDeg.roundToInt())
}
