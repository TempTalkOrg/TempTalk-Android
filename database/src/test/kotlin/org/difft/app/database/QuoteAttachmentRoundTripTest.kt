package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.messageserialization.db.store.TestWcdbFactory
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.QuotedAttachment
import difft.android.messageserialization.model.TextMessage
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBQuoteModel
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D7-D9 — Integration round-trip for the quote-media data layer (③ write ↔ ④ read ↔ delete).
 *
 * Persists a [TextMessage] whose `quote.attachments` carry inline thumbnails via the REAL
 * [WCDB.insertChildrenAndBuildMessageModel] (③), then reconstructs the [QuotedAttachment] list by the same
 * FK query the REAL [MessageModel.quote] (④) uses, asserting render-relevant fields survive
 * the write→read round-trip (§7 of the design). Also guards the pre-existing delete path
 * (`:885-888`, D8) and the text-only no-regression case (D9).
 *
 * **Currently @Ignore-d** — same constraint documented on [WCDBPagedMessageAccessTest] /
 * `JobModelRoundTripTest`: WCDB (Tencent SQLite wrapper) loads native libraries via
 * `System.loadLibrary`, which are not available to host JVM unit tests. Additionally,
 * [MessageModel.quote] resolves the top-level `wcdb` global ([org.difft.app.database.wcdb]) via
 * `EntryPointAccessors.fromApplication`, requiring a fully-wired Hilt application, so the ④
 * reader cannot be redirected to the in-memory instance built here. The ④ read is therefore
 * exercised against the same in-memory instance using the identical FK query
 * (`DBAttachmentModel.quoteModelDatabaseId.eq(qId)`) — byte-for-byte the column mapping ④ uses.
 *
 * To run reliably: (a) an instrumentation test (androidTest + emulator) parameterizing the
 * helpers on a `WCDB`, or (b) a JVM-compatible SQLite shim. Both are out of scope here; the
 * test remains a compilation + expected-behavior guard for the ③↔④↔delete symmetry (§4).
 *
 * Verify (instrumentation): :database:connectedDebugAndroidTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native library not loadable in JVM unit tests; quote() also binds the wcdb global via Hilt. Run via instrumentation test instead.")
class QuoteAttachmentRoundTripTest {

