package org.difft.app.database.hydration

import kotlinx.coroutines.runBlocking
import org.difft.app.database.test.builders.plainCorpus
import org.difft.app.database.test.builders.uniformRichCorpus
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the batched query count at the benchmark window tiers (issue #1162): the number the
 * `:microbenchmark` module measures as real SELECT statements on-device is fixed here as a
 * loader-call contract on the host JVM, where it runs on every `testAll`.
 *
 * At these sizes loader calls and SQL statements coincide (`IN` chunking starts at 500 keys):
 * plain tier = 6 (one per always-queried child table; quote/forward/phone key sets are empty and
 * an empty key set issues no query), uniform rich tier = 14 (adds quote, quote-attachments,
 * forward-context, top forwards, two BFS child levels — the second proving the frontier empty —
 * forward attachments and forward mentions). The old point-query path at the same tiers issues
 * 6 and 24 SELECTs PER MESSAGE (1080 / 4320 at 180) — measured on-device in `QueryCountTest`.
 */
class MessageHydratorQueryCountTest {

    @Test
    fun `plain tier hydrates 180 messages in 6 loader calls`() {
        val corpus = plainCorpus(180)
        val loader = FakeMessageChildRowLoader(corpus)

        val hydration = runBlocking { MessageHydrator(loader).hydrate(corpus.messages) }

        assertEquals(180, hydration.size)
        assertEquals(6, loader.callLog.size)
    }

    @Test
    fun `uniform rich tier hydrates 180 messages in 14 loader calls`() {
        val corpus = uniformRichCorpus(180)
        val loader = FakeMessageChildRowLoader(corpus)

        val hydration = runBlocking { MessageHydrator(loader).hydrate(corpus.messages) }

        assertEquals(180, hydration.size)
        assertEquals(14, loader.callLog.size)
    }
}
