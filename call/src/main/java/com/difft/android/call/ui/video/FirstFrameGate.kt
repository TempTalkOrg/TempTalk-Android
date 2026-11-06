package com.difft.android.call.ui.video

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import livekit.org.webrtc.EglRenderer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the camera renderer mounted for a tile has drawn a frame, so the tile can keep its
 * placeholder over a fresh renderer that is still black. A renderer is any object identifying one
 * mounted renderer instance. A frame reported before its renderer is attached still counts once it
 * is; a report from a renderer other than the attached one, drawn before the latest [reset], or
 * received while not [accepting] is ignored.
 *
 * Mutated on the main thread only; [generation] may be read from a render thread.
 */
@Stable
class FirstFrameGate {
    private var attached: Any? by mutableStateOf(null)
    private var rendered: Any? by mutableStateOf(null)

    /**
     * Advances on every [reset]. A renderer the tile keeps across a reset has already spent its
     * one-shot frame listener, so it must register a fresh one whenever this changes.
     */
    var generation: Int by mutableIntStateOf(0)
        private set

    /**
     * Whether frames count, i.e. the tile's camera is on. Off by default so nothing a renderer draws
     * before the tile reports its camera state can reveal the video.
     */
    val accepting: Boolean
        get() = acceptingState
    private var acceptingState by mutableStateOf(false)

    /**
     * True once the attached renderer has drawn a frame since it was attached or since [reset], while
     * [accepting]. Stays false in the composition that first sees the camera on, before
     * [setAccepting] runs, so a frame drawn while off cannot lift the placeholder for that frame.
     */
    val hasFrame: Boolean
        get() = accepting && attached != null && rendered === attached

    fun attach(renderer: Any) {
        attached = renderer
    }

    fun detach(renderer: Any) {
        if (attached === renderer) attached = null
        if (rendered === renderer) rendered = null
    }

    /**
     * Follows the tile's camera state. Each change resets, so a frame drawn on the other side of the
     * edge never counts and a kept renderer re-arms for the first frame after it.
     */
    fun setAccepting(value: Boolean) {
        if (acceptingState == value) return
        acceptingState = value
        reset()
    }

    /** [drawnGeneration] is the [generation] read when the frame was drawn. */
    fun onFrameRendered(renderer: Any, drawnGeneration: Int = generation) {
        if (!accepting || drawnGeneration != generation) return
        val current = attached
        if (current == null || current === renderer) rendered = renderer
    }

    /** Forgets the drawn frame, e.g. when the camera turns off and the renderer keeps a stale image. */
    fun reset() {
        rendered = null
        generation++
    }
}

/**
 * Reports one renderer's drawn frames to a [FirstFrameGate] through one-shot frame listeners, which
 * the renderer drops after a single call. [arm] registers a listener only while none is pending, so
 * calling it on every gate [FirstFrameGate.generation] change re-arms a kept renderer once per reset
 * without stacking duplicates. A frame reports the generation current when it was drawn, so a frame
 * drawn just before a reset and delivered after it does not count.
 */
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
                // Runs on the render thread once the frame is on the surface.
                pending.set(false)
                val drawnGeneration = gate()?.generation ?: return@FrameListener
                postToMain { gate()?.onFrameRendered(renderer, drawnGeneration) }
            }
        )
    }
}
