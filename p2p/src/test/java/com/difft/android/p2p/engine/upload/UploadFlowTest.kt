package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.ByteArrayFileSource
import com.difft.android.p2p.engine.CapturingFileSink
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.Sha256
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.PrepareUploadRequest
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-S1, T-S2, T-S3, T-S5, T-S7 — prepare-upload + upload state machine (design §13). */
class UploadFlowTest {

    private val info = com.difft.android.p2p.engine.model.DeviceInfo(alias = "Sender", fingerprint = "fp")

    private fun fileMeta(id: String, bytes: ByteArray, withSha: Boolean = true) = FileMetadata(
        id = id,
        fileName = "$id.bin",
        size = bytes.size.toLong(),
        fileType = "application/octet-stream",
        sha256 = if (withSha) Sha256.hex(bytes) else null,
    )

    @Test
    fun `T-S1 prepare-upload with correct PIN returns sessionId and one token per file`() {
        val sm = SessionManager()
        val pin = PinPolicy()
        val code = pin.issue()
        val handler = PrepareUploadHandler(sm, pin, requirePin = true)

        val bytes = "hello".toByteArray()
        val req = PrepareUploadRequest(info, mapOf("f1" to fileMeta("f1", bytes)))
        val resp = handler.handle(req, "1.1.1.1", code)

        assertTrue(resp.sessionId.isNotBlank())
        assertEquals(setOf("f1"), resp.files.keys)
        assertTrue(resp.files.getValue("f1").isNotBlank())
        // The session-level credential and the capability list the cross-platform contract adds:
        // 128 bits base64url without padding is 22 characters; the receiver declares what it serves.
        assertEquals(22, resp.sessionToken?.length)
        assertTrue(resp.caps.orEmpty().contains(ReceiverCaps.STATUS_V1))
    }

    @Test
    fun `T-S2 prepare-upload with wrong PIN is Unauthorized`() {
        val sm = SessionManager()
        val pin = PinPolicy().also { it.issue() }
        val handler = PrepareUploadHandler(sm, pin, requirePin = true)
        val req = PrepareUploadRequest(info, mapOf("f1" to fileMeta("f1", "x".toByteArray())))
        assertThrows(ProtocolError.Unauthorized::class.java) {
            handler.handle(req, "1.1.1.1", "999999")
        }
    }

    @Test
    fun `prepare-upload without PIN requirement skips the PIN check`() {
        val sm = SessionManager()
        val pin = PinPolicy()
        val handler = PrepareUploadHandler(sm, pin, requirePin = false) // multicast oracle path
        val req = PrepareUploadRequest(info, mapOf("f1" to fileMeta("f1", "x".toByteArray())))
        val resp = handler.handle(req, "1.1.1.1", null)
        assertEquals(setOf("f1"), resp.files.keys)
    }

    @Test
    fun `T-S3 prepare-upload with empty files map is NoContent`() {
        val sm = SessionManager()
        val pin = PinPolicy().also { it.issue() }
        val handler = PrepareUploadHandler(sm, pin, requirePin = true)
        assertThrows(ProtocolError.NoContent::class.java) {
            handler.handle(PrepareUploadRequest(info, emptyMap()), "1.1.1.1", pin.current())
        }
    }

    @Test
    fun `prepare-upload with mismatched file id and map key is BadRequest`() {
        val sm = SessionManager()
        val pin = PinPolicy().also { it.issue() }
        val handler = PrepareUploadHandler(sm, pin, requirePin = true)
        val req = PrepareUploadRequest(info, mapOf("mapKey" to fileMeta("differentId", "x".toByteArray())))
        assertThrows(ProtocolError.BadRequest::class.java) {
            handler.handle(req, "1.1.1.1", pin.current())
        }
    }

    @Test
    fun `T-S5 upload streams bytes, verifies sha256, marks done`() {
        val sm = SessionManager()
        val sink = CapturingFileSink()
        val bytes = "the quick brown fox".toByteArray()
        val (sid, slots) = sm.createSession("1.1.1.1", mapOf("f1" to fileMeta("f1", bytes)))
        val token = slots.getValue("f1").token

        val result = UploadHandler(sm, sink).handle(
            sessionId = sid, fileId = "f1", token = token, sourceIp = "1.1.1.1",
            body = ByteArrayFileSource(bytes).openRead(),
        )

        assertEquals(bytes.size.toLong(), result.bytes)
        assertEquals(Sha256.hex(bytes), result.sha256)
        assertArrayEquals(bytes, sink.bytesOf("f1"))
        assertTrue("file marked done", sm.isComplete(sid))
        assertEquals("/captured/f1.bin", sink.finalized)
    }

    /** A precondition the receiver honoured rides back on the 200 as a header; a plain sink adds none. */
    @Test
    fun `upload carries the sink's success headers back to the sender`() {
        val sm = SessionManager()
        val bytes = "ack me".toByteArray()
        val (sid, slots) = sm.createSession("1.1.1.1", mapOf("f1" to fileMeta("f1", bytes)))
        val acknowledging = object : FileSink by CapturingFileSink() {
            override fun successHeaders(meta: FileMetadata) = mapOf(UploadReplyHeaders.HANDOFF_INSTALLED to "true")
        }

        val result = UploadHandler(sm, acknowledging).handle(
            sessionId = sid, fileId = "f1", token = slots.getValue("f1").token, sourceIp = "1.1.1.1",
            body = ByteArrayFileSource(bytes).openRead(),
        )

        assertTrue(UploadReplyHeaders.handoffInstalled(result.headers))
        assertTrue("case-insensitive like HTTP", UploadReplyHeaders.handoffInstalled(mapOf("x-migration-handoff-installed" to "TRUE")))
        assertTrue("a bare 200 acknowledges nothing", !UploadReplyHeaders.handoffInstalled(emptyMap()))
    }

    @Test
    fun `T-S7 upload whose streamed sha256 mismatches the declared digest fails and does not finalize`() {
        val sm = SessionManager()
        val sink = CapturingFileSink()
        val realBytes = "real content".toByteArray()
        // Declare a metadata sha256 for DIFFERENT bytes → mismatch on stream.
        val lyingMeta = fileMeta("f1", "other content".toByteArray())
        val (sid, slots) = sm.createSession("1.1.1.1", mapOf("f1" to lyingMeta))
        val token = slots.getValue("f1").token

        assertThrows(ProtocolError.Internal::class.java) {
            UploadHandler(sm, sink).handle(
                sessionId = sid, fileId = "f1", token = token, sourceIp = "1.1.1.1",
                body = ByteArrayFileSource(realBytes).openRead(),
            )
        }
        assertNull("must NOT finalize a corrupt file", sink.finalized)
        assertTrue("must NOT mark done on integrity failure", !sm.isComplete(sid))
    }

    @Test
    fun `upload with no declared sha256 skips integrity check and finalizes`() {
        val sm = SessionManager()
        val sink = CapturingFileSink()
        val bytes = "no-digest".toByteArray()
        val (sid, slots) = sm.createSession("1.1.1.1", mapOf("f1" to fileMeta("f1", bytes, withSha = false)))
        val token = slots.getValue("f1").token
        val result = UploadHandler(sm, sink).handle(
            sessionId = sid, fileId = "f1", token = token, sourceIp = "1.1.1.1",
            body = ByteArrayFileSource(bytes).openRead(),
        )
        assertArrayEquals(bytes, sink.bytesOf("f1"))
        assertEquals(Sha256.hex(bytes), result.sha256)
    }
}
