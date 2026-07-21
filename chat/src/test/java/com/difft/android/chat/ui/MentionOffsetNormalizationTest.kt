package com.difft.android.chat.ui

import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Send-side mention-offset tests: the sender is the authority for mention positions,
 * so offsets are (re)derived from the normalized outgoing body at SEND. These tests
 * invoke the real offset seam [ChatMessageInputFragment.mentionOffsets] (the Fragment
 * is built only to reach the pure seam, hence Robolectric) and assert each returned
 * start still indexes the `@`. The receive/display side never re-derives offsets, so
 * there is no receive-side remap here. Family members use `Char(codepoint)` to keep
 * this file free of raw control characters. Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MentionOffsetNormalizationTest {

    // Construct the real Fragment purely to reach the internal offset seam.
    // No onAttach/onCreate — the seam is pure and reads no injected field.
    private val fragment = ChatMessageInputFragment()

    private val aliceKey = "@Alice"

    // ---- T13: LS/PS before a mention -> start unchanged (1->1, no shift) ----
    @Test
    fun `T13 LS before mention leaves offset unchanged and start indexes at-sign`() {
        val ls = Char(0x2028).toString() // LINE SEPARATOR
        val rawBody = "$ls${aliceKey} hi"          // "<LS>@Alice hi", @ at index 1
        val normalizedBody = rawBody.normalizeNewlines()
        assertEquals("\n@Alice hi", normalizedBody) // LS -> `\n`, length preserved

        // Offsets against the RAW body (LS is 1->1, so identical to normalized).
        val rawOffsets = fragment.mentionOffsets(rawBody, setOf(aliceKey))
        // Offsets against the NORMALIZED body (what send actually uses).
        val normalizedOffsets = fragment.mentionOffsets(normalizedBody, setOf(aliceKey))

        assertEquals(1, normalizedOffsets.size)
        val m = normalizedOffsets.single()
        assertEquals(1, m.start)                       // no shift: @ still at index 1
        assertEquals(aliceKey.length, m.length)        // length == 6
        // 1->1 member: offsets are identical whether computed against raw or normalized.
        assertEquals(rawOffsets.single().start, m.start)
        assertEquals('@', normalizedBody[m.start])     // start still indexes `@`
    }

    // ---- T14: CRLF before a mention -> start shifts -1, re-indexes `@` ----
    @Test
    fun `T14 CRLF before mention shifts start by minus one and re-indexes at-sign`() {
        val rawBody = "\r\n${aliceKey} hi"        // "<CR><LF>@Alice hi", @ at index 2
        // On the RAW body the @ is at index 2.
        val rawOffsets = fragment.mentionOffsets(rawBody, setOf(aliceKey))
        assertEquals(2, rawOffsets.single().start)

        val normalizedBody = rawBody.normalizeNewlines()
        assertEquals("\n@Alice hi", normalizedBody)   // CRLF (2->1)

        val normalizedOffsets = fragment.mentionOffsets(normalizedBody, setOf(aliceKey))
        val m = normalizedOffsets.single()
        assertEquals(1, m.start)                       // shifted -1 (CRLF 2->1)
        assertEquals(aliceKey.length, m.length)
        assertEquals('@', normalizedBody[m.start])     // start indexes `@` in normalized body
    }

    // ---- T14b: truncated body -> no returned Mention runs past the end ----
    @Test
    fun `T14b offsets against truncated body never exceed its length and drop tail mentions`() {
        val bobKey = "@Bob"
        // Full body has @Alice in the retained prefix and @Bob only in the tail.
        val fullBody = "$aliceKey said hi to $bobKey over there "
        // Truncate so the prefix keeps @Alice but the @Bob occurrence is cut off.
        val cut = fullBody.indexOf(bobKey)
        val truncatedBody = fullBody.substring(0, cut).normalizeNewlines()
        assertTrue("truncated prefix must still contain @Alice", truncatedBody.contains(aliceKey))
        assertTrue("truncated prefix must NOT contain @Bob", !truncatedBody.contains(bobKey))

        val offsets = fragment.mentionOffsets(truncatedBody, setOf(aliceKey, bobKey))

        // No out-of-bounds: every emitted offset is a valid slice of the truncated body.
        offsets.forEach { m ->
            assertTrue("start >= 0", m.start >= 0)
            assertTrue(
                "start + length (${m.start + m.length}) <= truncatedBody.length (${truncatedBody.length})",
                m.start + m.length <= truncatedBody.length
            )
        }
        // The tail mention (@Bob) is absent; the prefix mention (@Alice) indexes its `@`.
        assertNull("tail mention @Bob must be dropped", offsets.find { it.length == bobKey.length })
        val alice = offsets.single { it.length == aliceKey.length }
        assertEquals('@', truncatedBody[alice.start])
    }

    // ---- T19: draft mentions self-correct at send (stale draft offset discarded) ----
    @Test
    fun `T19 send-side rebuilt offset indexes at-sign independent of stale draft offset`() {
        // Simulate a draft stored with a CRLF-before-mention body. The draft's
        // stored offset (against the raw CRLF body) would be 2, but at SEND the
        // offsets are re-derived from the normalized body via the seam.
        val draftRawBody = "\r\n${aliceKey} draft text"
        val staleDraftOffset = 2 // what the draft would have stored (raw @-index)

        // Send path: normalize, then rebuild offsets from the normalized body.
        val normalizedAtSend = draftRawBody.normalizeNewlines()
        val rebuilt = fragment.mentionOffsets(normalizedAtSend, setOf(aliceKey))

        val m = rebuilt.single()
        // Rebuilt offset is derived from the normalized body, NOT the stale draft value.
        assertEquals(1, m.start)
        assertTrue("rebuilt offset must differ from the stale draft offset", m.start != staleDraftOffset)
        assertEquals('@', normalizedAtSend[m.start]) // rebuilt offset correctly indexes `@`
    }
}
