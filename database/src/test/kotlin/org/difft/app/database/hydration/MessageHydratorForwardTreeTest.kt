package org.difft.app.database.hydration

import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.model.Forward
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.builders.buildAttachmentModel
import org.difft.app.database.test.builders.buildForwardContextModel
import org.difft.app.database.test.builders.buildForwardModel
import org.difft.app.database.test.builders.buildMentionModel
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nested-forward BFS: flat assembly, deep-tree equivalence against a naive recursion, the
 * [MAX_FORWARD_DEPTH] cap, the cycle guard, and Int/Long key alignment.
 *
 * Test rows #25..#28 and #32 of the design inventory.
 */
class MessageHydratorForwardTreeTest {

    @After
    fun tearDown() {
        unmockkStatic(L::class)
    }

    private fun hydrate(corpus: ChildRowCorpus, loader: FakeMessageChildRowLoader) =
        runBlocking { MessageHydrator(loader).hydrate(corpus.messages) }

    private fun messageWithContext(id: String, fcId: Long): MessageModel =
        buildMessageModel(id, 1_000L).apply { forwardContextDatabaseId = fcId }

    /** #25 — flat combined forward: three top-level forwards, ordered, each with its own children rows. */
    @Test
    fun `flat forward context assembles all top level forwards in databaseId order`() {
        val corpus = ChildRowCorpus(
            messages = listOf(messageWithContext("A", 8L)),
            forwardContexts = listOf(buildForwardContextModel(databaseId = 8, isFromGroup = true)),
            forwards = listOf(203, 201, 202).map {
                buildForwardModel(databaseId = it, forwardContextDatabaseId = 8L)
            },
            attachments = listOf(203, 201, 202).map {
                buildAttachmentModel(databaseId = 300 + it, forwardModelDatabaseId = it.toLong())
            },
            mentions = listOf(203, 201, 202).map {
                buildMentionModel(databaseId = 400 + it, forwardModelDatabaseId = it.toLong())
            },
        )

        val hydration = hydrate(corpus, FakeMessageChildRowLoader(corpus))
        val context = requireNotNull(hydration["A"].forwardContext)
        val forwards = requireNotNull(context.forwards)

        assertTrue(context.isFromGroup)
        assertEquals(listOf(201L, 202L, 203L), forwards.map { it.id })
        forwards.forEach { forward ->
            assertEquals(listOf("att-${300 + forward.id}"), forward.attachments?.map { it.id })
            assertEquals(listOf("uid-${400 + forward.id}"), forward.mentions?.map { it.uid })
            assertEquals(emptyList<Forward>(), forward.forwards)
        }
    }

    /**
     * #26 — a three-level tree must equal what the naive point-query recursion produces for the
     * same rows. `ChildRowCorpus.referenceSubDataFor` is written independently of the BFS.
     */
    @Test
    fun `nested forward tree equals the naive recursive reference`() {
        val message = messageWithContext("A", 9L)
        val corpus = ChildRowCorpus(
            messages = listOf(message),
            forwardContexts = listOf(buildForwardContextModel(databaseId = 9)),
            forwards = listOf(
                buildForwardModel(databaseId = 501, forwardContextDatabaseId = 9L),
                buildForwardModel(databaseId = 502, parentForwardModelDatabaseId = 501L),
                buildForwardModel(databaseId = 503, parentForwardModelDatabaseId = 502L),
            ),
            attachments = listOf(501, 502, 503).map {
                buildAttachmentModel(databaseId = 600 + it - 500, forwardModelDatabaseId = it.toLong())
            },
        )

        val hydration = hydrate(corpus, FakeMessageChildRowLoader(corpus))

        assertEquals(
            corpus.referenceSubDataFor(message).forwardContext,
            hydration["A"].forwardContext,
        )
    }

