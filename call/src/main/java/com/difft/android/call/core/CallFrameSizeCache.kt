package com.difft.android.call.core

import com.difft.android.call.layout.CallMember


internal data class FrameSize(val id: String, val width: Int, val height: Int)









internal class CallFrameSizeCache(private val clock: () -> Long) {
    private val sizes = HashMap<String, FrameSize>()
    private val endedAt = HashMap<String, Long>()
    private var cameraOn = emptyMap<String, Boolean>()

    



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

    



    fun onFrameSize(id: String, width: Int, height: Int, seenAt: Long): FrameSize? {
        if (width <= 0 || height <= 0) return null
        if (endedAt[id]?.let { seenAt < it } == true) return null
        val size = FrameSize(id, width, height)
        val previous = sizes.put(id, size)
        return size.takeIf { cameraOn[id] == true && previous != size }
    }

    
    fun sizeOf(id: String): FrameSize? = sizes[id]?.takeIf { cameraOn[id] == true }

    
    fun isCameraOn(id: String): Boolean = cameraOn[id] == true

    private fun endSession(id: String, now: Long) {
        sizes.remove(id)
        endedAt[id] = now
    }
}
