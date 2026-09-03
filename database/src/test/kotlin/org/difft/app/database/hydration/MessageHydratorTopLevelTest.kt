package org.difft.app.database.hydration

import kotlinx.coroutines.runBlocking
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.builders.buildAttachmentModel
import org.difft.app.database.test.builders.buildMentionModel
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.builders.buildQuoteModel
import org.difft.app.database.test.builders.buildReactionModel
import org.difft.app.database.test.builders.buildSharedContactModel
import org.difft.app.database.test.builders.buildSharedContactPhoneModel
import org.difft.app.database.test.builders.buildSpeechToTextModel
import org.difft.app.database.test.builders.buildTranslateModel
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.difft.app.database.toAttachment
import org.difft.app.database.toMention
import org.difft.app.database.toReaction
import org.difft.app.database.toSpeechToTextData
import org.difft.app.database.toTranslateData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Top-level (L1) grouping contract of [MessageHydrator] plus the two fixed-depth second-level
 * fan-outs that hang off it (quote attachments, shared-contact phones).
 *
 * Every case seeds `databaseId` values OUT of insertion order: the batch path groups rows itself,
 * so "the loader returned them sorted" must be visible in the output, not assumed.
 *
 * Test rows #18..#24 of the design inventory.
 */
class MessageHydratorTopLevelTest {

    /**
     * `runBlocking`, not `runTest`: `hydrate` has no delays and no virtual time (it is CPU work
     * behind a `withContext(IO)`), and `runTest`'s `TestResult` is `Unit` on the JVM so it cannot
     * hand the result back to the assertions.
     */
    private fun hydrate(corpus: ChildRowCorpus) = runBlocking {
        MessageHydrator(FakeMessageChildRowLoader(corpus)).hydrate(corpus.messages)
    }

    /** #18 — one attachment per message: the lowest `databaseId` in the group wins, like `.firstOrNull()`. */
    @Test
    fun `attachment picks the first row by databaseId and is null when the message has none`() {
        val expectedRow = buildAttachmentModel(databaseId = 3, messageId = "A")
        val corpus = ChildRowCorpus(
            messages = listOf(buildMessageModel("A", 1_000L), buildMessageModel("B", 2_000L)),
            attachments = listOf(
                buildAttachmentModel(databaseId = 7, messageId = "A"),
                expectedRow,
            ),
        )

        val hydration = hydrate(corpus)

        assertEquals(expectedRow.toAttachment(), hydration["A"].attachment)
        assertNull(hydration["B"].attachment)
    }

    /** #19 — mentions keep `databaseId ASC` order; the list takes part in ChatMessage.equals. */
    @Test
    fun `mentions are ordered by databaseId ascending`() {
        val rows = listOf(
            buildMentionModel(databaseId = 9, messageId = "A"),
            buildMentionModel(databaseId = 4, messageId = "A"),
            buildMentionModel(databaseId = 6, messageId = "A"),
        )
        val corpus = ChildRowCorpus(messages = listOf(buildMessageModel("A", 1_000L)), mentions = rows)

        val hydration = hydrate(corpus)

        assertEquals(
            rows.sortedBy { it.databaseId }.map { it.toMention() },
            hydration["A"].mentions,
        )
        assertEquals(listOf(4, 6, 9), rows.sortedBy { it.databaseId }.map { it.databaseId })
    }

    /** #20 — reactions ordered ascending; a message with none gets an empty list, never null. */
    @Test
    fun `reactions are ordered ascending and degrade to an empty list`() {
        val rows = listOf(
            buildReactionModel(databaseId = 12, messageId = "A"),
            buildReactionModel(databaseId = 11, messageId = "A"),
        )
        val corpus = ChildRowCorpus(
            messages = listOf(buildMessageModel("A", 1_000L), buildMessageModel("B", 2_000L)),
            reactions = rows,
        )

        val hydration = hydrate(corpus)

        assertEquals(rows.sortedBy { it.databaseId }.map { it.toReaction() }, hydration["A"].reactions)
        assertEquals(emptyList<Any>(), hydration["B"].reactions)
    }

