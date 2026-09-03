package com.difft.android.websocket.api.messages

import com.difft.android.websocket.api.util.transformGroupIdFromLocalToServer
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import com.google.protobuf.ByteString
import difft.android.messageserialization.For
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MsgExtra

/**
 * The #1 correctness trap — gid normalization (design-report §3, §7.3).
 *
 * A legit group message must NOT be false-dropped because the content side and the
 * envelope side reduced the same group id to different strings. These tests pin:
 *   NM1/NM2 — same/different 32-byte gids compare (PROCESS / REJECT).
 *   NM3     — the "money" test: the real content lazy (:160) and the real envelope
 *             path (extractEnvelopeConversation -> parseToFor) reduce identical bytes
 *             to identical For.Group strings for the C1 / plain-ASCII population.
 *   NM-PLAIN-RT — f ∘ g is identity for plain-ASCII ids (manufactured C2/C3 never
 *                 false-drop the live population).
 *   NM-WEEK — f ∘ g is identity for legacy "WEEK"/16-byte ids after the C5 cross-platform
 *             alignment re-enabled the 16-byte decode branch, closing the former §3.1 residual.
 */
class ConversationGidNormalizationTest {

    private val gidA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 32 bytes ASCII
    private val gidB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" // 32 bytes ASCII

    // NM1 — identical 32 bytes both sides -> For.Group equal -> PROCESS (no false-drop)
    @Test
    fun `NM1 same group bytes both sides processes`() {
        val bytes = gidA.toByteArray(Charsets.UTF_8)
        val content = For.Group(bytes.transformGroupIdFromServerToLocal())
        val envelope = For.Group(bytes.transformGroupIdFromServerToLocal())
        assertEquals(content, envelope)
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(content, envelope, isSyncOrSelf = false)
        )
    }

    // NM2 — genuinely different gids still reject
    @Test
    fun `NM2 different group bytes reject`() {
        val content = For.Group(gidA.toByteArray(Charsets.UTF_8).transformGroupIdFromServerToLocal())
        val envelope = For.Group(gidB.toByteArray(Charsets.UTF_8).transformGroupIdFromServerToLocal())
        assertNotEquals(content, envelope)
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(content, envelope, isSyncOrSelf = false)
        )
    }

    // NM3 — money test: real content lazy (:160) vs real envelope path from the SAME bytes.
    @Test
    fun `NM3 content path and envelope path reduce identical bytes identically`() {
        val bytes = gidA.toByteArray(Charsets.UTF_8) // 32-byte on-wire server bytes

        val content = Content.newBuilder()
            .setDataMessage(
                DataMessage.newBuilder()
                    .setGroup(
                        DataMessage.Group.newBuilder()
                            .setId(ByteString.copyFrom(bytes))
                            .setType(DataMessage.Group.Type.DELIVER)
                            .build()
                    )
                    .build()
            )
            .build()

        val envelope = Envelope.newBuilder()
            .setSource("+15551234")
            .setSourceDevice(1)
            .setTimestamp(1_700_000_000_000L)
            .setMsgExtra(
                MsgExtra.newBuilder()
                    .setConversationId(
                        ConversationId.newBuilder()
                            .setGroupId(ByteString.copyFrom(bytes))
                            .build()
                    )
                    .build()
            )
            .build()

        val data = SignalServiceDataClass(envelope, content, null)
        val contentFor = data.conversation                 // real :160 reduction
        val envelopeFor = data.envelopeConversation         // real parseToFor :329 reduction

        assertTrue(contentFor is For.Group)
        assertTrue(envelopeFor is For.Group)
        assertEquals(contentFor, envelopeFor)
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(contentFor, envelopeFor!!, isSyncOrSelf = false)
        )
    }

    // NM-PLAIN-RT — f ∘ g is identity for plain-ASCII ids (live manufactured-path population)
    @Test
    fun `NM-PLAIN-RT plain ASCII round trip is identity`() {
        val local = gidA // 32-char ASCII local id
        val server = local.transformGroupIdFromLocalToServer() // g
        val back = server.transformGroupIdFromServerToLocal()  // f
        assertEquals(local, back)
    }

    // NM-WEEK — f ∘ g is identity for legacy WEEK/16-byte ids after the C5 cross-platform
    // alignment re-enabled the 16-byte decode branch (matching iOS/Desktop). This closes the
    // former §3.1 WEEK false-drop residual: a WEEK id now round-trips through the manufactured
    // envelope paths (C2/C3) unchanged. See GroupIdTransformParityTest for the raw-transform vectors.
    @Test
    fun `NM-WEEK legacy WEEK round trip is identity`() {
        val local = "WEEK0123456789ABCDEF0123456789ABCDEF" // "WEEK" + 32 hex chars
        val server = local.transformGroupIdFromLocalToServer() // g -> 16 bytes (live WEEK branch)
        assertEquals("WEEK encode must produce 16 bytes", 16, server.size)
        val back = server.transformGroupIdFromServerToLocal()  // f -> "WEEK" + uppercase hex (live decode)
        assertEquals(local, back)
    }
}
