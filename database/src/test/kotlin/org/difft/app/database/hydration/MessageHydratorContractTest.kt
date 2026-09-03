package org.difft.app.database.hydration

import kotlinx.coroutines.runBlocking
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.builders.buildAttachmentModel
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Whole-call contracts of [MessageHydrator]: no queries for an empty window, group integrity at
 * scale, and unchanged failure semantics.
 *
 * Test rows #31, #33 (hydrator half — see [MessageChildRowChunkingTest] for the chunk-size half)
 * and #34 of the design inventory.
 */
class MessageHydratorContractTest {

    /** #31 — an empty window must issue no query at all, not fourteen `IN ()` statements. */
    @Test
    fun `an empty message list issues no query and returns EMPTY`() {
        val loader = FakeMessageChildRowLoader()

        val hydration = runBlocking { MessageHydrator(loader).hydrate(emptyList()) }

        assertEquals(emptyList<Any>(), loader.callLog)
        assertEquals(MessageHydration.EMPTY, hydration)
    }

    /**
     * #33 (hydrator half) — the hydrator itself never splits a key set: it hands the loader the
     * whole id list in one call and lets the loader decide how to chunk. Group membership and
     * intra-group order survive at scale.
     */
    @Test
    fun `a large window is passed to the loader as one key set with groups intact`() {
        val messageCount = 1_200
        val messages = (0 until messageCount).map { buildMessageModel("m$it", 1_000L + it) }
        val attachments = messages.flatMapIndexed { index, message ->
            listOf(
                buildAttachmentModel(databaseId = 2 * index + 2, messageId = message.id),
                buildAttachmentModel(databaseId = 2 * index + 1, messageId = message.id),
            )
        }
        val corpus = ChildRowCorpus(messages = messages, attachments = attachments)
        val loader = FakeMessageChildRowLoader(corpus)

        val hydration = runBlocking { MessageHydrator(loader).hydrate(messages) }

        assertEquals(1, loader.callCount("attachmentsByMessageId"))
        assertEquals(messageCount, loader.keysPassedTo("attachmentsByMessageId").single().size)
        assertEquals(messageCount, hydration.size)
        messages.forEachIndexed { index, message ->
            assertEquals(
                "message ${message.id} must keep the lowest-databaseId row of its own group",
                "att-${2 * index + 1}",
                hydration[message.id].attachment?.id,
            )
        }
    }

    /**
     * #34 — a loader failure propagates unchanged. The point-query path has no `catch` either;
     * swallowing it here would silently render messages without their attachments.
     *
     * The throwable is a plain exception rather than a `WCDBException`: the latter has no
     * accessible constructor (it is created from native code), so it cannot be raised off-device.
     */
    @Test
    fun `a loader exception propagates instead of degrading to empty sub data`() {
        val boom = IllegalStateException("child row read failed")
        val corpus = ChildRowCorpus(messages = listOf(buildMessageModel("A", 1_000L)))
        val loader = FakeMessageChildRowLoader(
            corpus,
            failures = mapOf("attachmentsByMessageId" to boom),
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { MessageHydrator(loader).hydrate(corpus.messages) }
        }

        assertEquals(boom.message, thrown.message)
    }
}