    /** #21 — shared contacts plus their L2 phone fan-out; the four unused SharedContact slots stay null. */
    @Test
    fun `shared contacts carry their phones in databaseId order and null out the unused slots`() {
        val corpus = ChildRowCorpus(
            messages = listOf(buildMessageModel("A", 1_000L)),
            sharedContacts = listOf(
                buildSharedContactModel(databaseId = 21, messageId = "A"),
                buildSharedContactModel(databaseId = 22, messageId = "A"),
            ),
            sharedContactPhones = listOf(
                buildSharedContactPhoneModel(databaseId = 33, sharedContactDatabaseId = 21L),
                buildSharedContactPhoneModel(databaseId = 31, sharedContactDatabaseId = 21L),
                buildSharedContactPhoneModel(databaseId = 32, sharedContactDatabaseId = 21L),
            ),
        )

        val hydration = hydrate(corpus)
        val contacts = hydration["A"].sharedContacts

        assertEquals(2, contacts.size)
        assertEquals(listOf("phone-31", "phone-32", "phone-33"), contacts[0].phone?.map { it.value })
        assertEquals(emptyList<Any>(), contacts[1].phone)
        assertEquals("display-21", contacts[0].name?.displayName)
        contacts.forEach {
            assertNull(it.avatar)
            assertNull(it.email)
            assertNull(it.address)
            assertNull(it.organization)
        }
    }

    /** #22 — translate / speech-to-text rows map field for field; absent rows stay null. */
    @Test
    fun `translate and speech to text map field for field`() {
        val translateRow = buildTranslateModel(databaseId = 5, messageId = "A")
        val sttRow = buildSpeechToTextModel(databaseId = 6, messageId = "A")
        val corpus = ChildRowCorpus(
            messages = listOf(buildMessageModel("A", 1_000L), buildMessageModel("B", 2_000L)),
            translates = listOf(translateRow),
            speechToTexts = listOf(sttRow),
        )

        val hydration = hydrate(corpus)

        assertEquals(translateRow.toTranslateData(), hydration["A"].translateData)
        assertEquals(sttRow.toSpeechToTextData(), hydration["A"].speechToTextData)
        assertNull(hydration["B"].translateData)
        assertNull(hydration["B"].speechToTextData)
    }

    /**
     * #23 — quote plus its L2 attachments. The quoted attachment mapper is deliberately NOT
     * `toAttachment()`: an empty thumbnail byte array normalises to null here.
     */
    @Test
    fun `quote attachments use the quoted mapper and normalise an empty thumbnail to null`() {
        val corpus = ChildRowCorpus(
            messages = listOf(
                buildMessageModel("A", 1_000L).apply { quoteDatabaseId = 5L },
            ),
            quotes = listOf(buildQuoteModel(databaseId = 5)),
            attachments = listOf(
                buildAttachmentModel(databaseId = 102, quoteModelDatabaseId = 5L, thumbnail = byteArrayOf(1, 2)),
                buildAttachmentModel(databaseId = 101, quoteModelDatabaseId = 5L, thumbnail = ByteArray(0)),
            ),
        )

        val hydration = hydrate(corpus)
        val quote = requireNotNull(hydration["A"].quote)

        assertEquals(5L, quote.id)
        assertEquals("author-5", quote.author)
        assertEquals("quoted-5", quote.text)
        val attachments = requireNotNull(quote.attachments)
        assertEquals(2, attachments.size)
        assertEquals(listOf("att-101", "att-102"), attachments.map { it.thumbnail?.id })
        assertNull("empty byte array must normalise to null", attachments[0].thumbnail?.thumbnail)
        assertTrue(attachments[1].thumbnail?.thumbnail?.isNotEmpty() == true)
        // Quoted attachments carry neither totalTime nor amplitudes, unlike toAttachment().
        assertEquals(0L, attachments[0].thumbnail?.totalTime)
        assertNull(attachments[0].thumbnail?.amplitudes)
    }

    /** #24 — a quote with no attachment rows reports `null`, not an empty list (`ifEmpty { null }`). */
    @Test
    fun `quote without attachments reports null attachments`() {
        val corpus = ChildRowCorpus(
            messages = listOf(buildMessageModel("A", 1_000L).apply { quoteDatabaseId = 5L }),
            quotes = listOf(buildQuoteModel(databaseId = 5)),
        )

        val hydration = hydrate(corpus)

        assertNull(requireNotNull(hydration["A"].quote).attachments)
    }
}
