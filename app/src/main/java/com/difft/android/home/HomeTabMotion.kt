



package com.difft.android.home

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


internal class PillMotion(
    initial: Int,
    private val scope: CoroutineScope,
    private val selected: State<Int>,
    private val onSelect: State<(Int) -> Unit>,
    private val nowMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private val valueSpec = spring<Float>(1f, 1000f, 0.001f)
    private val velocitySpec = spring<Float>(0.5f, 300f, 0.01f)
    private val pressSpec = spring<Float>(1f, 1000f, 0.001f)
    private val axisSpec = spring<Float>(0.6f, 250f, 0.001f)
    private val crossSpec = spring<Float>(0.7f, 250f, 0.001f)
    private val position = Animatable(initial.toFloat(), 0.001f)
    private val speed = Animatable(0f, 5f)
    private val press = Animatable(0f, 0.001f)
    private val axis = Animatable(1f, 0.001f)
    private val cross = Animatable(1f, 0.001f)
    private val tracker = VelocityTracker()
    private val panelReturn = Animatable(0f)
    private var panelDrag by mutableFloatStateOf(0f)
    private var positionJob: Job? = null
    private var speedJob: Job? = null
    private var activeSpeedJob: Job? = null
    private var speedRevision = 0L
    private var pressJob: Job? = null
    private var axisJob: Job? = null
    private var crossJob: Job? = null
    private var releaseWait: Job? = null
    private var returningPanel: Job? = null
    var dragging by mutableStateOf(false)
        private set
    var pressed by mutableStateOf(false)
        private set
    var slotExtent = 0f
    var stripExtent = 0f
    val index: Float get() = position.value
    val targetIndex: Float get() = position.targetValue
    val velocity: Float get() = speed.value
    val pressState: State<Float> get() = press.asState()
    val scaleAxis: Float get() = axis.value
    val scaleCross: Float get() = cross.value
    val panelDisplacement: Float get() = if (dragging) panelDrag else panelReturn.value

    suspend fun animateSelection(target: Int) {
        val clamped = HomeTabPillMath.clampIndex(target.toFloat(), HomeTab.COUNT)
        
        if (clamped == targetIndex) return
        beginSelection(clamped).join()
    }

    fun pointerDown() {
        pressed = true
        releaseWait?.cancel()
        tracker.resetTracking()
        animatePressure(true)
    }

    fun pointerUp() {
        pressed = false
        if (!dragging) releaseWhenNear()
    }

    fun startDrag() {
        returningPanel?.cancel()
        panelDrag = panelReturn.value
        dragging = true
        releaseWait?.cancel()
        tracker.resetTracking()
        animatePressure(true)
    }

    fun drag(delta: Float, reverse: Boolean) {
        if (delta.isFinite()) panelDrag += delta
        val target = HomeTabPillMath.dragIndex(targetIndex, delta, slotExtent, HomeTab.COUNT, reverse)
        animateValue(target, trackVelocity = true)
    }

    fun release() {
        val target = HomeTabPillMath.nearestIndex(targetIndex, HomeTab.COUNT)
        pressed = false
        dragging = false
        returnPanel()
        onSelect.value(target)
        beginSelection(target.toFloat())
    }

    fun cancelDrag() {
        pressed = false
        dragging = false
        returnPanel()
        animateValue(HomeTabPillMath.clampIndex(selected.value.toFloat(), HomeTab.COUNT), trackVelocity = false)
        animateVelocity(0f)
        releaseWhenNear()
    }

    private fun beginSelection(target: Float): Job {
        releaseWait?.cancel()
        tracker.resetTracking()
        animatePressure(true)
        val animation = animateValue(target, trackVelocity = false)
        
        animateVelocity(0f)
        releaseWhenNear()
        return animation
    }

    private fun animateValue(target: Float, trackVelocity: Boolean): Job {
        val animation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            position.animateTo(target, valueSpec) {
                if (trackVelocity) {
                    tracker.addPosition(nowMillis(), Offset(value, 0f))
                    animateVelocity(tracker.calculateVelocity().x / (HomeTab.COUNT - 1))
                }
            }
        }
        positionJob = animation
        return animation
    }

    private fun animateVelocity(target: Float) {
        val revision = ++speedRevision
        
        speedJob = scope.launch {
            if (revision != speedRevision) return@launch
            val animation = coroutineContext[Job]
            activeSpeedJob = animation
            try {
                speed.animateTo(target, velocitySpec)
            } finally {
                if (activeSpeedJob === animation) activeSpeedJob = null
            }
        }
    }

    private fun animatePressure(down: Boolean) {
        val progress = if (down) 1f else 0f
        val scale = if (down) 78f / 56f else 1f
        pressJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { press.animateTo(progress, pressSpec) }
        axisJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { axis.animateTo(scale, axisSpec) }
        crossJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { cross.animateTo(scale, crossSpec) }
    }

    private fun releaseWhenNear() {
        releaseWait?.cancel()
        releaseWait = scope.launch {
            withFrameNanos { }
            snapshotFlow {
                !position.isRunning || abs(position.value - position.targetValue) < (HomeTab.COUNT - 1) * 0.025f
            }.first { it }
            if (!pressed && !dragging) animatePressure(false)
        }
    }

    private fun returnPanel() {
        val displacement = panelDrag
        returningPanel?.cancel()
        if (displacement == 0f && panelReturn.value == 0f) return
        returningPanel = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            panelReturn.snapTo(displacement)
            panelReturn.animateTo(0f, spring(1f, 300f, 0.5f))
        }
    }

    fun dispose() {
        pressed = false
        dragging = false
        releaseWait?.cancel()
        positionJob?.cancel()
        speedRevision++
        speedJob?.cancel()
        activeSpeedJob?.cancel()
        pressJob?.cancel()
        axisJob?.cancel()
        crossJob?.cancel()
        returningPanel?.cancel()
    }
}
