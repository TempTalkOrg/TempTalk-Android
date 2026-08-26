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

/**
 * Case #29 — the main equivalence assertion for the whole hydration change: for every message in a
 * corpus that covers all eight child-row families and all three second-level paths, the
 * `ChatMessage` built from BATCH-hydrated sub-data is field-for-field equal to the one built from
 * sub-data resolved the way the per-message point queries resolve it.
 *
 * Two independent implementations meet here. `ChildRowCorpus.referenceSubDataFor` is a literal
 * re-statement of `WCDBExtensions.kt`'s point-query path (same predicate, `sortedBy { databaseId }`,
 * naive unbounded forward recursion); `MessageHydrator` is the batched one. `TextChatMessage.equals`
 * compares all eight sub-data fields, so `assertEquals` on the produced messages is a real
 * field-for-field check rather than an id comparison — and the per-field assertions below make the
 * failure message say WHICH family diverged.
 *
 * `WCDBExtensionsKt` is deliberately NOT mocked here: the corpus messages carry no `screenShotJson`,
 * so `screenShot()` short-circuits before touching anything, and `AttachmentModel.toAttachment()`
 * (which lives in that facade and IS used by both paths) has to be the real mapper for the
 * comparison to mean anything.
 */
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
            // The DiffUtil-visible verdict: equal by the equals() the adapter actually uses.
            assertEquals("whole message ${record.id}", fromPointQueries, fromHydration)
        }
    }

    // Guard on the guard: a corpus that produced empty sub-data for everything would make the case
    // above pass vacuously.
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
        // Nested forward: the combined-forward branch also blanks the text.
        assertEquals("", messages.getValue("m-forward-nested").message)
    }

    private companion object {
        const val MY_ID = "my-uid"
        const val PEER_ID = "peer-uid"
    }
}
