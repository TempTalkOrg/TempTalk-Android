package org.difft.app.database.hydration

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.runBlocking
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The per-loader breakdown on the hydrator's exit line.
 *
 * The point of the breakdown is to split hydration cost into fixed and per-row parts, which a single
 * total cannot show. It rides on the ONE existing summary line — the assertions below pin both the
 * breakdown's shape (loader, key count, elapsed ms) and the fact that it did not become a second
 * line per emission.
 */
class MessageHydratorLoaderTimingTest {

    @Test
    fun `the single summary line carries a per-loader key count and cost`() {
        val corpus = ChildRowCorpus.rich()

        val summary = summaryLineFor(corpus, corpus.messages)

        assertTrue(summary, summary.contains("msgs=${corpus.messages.size} queries="))
        val breakdown = breakdownOf(summary)
        // Key counts of the loaders whose key set is fully determined by the corpus: the six
        // message-keyed loaders take every message id, phones take the shared-contact rows those
        // messages produced, and quote / forward-context loaders take the distinct child ids.
        val expectedKeys = mapOf(
            "att" to corpus.messages.size,
            "mention" to corpus.messages.size,
            "reaction" to corpus.messages.size,
            "translate" to corpus.messages.size,
            "stt" to corpus.messages.size,
            "contact" to corpus.messages.size,
            "phone" to 4,
            "quote" to 3,
            "quoteAtt" to 3,
            "fwdCtx" to 3,
            "fwdTop" to 3,
        )
        expectedKeys.forEach { (loader, keys) ->
            assertEquals("$loader keys in $summary", keys, breakdown[loader]?.keys)
        }
        // The nested-forward loaders run per BFS level, so their key totals are not a fixed number —
        // what matters is that the levels were accounted for at all.
        listOf("fwdChild", "fwdAtt", "fwdMention").forEach { loader ->
            assertTrue("$loader missing from $summary", breakdown.containsKey(loader))
        }
    }

    @Test
    fun `loaders that never ran are absent from the breakdown`() {
        val messages = (0 until 3).map { buildMessageModel("m$it", 1_000L + it) }

        val summary = summaryLineFor(ChildRowCorpus(), messages)

        // Only the six message-keyed loaders have a non-empty key set here; every other loader is
        // keyed off child rows that do not exist, and an empty key set issues no query.
        assertEquals(
            setOf("att", "mention", "reaction", "translate", "stt", "contact"),
            breakdownOf(summary).keys,
        )
        assertTrue(summary, summary.contains("queries=6 "))
    }

    // --- helpers ---

    private class LoaderCost(val keys: Int, val costMs: Long)

    /** Parses `detail=att:12/0ms,quote:3/0ms` back into a map, asserting each entry's shape. */
    private fun breakdownOf(summary: String): Map<String, LoaderCost> {
        val detail = summary.substringAfter("detail=", missingDelimiterValue = "")
        assertTrue("no detail= in $summary", detail.isNotEmpty())
        return detail.split(",").associate { entry ->
            val match = ENTRY.matchEntire(entry) ?: run {
                fail("malformed breakdown entry '$entry' in $summary")
                error("unreachable")
            }
            val (loader, keys, costMs) = match.destructured
            loader to LoaderCost(keys.toInt(), costMs.toLong())
        }
    }

    /**
     * Hydrates and returns the exit line.
     *
     * `L.i` is `@JvmStatic` and delivers over L's own single-thread channel, so the only way to see
     * the line is to plant a real tree and wait for it — the same technique the chat suites use.
     */
    private fun summaryLineFor(corpus: ChildRowCorpus, messages: List<MessageModel>): String {
        val captured = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                captured += message
            }
        }
        L.plant(tree)
        try {
            runBlocking { MessageHydrator(FakeMessageChildRowLoader(corpus)).hydrate(messages) }
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val lines = captured.filter { it.startsWith(SUMMARY_PREFIX) }
                if (lines.isNotEmpty()) {
                    assertEquals("one summary line per hydration; captured=$captured", 1, lines.size)
                    return lines.first()
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            fail("no hydrator summary line within ${TIMEOUT_MS}ms; captured=$captured")
            error("unreachable")
        } finally {
            L.uproot(tree)
        }
    }

    private companion object {
        const val SUMMARY_PREFIX = "[MessageHydrator] msgs="
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 10L
        val ENTRY = Regex("""([A-Za-z]+):(\d+)/(\d+)ms""")
    }
}
