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









class MessageHydratorLoaderTimingTest {

    @Test
    fun `the single summary line carries a per-loader key count and cost`() {
        val corpus = ChildRowCorpus.rich()

        val summary = summaryLineFor(corpus, corpus.messages)

        assertTrue(summary, summary.contains("msgs=${corpus.messages.size} queries="))
        val breakdown = breakdownOf(summary)
        
        
        
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
        
        
        listOf("fwdChild", "fwdAtt", "fwdMention").forEach { loader ->
            assertTrue("$loader missing from $summary", breakdown.containsKey(loader))
        }
    }

    @Test
    fun `loaders that never ran are absent from the breakdown`() {
        val messages = (0 until 3).map { buildMessageModel("m$it", 1_000L + it) }

        val summary = summaryLineFor(ChildRowCorpus(), messages)

        
        
        assertEquals(
            setOf("att", "mention", "reaction", "translate", "stt", "contact"),
            breakdownOf(summary).keys,
        )
        assertTrue(summary, summary.contains("queries=6 "))
    }

    

    private class LoaderCost(val keys: Int, val costMs: Long)

    
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
