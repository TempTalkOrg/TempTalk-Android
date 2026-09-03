package org.difft.app.database.hydration

import kotlinx.coroutines.runBlocking
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sub-data-level equivalence over the whole `ChildRowCorpus.rich()` corpus: for every message, the
 * batch-hydrated [MessageSubData] must equal what the reference point-query implementation
 * produces from the same rows.
 *
 * This is the row-selection half of the equivalence argument (the field-mapping half is structural
 * — both paths call the same `ChildRowMappers`). It is also the guard on the fixture itself: the
 * end-to-end `generateMessageTwo` equivalence case is built on `rich()` + `referenceSubDataFor`,
 * and a corpus that disagreed with the hydrator here would make that case meaningless.
 */
class MessageHydratorCorpusEquivalenceTest {

    @Test
    fun `every message in the rich corpus hydrates to its reference sub data`() {
        val corpus = ChildRowCorpus.rich()

        val hydration = runBlocking {
            MessageHydrator(FakeMessageChildRowLoader(corpus)).hydrate(corpus.messages)
        }

        assertEquals(12, corpus.messages.size)
        corpus.messages.forEach { message ->
            assertEquals(
                "sub data for ${message.id}",
                corpus.referenceSubDataFor(message),
                hydration[message.id],
            )
        }
    }
}
