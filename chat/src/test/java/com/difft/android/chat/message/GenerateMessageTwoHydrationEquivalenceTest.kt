package com.difft.android.chat.message

import com.difft.android.base.utils.GlobalHiltEntryPoint
import difft.android.messageserialization.For
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test










@OptIn(ExperimentalCoroutinesApi::class)
class GenerateMessageTwoHydrationEquivalenceTest {

    private val corpus = ChildRowCorpus.rich()
    private val forWhat = For.Account(PEER_ID)

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val globalServicesMock: GlobalHiltEntryPoint = mockk(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns MY_ID
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
    }

    @Test
    fun `every message renders identically from batch hydration and from the point-query path`() = runTest {
        val hydration = MessageHydrator(FakeMessageChildRowLoader(corpus)).hydrate(corpus.messages)
        assertEquals(corpus.messages.size, hydration.size)

        corpus.messages.forEach { record ->
            val fromPointQueries = generateMessageTwo(
                forWhat, record, emptyList(), null, false, 0, corpus.referenceSubDataFor(record),
            ) as TextChatMessage
            val fromHydration = generateMessageTwo(
                forWhat, record, emptyList(), null, false, 0, hydration[record.id],
            ) as TextChatMessage

            assertEquals("attachment of ${record.id}", fromPointQueries.attachment, fromHydration.attachment)
            assertEquals("quote of ${record.id}", fromPointQueries.quote, fromHydration.quote)
            assertEquals(
                "forwardContext of ${record.id}",
                fromPointQueries.forwardContext,
                fromHydration.forwardContext,
            )
            assertEquals("mentions of ${record.id}", fromPointQueries.mentions, fromHydration.mentions)
            assertEquals("reactions of ${record.id}", fromPointQueries.reactions, fromHydration.reactions)
            assertEquals(
                "sharedContacts of ${record.id}",
                fromPointQueries.sharedContacts,
                fromHydration.sharedContacts,
            )
            assertEquals(
                "translateData of ${record.id}",
                fromPointQueries.translateData,
                fromHydration.translateData,
            )
            assertEquals(
                "speechToTextData of ${record.id}",
                fromPointQueries.speechToTextData,
                fromHydration.speechToTextData,
            )
            
            assertEquals("whole message ${record.id}", fromPointQueries, fromHydration)
        }
    }

    
    
    @Test
    fun `the corpus really exercises every child-row family`() = runTest {
        val hydration = MessageHydrator(FakeMessageChildRowLoader(corpus)).hydrate(corpus.messages)
        val messages = corpus.messages.associate { record ->
            record.id to generateMessageTwo(
                forWhat, record, emptyList(), null, false, 0, hydration[record.id],
            ) as TextChatMessage
        }

        assertEquals("att-31", messages.getValue("m-attach").attachment?.id)
        assertEquals(3, messages.getValue("m-mention").mentions?.size)
        assertEquals(2, messages.getValue("m-reaction").reactions?.size)
        assertEquals(2, messages.getValue("m-quote").quote?.attachments?.size)
        assertEquals(null, messages.getValue("m-quote-bare").quote?.attachments)
        assertEquals(3, messages.getValue("m-contacts").sharedContacts?.first()?.phone?.size)
        assertEquals("cn-5", messages.getValue("m-derived").translateData?.translatedContentCN)
        assertEquals("stt-6", messages.getValue("m-derived").speechToTextData?.speechToTextContent)
        assertEquals(3, messages.getValue("m-forward-flat").forwardContext?.forwards?.size)
        
        assertEquals("", messages.getValue("m-forward-nested").message)
    }

    private companion object {
        const val MY_ID = "my-uid"
        const val PEER_ID = "peer-uid"
    }
}
