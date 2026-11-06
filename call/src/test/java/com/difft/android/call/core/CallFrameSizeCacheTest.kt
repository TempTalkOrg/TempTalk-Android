package com.difft.android.call.core

import com.difft.android.call.layout.CallMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallFrameSizeCacheTest {

    private var now = 100L
    private val cache = CallFrameSizeCache { now }

    private fun members(vararg on: Pair<String, Boolean>) = on.map { (id, cam) -> CallMember(id, isLocal = id == "me", cameraOn = cam) }

    @Test
    fun `a size while the camera is on is sent once`() {
        cache.onMembers(members("me" to false, "a" to true))
        assertEquals(FrameSize("a", 720, 1280), cache.onFrameSize("a", 720, 1280, now))
        assertNull(cache.onFrameSize("a", 720, 1280, now))
        assertEquals(FrameSize("a", 1280, 720), cache.onFrameSize("a", 1280, 720, now))
    }

    @Test
    fun `an early frame is kept and sent when the camera turns on`() {
        cache.onMembers(members("me" to false))
        assertNull(cache.onFrameSize("me", 720, 1280, now))
        assertEquals(listOf(FrameSize("me", 720, 1280)), cache.onMembers(members("me" to true)))
    }

    @Test
    fun `turning the camera off forgets the size`() {
        cache.onMembers(members("a" to true))
        cache.onFrameSize("a", 720, 1280, now)
        now = 200
        cache.onMembers(members("a" to false))
        now = 300
        // Back on with no fresh frame: nothing to send, so the card waits for one.
        assertEquals(emptyList<FrameSize>(), cache.onMembers(members("a" to true)))
    }

    @Test
    fun `a size seen before the camera turned off is dropped`() {
        cache.onMembers(members("a" to true))
        now = 200
        cache.onMembers(members("a" to false))
        // Seen at 150, delivered after the camera went off.
        assertNull(cache.onFrameSize("a", 720, 1280, 150))
        now = 300
        assertEquals(emptyList<FrameSize>(), cache.onMembers(members("a" to true)))
        assertEquals(FrameSize("a", 720, 1280), cache.onFrameSize("a", 720, 1280, 310))
    }

    @Test
    fun `leaving forgets the size and drops sizes seen before`() {
        cache.onMembers(members("a" to true))
        cache.onFrameSize("a", 720, 1280, now)
        now = 200
        cache.onMembers(members("me" to false))
        assertNull(cache.onFrameSize("a", 720, 1280, 150))
        now = 300
        assertEquals(emptyList<FrameSize>(), cache.onMembers(members("me" to false, "a" to true)))
    }

    @Test
    fun `invalid sizes are ignored`() {
        cache.onMembers(members("a" to true))
        assertNull(cache.onFrameSize("a", 0, 1280, now))
        assertNull(cache.onFrameSize("a", 720, -1, now))
    }

    @Test
    fun `the size lookup answers only for a camera that is on`() {
        cache.onMembers(members("me" to false))
        cache.onFrameSize("me", 720, 1280, now)
        assertNull("an early frame is not shown yet", cache.sizeOf("me"))
        cache.onMembers(members("me" to true))
        assertEquals(FrameSize("me", 720, 1280), cache.sizeOf("me"))
        cache.onMembers(members("me" to false))
        assertNull(cache.sizeOf("me"))
    }
}