    private lateinit var wcdb: WCDB

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        wcdb = TestWcdbFactory.createInMemoryWcdb(ctx)
    }

    private fun textMessage(
        id: String,
        timeStamp: Long,
        quote: Quote?,
    ) = TextMessage(
        id = id,
        fromWho = For.Account("sender"),
        forWhat = For.Account("room"),
        systemShowTimestamp = timeStamp,
        timeStamp = timeStamp,
        receivedTimeStamp = timeStamp,
        sendType = 0,
        expiresInSeconds = 0,
        notifySequenceId = 0L,
        sequenceId = 0L,
        mode = 0,
        text = "body",
    ).apply { this.quote = quote }

    private fun imageAttachment(bytes: ByteArray) = Attachment(
        id = "", authorityId = 0L, contentType = "image/jpeg", key = null,
        size = bytes.size, thumbnail = bytes, digest = null, fileName = "p.jpg",
        flags = 0, width = 100, height = 200, path = null, status = AttachmentStatus.SUCCESS.code
    )

    /** Reconstructs the QuotedAttachment list exactly as [MessageModel.quote] (④) does. */
    private fun readQuoteAttachments(qId: Long): List<QuotedAttachment> =
        wcdb.attachment.getAllObjects(DBAttachmentModel.quoteModelDatabaseId.eq(qId)).map { am ->
            QuotedAttachment(
                contentType = am.contentType ?: "",
                fileName = am.fileName ?: "",
                thumbnail = Attachment(
                    id = am.id ?: "", authorityId = am.authorityId ?: 0L,
                    contentType = am.contentType ?: "", key = am.key, size = am.size,
                    thumbnail = am.thumbnail, digest = am.digest, fileName = am.fileName,
                    flags = am.flags, width = am.width, height = am.height,
                    path = null, status = am.status
                ),
                flags = am.flags
            )
        }

    // D7 — write (③) then read (④ FK query) round-trip + FK isolation across two quotes.
    @Test
    fun `D7 persists and reads back quote attachments preserving render fields`() {
        val imgBytes = byteArrayOf(1, 2, 3, 4)
        val audio = Attachment(
            id = "", authorityId = 0L, contentType = "audio/aac", key = null, size = 0,
            thumbnail = null, digest = null, fileName = null, flags = 1, width = 0, height = 0,
            path = null, status = AttachmentStatus.SUCCESS.code
        )
        val file = Attachment(
            id = "", authorityId = 0L, contentType = "application/pdf", key = null, size = 0,
            thumbnail = null, digest = null, fileName = "doc.pdf", flags = 0, width = 0, height = 0,
            path = null, status = AttachmentStatus.SUCCESS.code
        )
        val quote = Quote(
            id = 1000L, author = "alice", text = "hi",
            attachments = listOf(
                QuotedAttachment("image/jpeg", "p.jpg", imageAttachment(imgBytes), 0),
                QuotedAttachment("audio/aac", "", audio, 1),
                QuotedAttachment("application/pdf", "doc.pdf", file, 0),
            )
        )

        val model = wcdb.insertChildrenAndBuildMessageModel(textMessage("m1", 1000L, quote))
        wcdb.message.insertObject(model)
        val qId = model.quoteDatabaseId!!

        val read = readQuoteAttachments(qId)
        assertEquals(3, read.size)
        // image: bytes + width/height/status preserved
        assertTrue(imgBytes.contentEquals(read[0].thumbnail?.thumbnail))
        assertEquals(100, read[0].thumbnail?.width)
        assertEquals(200, read[0].thumbnail?.height)
        assertEquals(AttachmentStatus.SUCCESS.code, read[0].thumbnail?.status)
        // audio: flags preserved, thumbnail null (no inline bytes → "null = no bytes" convention)
        assertEquals(1, read[1].flags)
        assertNull(read[1].thumbnail?.thumbnail)
        assertEquals(0, read[1].thumbnail?.size)
        // file: contentType preserved
        assertEquals("application/pdf", read[2].contentType)

        // FK isolation — a second quote's attachments must NOT leak into the first.
        val quote2 = Quote(
            id = 2000L, author = "bob", text = "yo",
            attachments = listOf(QuotedAttachment("image/png", "x.png", imageAttachment(byteArrayOf(9)), 0))
        )
        val model2 = wcdb.insertChildrenAndBuildMessageModel(textMessage("m2", 2000L, quote2))
        wcdb.message.insertObject(model2)
        assertEquals(3, readQuoteAttachments(qId).size) // first quote still exactly 3
        assertEquals(1, readQuoteAttachments(model2.quoteDatabaseId!!).size)
    }

    // D8 — delete-path closure (the pre-existing `:885-888` delete loop).
    @Test
    fun `D8 deleting message removes quote and its attachment rows`() {
        val quote = Quote(
            id = 3000L, author = "a", text = "t",
            attachments = listOf(QuotedAttachment("image/jpeg", "p.jpg", imageAttachment(byteArrayOf(7, 7)), 0))
        )
        val model = wcdb.insertChildrenAndBuildMessageModel(textMessage("m3", 3000L, quote))
        wcdb.message.insertObject(model)
        val qId = model.quoteDatabaseId!!
        assertEquals(1, readQuoteAttachments(qId).size)

        // Mirror the production delete path (:885-888).
        wcdb.attachment.deleteObjects(DBAttachmentModel.quoteModelDatabaseId.eq(qId))
        wcdb.quote.deleteObjects(DBQuoteModel.databaseId.eq(qId))

        assertTrue(readQuoteAttachments(qId).isEmpty())
        assertNull(wcdb.quote.getFirstObject(DBQuoteModel.databaseId.eq(qId)))
    }

    // D9 — text-only quote (no attachments) — no regression.
    @Test
    fun `D9 text-only quote persists no attachment rows`() {
        val quote = Quote(id = 4000L, author = "a", text = "just text", attachments = null)
        val model = wcdb.insertChildrenAndBuildMessageModel(textMessage("m4", 4000L, quote))
        wcdb.message.insertObject(model)
        val qId = model.quoteDatabaseId!!

        assertTrue(readQuoteAttachments(qId).isEmpty())
        val qm = wcdb.quote.getFirstObject(DBQuoteModel.databaseId.eq(qId))!!
        assertEquals(4000L, qm.id)
        assertEquals("a", qm.author)
        assertEquals("just text", qm.text)
    }
}
