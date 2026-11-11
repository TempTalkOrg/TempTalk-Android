package com.difft.android.chat.messages

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.test.fakes.TestMessagingPause
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










class EnvelopToMessageProcessorClassifyTest {

    private lateinit var decryptionUtil: NewMessageDecryptionUtil
    private lateinit var contentProcessor: MessageContentProcessor
    private lateinit var dbMessageStore: DBMessageStore
    private lateinit var processor: EnvelopToMessageProcessor
    private val admission = requireNotNull(TestMessagingPause().open().gate.tryAdmit(AdmissionKind.RECEIVE, "test"))

    @Before
    fun setUp() {
        decryptionUtil = mockk(relaxed = true)
        contentProcessor = mockk(relaxed = true)
        dbMessageStore = mockk(relaxed = true)
        processor = EnvelopToMessageProcessor(
            newMessageDecryptionUtil = decryptionUtil,
            messageContentProcessor = contentProcessor,
            dbMessageStore = dbMessageStore,
            healthCounters = mockk(relaxed = true),
            lateDeliveryTracker = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
        )
    }

    private fun envelope(): Envelope = Envelope.newBuilder()
        .setTimestamp(12345L)
        .build()

    @Test
    fun `decrypt throws DecryptMessageDataException - PermanentFailure DECRYPTION_FAILED`() = runBlocking {
        val cause = DtProtoException.DecryptMessageDataException("MAC failed")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.PermanentFailure)
        assertEquals(DropReason.DECRYPTION_FAILED, (res as EnvelopeProcessResult.PermanentFailure).reason)
        assertEquals(cause, res.cause)
    }

    @Test
    fun `decrypt throws other DtProtoException variant - TransientFailure (conservative)`() = runBlocking {
        
        
        val cause = DtProtoException.VersionException("version error")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
        assertEquals(cause, (res as EnvelopeProcessResult.TransientFailure).cause)
    }

    @Test
    fun `decrypt throws InvalidProtocolBufferException - PermanentFailure DECRYPTION_DATA_CORRUPT`() = runBlocking {
        val cause = InvalidProtocolBufferException("corrupt bytes")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.PermanentFailure)
        assertEquals(DropReason.DECRYPTION_DATA_CORRUPT, (res as EnvelopeProcessResult.PermanentFailure).reason)
    }

    @Test
    fun `decrypt throws JsonSyntaxException - PermanentFailure MALFORMED_NOTIFY_JSON`() = runBlocking {
        val cause = JsonSyntaxException("bad json")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.PermanentFailure)
        assertEquals(DropReason.MALFORMED_NOTIFY_JSON, (res as EnvelopeProcessResult.PermanentFailure).reason)
    }

    @Test
    fun `decrypt throws Base64DecodeException - PermanentFailure BASE64_DECODE_FAILED`() = runBlocking {
        val cause = Base64DecodeException("identityKey", null)
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.PermanentFailure)
        assertEquals(DropReason.BASE64_DECODE_FAILED, (res as EnvelopeProcessResult.PermanentFailure).reason)
    }

    @Test
    fun `decrypt throws IOException - TransientFailure`() = runBlocking {
        val cause = IOException("network")
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
    }

    @Test
    fun `decrypt throws NullPointerException - TransientFailure`() = runBlocking {
        every { decryptionUtil.decrypt(any()) } throws NullPointerException("contact not yet synced")

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
    }

    @Test
    fun `decrypt throws IllegalArgumentException (non-Base64) - TransientFailure`() = runBlocking {
        every { decryptionUtil.decrypt(any()) } throws IllegalArgumentException("not from decryption stack")

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
    }

    @Test
    fun `decrypt throws WCDBException (from MCP internal write) - TransientFailure`() = runBlocking {
        
        
        
        
        val cause: WCDBException = mockk(relaxed = true)
        every { decryptionUtil.decrypt(any()) } throws cause

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.TransientFailure)
    }

    @Test
    fun `CancellationException is rethrown, not classified`() = runBlocking {
        every { decryptionUtil.decrypt(any()) } throws CancellationException("scope cancel")

        try {
            processor.process(envelope(), "test", admission)
            assertTrue("Expected CancellationException to propagate", false)
        } catch (e: CancellationException) {
            
        }
    }

    @Test
    fun `OutOfMemoryError is rethrown, not classified`() = runBlocking {
        every { decryptionUtil.decrypt(any()) } throws OutOfMemoryError("oom")

        try {
            processor.process(envelope(), "test", admission)
            assertTrue("Expected OutOfMemoryError to propagate", false)
        } catch (e: OutOfMemoryError) {
            
        }
    }

    @Test
    fun `success path with null decryption result returns Success(null)`() = runBlocking {
        
        
        every { decryptionUtil.decrypt(any()) } returns null

        val res = processor.process(envelope(), "test", admission)

        assertTrue(res is EnvelopeProcessResult.Success)
        assertEquals(null, (res as EnvelopeProcessResult.Success).result)
    }
}
