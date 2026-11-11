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
        
        assertTrue(imgBytes.contentEquals(read[0].thumbnail?.thumbnail))
        assertEquals(100, read[0].thumbnail?.width)
        assertEquals(200, read[0].thumbnail?.height)
        assertEquals(AttachmentStatus.SUCCESS.code, read[0].thumbnail?.status)
        
        assertEquals(1, read[1].flags)
        assertNull(read[1].thumbnail?.thumbnail)
        assertEquals(0, read[1].thumbnail?.size)
        
        assertEquals("application/pdf", read[2].contentType)

        
        val quote2 = Quote(
            id = 2000L, author = "bob", text = "yo",
            attachments = listOf(QuotedAttachment("image/png", "x.png", imageAttachment(byteArrayOf(9)), 0))
        )
        val model2 = wcdb.insertChildrenAndBuildMessageModel(textMessage("m2", 2000L, quote2))
        wcdb.message.insertObject(model2)
        assertEquals(3, readQuoteAttachments(qId).size) 
        assertEquals(1, readQuoteAttachments(model2.quoteDatabaseId!!).size)
    }

    
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

        
        wcdb.attachment.deleteObjects(DBAttachmentModel.quoteModelDatabaseId.eq(qId))
        wcdb.quote.deleteObjects(DBQuoteModel.databaseId.eq(qId))

        assertTrue(readQuoteAttachments(qId).isEmpty())
        assertNull(wcdb.quote.getFirstObject(DBQuoteModel.databaseId.eq(qId)))
    }

    
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
