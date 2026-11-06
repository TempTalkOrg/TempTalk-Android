package com.difft.android.call.core

import com.difft.android.call.layout.CallMember

/** One camera frame size for [CallLayoutController.setVideoSize][com.difft.android.call.layout.CallLayoutController.setVideoSize]. */
internal data class FrameSize(val id: String, val width: Int, val height: Int)

/**
 * The frame size of each person's current camera session, for the call layout. Main thread only.
 *
 * A session ends when the camera turns off or the person leaves: its size is forgotten and any
 * size seen before that moment (still on its way to main) is dropped, so a restarted camera shows
 * no video before a fresh frame. A size that arrives while the camera is not on yet (an early
 * frame) is kept and sent once the camera turns on.
 */
internal class CallFrameSizeCache(private val clock: () -> Long) {
    private val sizes = HashMap<String, FrameSize>()
    private val endedAt = HashMap<String, Long>()
    private var cameraOn = emptyMap<String, Boolean>()

    /**
     * The people in the call now. Returns the kept sizes of cameras that just turned on, to send
     * after the list.
     */
    fun onMembers(members: List<CallMember>): List<FrameSize> {
        val now = clock()
        val next = members.associate { it.id to it.cameraOn }
        for ((id, on) in cameraOn) {
            if (on && next[id] != true) endSession(id, now)
        }
        for (id in cameraOn.keys) {
            if (id !in next) endSession(id, now)
        }
        sizes.keys.retainAll(next.keys)
        val turnedOn = members.filter { it.cameraOn && cameraOn[it.id] != true }.mapNotNull { sizes[it.id] }
        cameraOn = next
        return turnedOn
    }

    /**
     * A frame of [id]'s camera seen at [seenAt] (the [clock]'s time base). Returns the size to send
     * now, or null when it is stale, unchanged, or kept until the camera turns on.
     */
    fun onFrameSize(id: String, width: Int, height: Int, seenAt: Long): FrameSize? {
        if (width <= 0 || height <= 0) return null
        if (endedAt[id]?.let { seenAt < it } == true) return null
        val size = FrameSize(id, width, height)
        val previous = sizes.put(id, size)
        return size.takeIf { cameraOn[id] == true && previous != size }
    }

    /** The last frame size of [id]'s current camera session; null while the camera is off or before a frame. */
    fun sizeOf(id: String): FrameSize? = sizes[id]?.takeIf { cameraOn[id] == true }

    /** The camera of [id] is on as of the last member list. */
    fun isCameraOn(id: String): Boolean = cameraOn[id] == true

    private fun endSession(id: String, now: Long) {
        sizes.remove(id)
        endedAt[id] = now
    }
}
