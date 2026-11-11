package com.difft.android.chat.attachment.videoprobe

import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.VideoProbeState
import difft.android.messageserialization.model.isPlayableVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test






class VideoProbeRefreshTest {

    private fun attachment(localId: String) = Attachment(
        id = "",
        authorityId = 0L,
        contentType = "video/quicktime",
        key = null,
        size = 0,
        thumbnail = null,
        digest = null,
        fileName = "clip.mov",
        flags = 0,
        width = 0,
        height = 0,
        path = null,
        status = 3,
        localId = localId,
    )

    private fun forward(id: Long, attachments: List<Attachment>? = null, nested: List<Forward>? = null) =
        Forward(id, 0, false, "author", null, attachments, nested, null)

    @Test
    fun `room item renders its own or its single-forward attachment only`() {
        val own = TextChatMessage().apply { attachment = attachment("own") }
        val single = TextChatMessage().apply {
            forwardContext = ForwardContext(listOf(forward(1, listOf(attachment("fwd")))), false)
        }
        val combined = TextChatMessage().apply {
            forwardContext = ForwardContext(listOf(forward(1, listOf(attachment("x"))), forward(2)), false)
        }

        assertTrue(own.rendersAttachment("own"))
        assertTrue(single.rendersAttachment("fwd"))
        assertFalse(combined.rendersAttachment("x"))
        assertFalse(own.rendersAttachment("other"))
    }

    @Test
    fun `T16 T26 probed attachment is replaced by a fresh instance at any depth`() {
        val target = attachment("target")
        val sibling = attachment("sibling")
        val untouched = forward(3, listOf(attachment("other")))
        val context = ForwardContext(
            listOf(forward(1, listOf(sibling)), forward(2, nested = listOf(forward(21, listOf(target)))), untouched),
            isFromGroup = true,
        )
        val row = VideoProbeRow(databaseId = 5, width = 640, height = 360, videoProbeState = VideoProbeState.NONE)

        assertTrue(context.containsAttachment("target"))
        val updated = context.withProbedAttachment("target", row)

        val probed = updated.forwards!![1].forwards!![0].attachments!![0]
        assertTrue(probed.isPlayableVideo())
        assertEquals("target", probed.localId)
        assertNotSame(target, probed)
        
        assertEquals(0, target.width)
        assertFalse(context.forwards!![1].forwards!![0].attachments!![0].isPlayableVideo())
        
        assertSame(untouched, updated.forwards!![2])
        assertSame(sibling, updated.forwards!![0].attachments!![0])
        assertTrue(updated.isFromGroup)
    }

    @Test
    fun `a failed probe is carried as file rendering`() {
        val context = ForwardContext(listOf(forward(1, listOf(attachment("a")))), false)
        val failed = VideoProbeRow(databaseId = 5, width = 0, height = 0, videoProbeState = VideoProbeState.FAILED)

        val probed = context.withProbedAttachment("a", failed).forwards!![0].attachments!![0]

        assertEquals(VideoProbeState.FAILED, probed.videoProbeState)
        assertFalse(probed.isPlayableVideo())
        assertFalse(context.containsAttachment("b"))
    }
}
