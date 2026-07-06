package com.difft.android.chat.messages

import com.difft.android.messageserialization.db.store.DBMessageStore
import com.google.gson.JsonSyntaxException
import com.google.protobuf.InvalidProtocolBufferException
import com.tencent.wcdb.base.WCDBException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import uniffi.dtproto.DtProtoException
import java.io.IOException

/**
 * Drives [EnvelopToMessageProcessor.process] through every exception type in
 * the [DropReason] table and verifies the classification routing.
 *
 * Failures from the decrypt → MCP pipeline are injected by stubbing
 * `NewMessageDecryptionUtil.decrypt` to throw — that lets us cover the entire
 * `process()` → `classify()` → [EnvelopeProcessResult] chain without needing
 * a real DtProto / WCDB stack.
 */
class EnvelopToMessageProcessorClassifyTest {

    private lateinit var decryptionUtil: NewMessageDecryptionUtil
    private lateinit var contentProcessor: MessageContentProcessor
    private lateinit var dbMessageStore: DBMessageStore
    private lateinit var processor: EnvelopToMessageProcessor

    @Before
    fun setUp() {
        decryptionUtil = mockk(relaxed = true)
        contentProcessor = mockk(relaxed = true)
        dbMessageStore = mockk(relaxed = true)
        processor = EnvelopToMessageProcessor(
            newMessageDecryptionUtil = decryptionUtil,
            messageContentProcessor = contentProcessor,
            dbMessageStore = dbMessageStore,
        )
    }

    private fun envelope(): Envelope = Envelope.newBuilder()
        .setTimestamp(12345L)
        .build()

    @Test
    fun `decrypt throws DecryptMessageDataException - PermanentFailure DECRYPTION_FAILED`() = runBlocking {
        val cause = DtProtoException.DecryptMessageDataException("MAC failed")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.PermanentFailure)
        assertEquals(DropReason.DECRYPTION_FAILED, (res as EnvelopeProcessResult.PermanentFailure).reason)
        assertEquals(cause, res.cause)
    }

    @Test
    fun `decrypt throws other DtProtoException variant - TransientFailure (conservative)`() = runBlocking {
        // VersionException isn't the deterministic-failure variant we trust;
        // default to transient so we don't drop messages that could recover.
        val cause = DtProtoException.VersionException("version error")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
        assertEquals(cause, (res as EnvelopeProcessResult.TransientFailure).cause)
    }

    @Test
    fun `decrypt throws InvalidProtocolBufferException - PermanentFailure DECRYPTION_DATA_CORRUPT`() = runBlocking {
        val cause = InvalidProtocolBufferException("corrupt bytes")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.PermanentFailure)
        assertEquals(DropReason.DECRYPTION_DATA_CORRUPT, (res as EnvelopeProcessResult.PermanentFailure).reason)
    }

    @Test
    fun `decrypt throws JsonSyntaxException - PermanentFailure MALFORMED_NOTIFY_JSON`() = runBlocking {
        val cause = JsonSyntaxException("bad json")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.PermanentFailure)
        assertEquals(DropReason.MALFORMED_NOTIFY_JSON, (res as EnvelopeProcessResult.PermanentFailure).reason)
    }

    @Test
    fun `decrypt throws Base64DecodeException - PermanentFailure BASE64_DECODE_FAILED`() = runBlocking {
        val cause = Base64DecodeException("identityKey", null)
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.PermanentFailure)
        assertEquals(DropReason.BASE64_DECODE_FAILED, (res as EnvelopeProcessResult.PermanentFailure).reason)
    }

    @Test
    fun `decrypt throws IOException - TransientFailure`() = runBlocking {
        val cause = IOException("network")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
    }

    @Test
    fun `decrypt throws NullPointerException - TransientFailure`() = runBlocking {
        every { decryptionUtil.decrypt(any()) } throws NullPointerException("contact not yet synced")

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
    }

    @Test
    fun `decrypt throws IllegalArgumentException (non-Base64) - TransientFailure`() = runBlocking {
        every { decryptionUtil.decrypt(any()) } throws IllegalArgumentException("not from decryption stack")

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
    }

    @Test
    fun `decrypt throws WCDBException (from MCP internal write) - TransientFailure`() = runBlocking {
        // WCDBException thrown anywhere inside the decrypt → MCP chain falls
        // into classify's else → TransientFailure. Disk-full / DB-lock are
        // recoverable so retrying is the right call. (Use mockk because
        // WCDBException's public constructor isn't trivially callable from tests.)
        val cause: WCDBException = mockk(relaxed = true)
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
    }

    @Test
    fun `CancellationException is rethrown, not classified`() = runBlocking {
        every { decryptionUtil.decrypt(any()) } throws CancellationException("scope cancel")

        try {
            processor.process(envelope(), "test")
            assertTrue("Expected CancellationException to propagate", false)
        } catch (e: CancellationException) {
            // Expected — coroutine cancel must not be swallowed by classify.
        }
    }

    @Test
    fun `OutOfMemoryError is rethrown, not classified`() = runBlocking {
        every { decryptionUtil.decrypt(any()) } throws OutOfMemoryError("oom")

        try {
            processor.process(envelope(), "test")
            assertTrue("Expected OutOfMemoryError to propagate", false)
        } catch (e: OutOfMemoryError) {
            // Expected — system errors bubble up to crash reporters.
        }
    }

    @Test
    fun `success path with null decryption result returns Success(null)`() = runBlocking {
        // Envelope that survives takeIf chain but decrypt returns null
        // (e.g., unsupported version dropped by NewMessageDecryptionUtil).
        every { decryptionUtil.decrypt(any()) } returns null

        val res = processor.process(envelope(), "test")

        assertTrue(res is EnvelopeProcessResult.Success)
        assertEquals(null, (res as EnvelopeProcessResult.Success).result)
    }
}
