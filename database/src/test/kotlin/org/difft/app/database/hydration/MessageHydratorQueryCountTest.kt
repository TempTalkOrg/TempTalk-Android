package org.difft.app.database.hydration

import kotlinx.coroutines.runBlocking
import org.difft.app.database.test.builders.plainCorpus
import org.difft.app.database.test.builders.uniformRichCorpus
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.Assert.assertEquals
import org.junit.Test













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
