package com.difft.android.chat.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.QuotedAttachment
import io.mockk.mockk
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.attachmentPointer

/**
 * D4-D6 — Unit tests for the REAL [MessageContentProcessor.createQuoteThumbnailAttachment]
 * (②, wire receive/parse) plus the proto→domain list-mapping + `ifEmpty { null }` collapse.
 *
 * [MessageContentProcessor] has a 16-dependency constructor but its body starts directly with
 * methods (no heavy init), and `createQuoteThumbnailAttachment` reads only its `pointer` arg —
 * so a relaxed-mock instance lets us exercise production code directly. Robolectric is used only
 * to satisfy the `@ApplicationContext` parameter; the method itself touches no Android framework.
 *
 * D6 asserts the documented list-mapping contract (`attachmentsList.map { ... }.ifEmpty { null }`)
 * exactly as wired in `handleDataMessage` — text-only quote (empty list) collapses to null;
 * each proto element maps to one [QuotedAttachment] preserving contentType/fileName/flags.
 *
 * Verify: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreateQuoteThumbnailAttachmentTest {

    private val processor: MessageContentProcessor by lazy {
        MessageContentProcessor(
            context = ApplicationProvider.getApplicationContext<Application>(),
            dbRoomStore = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            asyncMessageJobsManager = mockk(relaxed = true),
            contactsUpdater = mockk(relaxed = true),
            groupUpdater = mockk(relaxed = true),
            messageArchiveManager = mockk(relaxed = true),
            lCallManagerProvider = mockk(relaxed = true),
            receiptMessageHelper = mockk(relaxed = true),
            messageNotificationUtil = mockk(relaxed = true),
            conversationSettingsManager = mockk(relaxed = true),
            localMessageCreator = mockk(relaxed = true),
            groupCryptoRepo = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
            weakContactReconciler = mockk(relaxed = true),
            gson = mockk(relaxed = true),
        )
    }

    // ── D4: inline thumbnail bytes present ───────────────────────────────────────

    @Test
    fun `D4 creates Attachment with inline thumbnail bytes`() {
        val thumbBytes = byteArrayOf(10, 20, 30)
        val pointer = attachmentPointer {
            contentType = "image/png"
            thumbnail = ByteString.copyFrom(thumbBytes)
            width = 100
            height = 150
            flags = 0
        }

        val result = processor.createQuoteThumbnailAttachment(pointer)

        assertEquals("", result.id)
        assertEquals(0L, result.authorityId)
        assertEquals("image/png", result.contentType)
        assertNull(result.key)
        assertEquals(3, result.size)
        assertArrayEquals(thumbBytes, result.thumbnail)
        assertNull(result.digest)
        assertNull(result.path)
        assertEquals(100, result.width)
        assertEquals(150, result.height)
        assertEquals(0, result.flags)
        assertEquals(AttachmentStatus.SUCCESS.code, result.status)
    }

    // ── D5: edge cases (no thumbnail / empty bytes / no fileName / voice) ─────────

    @Test
    fun `D5 creates Attachment without thumbnail when pointer has no thumbnail`() {
        val pointer = attachmentPointer {
            contentType = "application/pdf"
            flags = 0
        }

        val result = processor.createQuoteThumbnailAttachment(pointer)

        assertEquals("application/pdf", result.contentType)
        assertNull(result.thumbnail)
        assertEquals(0, result.size)
        assertEquals(AttachmentStatus.SUCCESS.code, result.status)
    }

    @Test
    fun `D5 treats empty thumbnail bytes as no thumbnail`() {
        val pointer = attachmentPointer {
            contentType = "image/jpeg"
            thumbnail = ByteString.EMPTY
            width = 200
            height = 300
        }

        val result = processor.createQuoteThumbnailAttachment(pointer)

        assertNull(result.thumbnail)
        assertEquals(0, result.size)
        assertEquals("image/jpeg", result.contentType)
        assertEquals(200, result.width)
        assertEquals(300, result.height)
    }

    @Test
    fun `D5 fileName is null when pointer hasFileName is false`() {
        val pointer = attachmentPointer {
            contentType = "image/jpeg"
            thumbnail = ByteString.copyFrom(byteArrayOf(1, 2))
        }

        val result = processor.createQuoteThumbnailAttachment(pointer)

        assertNull(result.fileName)
    }

    @Test
    fun `D5 preserves fileName when pointer hasFileName is true`() {
        val pointer = attachmentPointer {
            contentType = "image/jpeg"
            fileName = "photo.jpg"
            thumbnail = ByteString.copyFrom(byteArrayOf(1, 2))
        }

        val result = processor.createQuoteThumbnailAttachment(pointer)

        assertEquals("photo.jpg", result.fileName)
    }

    @Test
    fun `D5 preserves flags and contentType for voice attachment`() {
        val pointer = attachmentPointer {
            contentType = "audio/aac"
            flags = 1
        }

        val result = processor.createQuoteThumbnailAttachment(pointer)

        assertEquals(1, result.flags)
        assertEquals("audio/aac", result.contentType)
    }

    // ── D6: proto attachmentsList → List<QuotedAttachment> mapping + ifEmpty{null} ─

    @Test
    fun `D6 empty attachmentsList collapses to null via ifEmpty`() {
        val quoteMessage = SignalServiceProtos.DataMessage.Quote.newBuilder()
            .setId(123L)
            .setAuthor("alice")
            .setText("hi")
            .build() // no attachments

        val mapped = mapQuotedAttachments(quoteMessage)

        assertNull(mapped)
    }

    @Test
    fun `D6 maps each proto element to a QuotedAttachment preserving type fileName flags`() {
        val quoteMessage = SignalServiceProtos.DataMessage.Quote.newBuilder()
            .setId(1L)
            .setAuthor("bob")
            .setText("")
            .addAttachments(
                SignalServiceProtos.DataMessage.Quote.QuotedAttachment.newBuilder()
                    .setContentType("image/jpeg")
                    .setFileName("p.jpg")
                    .setFlags(0)
                    .setThumbnail(
                        attachmentPointer {
                            contentType = "image/jpeg"
                            thumbnail = ByteString.copyFrom(byteArrayOf(9, 9))
                            width = 10
                            height = 20
                        }
                    )
            )
            .addAttachments(
                SignalServiceProtos.DataMessage.Quote.QuotedAttachment.newBuilder()
                    .setContentType("audio/aac")
                    .setFlags(1) // voice, no thumbnail
            )
            .addAttachments(
                SignalServiceProtos.DataMessage.Quote.QuotedAttachment.newBuilder()
                    .setContentType("application/pdf")
                    .setFileName("doc.pdf")
                    .setFlags(0) // file, no thumbnail
            )
            .build()

        val mapped = mapQuotedAttachments(quoteMessage)

        assertEquals(3, mapped!!.size)
        // image: has inline thumbnail bytes
        assertEquals("image/jpeg", mapped[0].contentType)
        assertEquals("p.jpg", mapped[0].fileName)
        assertEquals(0, mapped[0].flags)
        assertArrayEquals(byteArrayOf(9, 9), mapped[0].thumbnail?.thumbnail)
        // voice: flags preserved, no thumbnail attachment
        assertEquals("audio/aac", mapped[1].contentType)
        assertEquals(1, mapped[1].flags)
        assertNull(mapped[1].thumbnail)
        // file: contentType/fileName preserved, no thumbnail attachment
        assertEquals("application/pdf", mapped[2].contentType)
        assertEquals("doc.pdf", mapped[2].fileName)
        assertNull(mapped[2].thumbnail)
    }

    /**
     * Mirrors the exact list-wiring in [MessageContentProcessor.handleDataMessage] quote block:
     * `attachmentsList.map { ... createQuoteThumbnailAttachment(...) ... }.ifEmpty { null }`.
     * Calls the REAL [MessageContentProcessor.createQuoteThumbnailAttachment] for each element so
     * a change to that production method is reflected here.
     */
    private fun mapQuotedAttachments(
        quoteMessage: SignalServiceProtos.DataMessage.Quote
    ): List<QuotedAttachment>? = quoteMessage.attachmentsList.map { protoQa ->
        val thumbnailAttachment: Attachment? = if (protoQa.hasThumbnail()) {
            processor.createQuoteThumbnailAttachment(protoQa.thumbnail)
        } else null
        QuotedAttachment(
            contentType = protoQa.contentType,
            fileName = protoQa.fileName,
            thumbnail = thumbnailAttachment,
            flags = protoQa.flags
        )
    }.ifEmpty { null }
}
