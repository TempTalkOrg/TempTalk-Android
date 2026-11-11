package com.difft.android.call.ui.video

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import livekit.org.webrtc.EglRenderer
import java.util.concurrent.atomic.AtomicBoolean










@Stable
class FirstFrameGate {
    private var attached: Any? by mutableStateOf(null)
    private var rendered: Any? by mutableStateOf(null)

    



    var generation: Int by mutableIntStateOf(0)
        private set

    



    val accepting: Boolean
        get() = acceptingState
    private var acceptingState by mutableStateOf(false)

    




    val hasFrame: Boolean
        get() = accepting && attached != null && rendered === attached

    fun attach(renderer: Any) {
        attached = renderer
    }

    fun detach(renderer: Any) {
        if (attached === renderer) attached = null
        if (rendered === renderer) rendered = null
    }

    



    fun setAccepting(value: Boolean) {
        if (acceptingState == value) return
        acceptingState = value
        reset()
    }

    
    fun onFrameRendered(renderer: Any, drawnGeneration: Int = generation) {
        if (!accepting || drawnGeneration != generation) return
        val current = attached
        if (current == null || current === renderer) rendered = renderer
    }

    
    fun reset() {
        rendered = null
        generation++
    }
}








internal class FirstFrameReporter(
    private val renderer: Any,
    private val gate: () -> FirstFrameGate?,
    private val addListener: (EglRenderer.FrameListener) -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
) {
    private val pending = AtomicBoolean(false)

    fun arm() {
        if (!pending.compareAndSet(false, true)) return
        addListener(
            EglRenderer.FrameListener {
                
                pending.set(false)
                val drawnGeneration = gate()?.generation ?: return@FrameListener
                postToMain { gate()?.onFrameRendered(renderer, drawnGeneration) }
            }
        )
    }
}