    /**
     * #27 — an 18-level chain truncates at [MAX_FORWARD_DEPTH]: the level-16 node reports
     * `forwards = emptyList()` (shape-identical to a real leaf) and exactly one `L.w` is emitted.
     * Without the cap this would be the point-query path's StackOverflowError.
     */
    @Test
    fun `nesting deeper than the cap truncates and warns once`() {
        // mockkStatic, not mockkObject: L's log entry points are @JvmStatic, so Kotlin call sites
        // invoke the static bridge on class L, which instance-level object mocking cannot see.
        mockkStatic(L::class)
        val chainLength = MAX_FORWARD_DEPTH + 2
        val forwards = buildList {
            add(buildForwardModel(databaseId = 1_000, forwardContextDatabaseId = 1L))
            (2..chainLength).forEach { level ->
                add(
                    buildForwardModel(
                        databaseId = 999 + level,
                        parentForwardModelDatabaseId = (998 + level).toLong(),
                    )
                )
            }
        }
        val corpus = ChildRowCorpus(
            messages = listOf(messageWithContext("A", 1L)),
            forwardContexts = listOf(buildForwardContextModel(databaseId = 1)),
            forwards = forwards,
        )

        val hydration = hydrate(corpus, FakeMessageChildRowLoader(corpus))

        var node = requireNotNull(requireNotNull(hydration["A"].forwardContext).forwards).single()
        var level = 1
        while (level < MAX_FORWARD_DEPTH) {
            node = requireNotNull(node.forwards).single()
            level++
        }
        assertEquals(MAX_FORWARD_DEPTH, level)
        assertEquals(
            "the node at the depth cap degrades to a leaf",
            emptyList<Forward>(),
            node.forwards,
        )
        verify(exactly = 1) { L.w(any<() -> String>()) }
    }

    /**
     * #28 — a corrupt parent loop (a node pointing back at an already-visited ancestor) must not
     * spin or overflow. The visited set drops the repeat, so the BFS issues at most
     * [MAX_FORWARD_DEPTH] level queries and the rebuilt tree is finite.
     */
    @Test
    fun `a parent cycle terminates instead of looping`() {
        val corpus = ChildRowCorpus(
            messages = listOf(messageWithContext("A", 1L)),
            forwardContexts = listOf(buildForwardContextModel(databaseId = 1)),
            forwards = listOf(
                buildForwardModel(databaseId = 10, forwardContextDatabaseId = 1L),
                buildForwardModel(databaseId = 20, parentForwardModelDatabaseId = 10L),
                buildForwardModel(databaseId = 30, parentForwardModelDatabaseId = 20L),
                // Corrupt row: re-introduces databaseId 20 as a child of 30, closing 20 -> 30 -> 20.
                buildForwardModel(databaseId = 20, parentForwardModelDatabaseId = 30L),
            ),
        )
        val loader = FakeMessageChildRowLoader(corpus)

        val hydration = hydrate(corpus, loader)

        assertTrue(
            "BFS must not keep re-fetching the cycle",
            loader.callCount("forwardsByParentId") <= MAX_FORWARD_DEPTH,
        )
        var node = requireNotNull(requireNotNull(hydration["A"].forwardContext).forwards).single()
        var depth = 1
        while (node.forwards?.isNotEmpty() == true) {
            node = requireNotNull(node.forwards).first()
            depth++
        }
        assertEquals("10 -> 20 -> 30, then the cycle edge is dropped", 3, depth)
    }

    /**
     * #32 — `ForwardModel.databaseId` is `Int` while `AttachmentModel.forwardModelDatabaseId` is
     * `Long?`. A mismatched key type here loses the whole attachment set silently, with no
     * exception and no log.
     */
    @Test
    fun `forward children attach across the Int primary key to Long foreign key boundary`() {
        val corpus = ChildRowCorpus(
            messages = listOf(messageWithContext("A", 3L)),
            forwardContexts = listOf(buildForwardContextModel(databaseId = 3)),
            forwards = listOf(buildForwardModel(databaseId = 7, forwardContextDatabaseId = 3L)),
            attachments = listOf(buildAttachmentModel(databaseId = 1, forwardModelDatabaseId = 7L)),
            mentions = listOf(buildMentionModel(databaseId = 2, forwardModelDatabaseId = 7L)),
        )

        val hydration = hydrate(corpus, FakeMessageChildRowLoader(corpus))
        val forward = requireNotNull(requireNotNull(hydration["A"].forwardContext).forwards).single()

        assertNotNull(forward.attachments)
        assertEquals(listOf("att-1"), forward.attachments?.map { it.id })
        assertEquals(listOf("uid-2"), forward.mentions?.map { it.uid })
    }
}
