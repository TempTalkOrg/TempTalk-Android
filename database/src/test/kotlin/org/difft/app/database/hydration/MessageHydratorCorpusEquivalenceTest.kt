package org.difft.app.database.hydration

import kotlinx.coroutines.runBlocking
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.Assert.assertEquals
import org.junit.Test











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
