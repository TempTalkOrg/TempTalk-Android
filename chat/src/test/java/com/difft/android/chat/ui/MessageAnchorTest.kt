package com.difft.android.chat.ui

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessageAnchorTest {

    @Test
    fun `an anchor survives the saved state bundle`() {
        val out = Bundle()
        MessageAnchor(timestamp = 1_700_000_000_123L, offsetPx = -42).saveTo(out)

        assertEquals(MessageAnchor(1_700_000_000_123L, -42), MessageAnchor.from(out))
    }

    @Test
    fun `no anchor is restored from a state that never saved one`() {
        assertNull(MessageAnchor.from(null))
        assertNull(MessageAnchor.from(Bundle()))
    }

    @Test
    fun `a loaded message is restored in place with its offset`() {
        val restore = MessageAnchor(timestamp = 30L, offsetPx = 12).resolve(listOf(10L, 20L, 30L, 40L))
        assertEquals(MessageAnchor.Restore.InList(position = 2, offsetPx = 12), restore)
    }

    @Test
    fun `a message outside the loaded window is reached through the existing jump`() {
        val restore = MessageAnchor(timestamp = 5L, offsetPx = 12).resolve(listOf(10L, 20L))
        assertEquals(MessageAnchor.Restore.Jump(5L), restore)
    }

    @Test
    fun `a list saved at the bottom is restored at the bottom whatever the replayed action`() {
        val out = Bundle()
        MessageAnchor.BOTTOM.saveTo(out)

        val restored = MessageAnchor.from(out)
        assertEquals(MessageAnchor.BOTTOM, restored)
        assertEquals(MessageAnchor.Restore.Bottom, restored!!.resolve(listOf(10L, 20L)))
    }

    @Test
    fun `a bottom anchor is dropped after process death so the first unread opens instead`() {
        val out = Bundle()
        MessageAnchor.BOTTOM.saveTo(out)

        assertNull(MessageAnchor.from(out, pid = android.os.Process.myPid() + 1))
    }

    @Test
    fun `a message anchor still restores after process death`() {
        val out = Bundle()
        MessageAnchor(timestamp = 30L, offsetPx = 12).saveTo(out)

        assertEquals(MessageAnchor(30L, 12), MessageAnchor.from(out, pid = android.os.Process.myPid() + 1))
    }
}
