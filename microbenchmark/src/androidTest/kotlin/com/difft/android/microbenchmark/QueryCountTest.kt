package com.difft.android.microbenchmark

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.difft.app.database.WCDB
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.hydration.WcdbMessageChildRowLoader
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.builders.plainCorpus
import org.difft.app.database.test.builders.uniformRichCorpus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The structural fact behind the perf numbers (perf doc item ①): real SELECT statement counts on
 * the real encrypted DB, measured with WCDB's `traceSQL` — not the in-code `queries=` counter,
 * which counts loader calls and never logs in a benchmark process.
 *
 * At these window sizes each loader call is exactly one statement (`IN` chunking starts at 500
 * keys), so old-vs-new per tier is: plain 6/message -> 1080 vs 6 total; uniform rich 24/message
 * -> 4320 vs 14 total. Counts are venue-independent — valid from emulator or device.
 */
@RunWith(AndroidJUnit4::class)
class QueryCountTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wcdb: WCDB

    @Before
    fun setUp() {
        context.deleteDatabase(WCDB.DATABASE_NAME)
        wcdb = WCDB(context, CoroutineScope(SupervisorJob()))
        wcdb.db
    }

    @After
    fun tearDown() {
        wcdb.db.close()
        context.deleteDatabase(WCDB.DATABASE_NAME)
    }

    private fun countSelects(block: () -> Unit): Int {
        val selects = AtomicInteger(0)
        wcdb.db.traceSQL { _, _, _, sql, _ ->
            if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) selects.incrementAndGet()
        }
        try {
            block()
        } finally {
            wcdb.db.traceSQL(null)
        }
        return selects.get()
    }

    private fun oldPathSelects(corpus: ChildRowCorpus): Int {
        wcdb.seed(corpus)
        val pointQueries = PointQueryPath(wcdb)
        return countSelects { corpus.messages.forEach { pointQueries.subDataFor(it) } }
    }

    private fun newPathSelects(corpus: ChildRowCorpus): Int {
        wcdb.seed(corpus)
        val hydrator = MessageHydrator(WcdbMessageChildRowLoader(wcdb))
        return countSelects {
            runBlocking(Dispatchers.IO) { hydrator.hydrate(corpus.messages) }
        }
    }

    @Test
    fun oldPath_plain180_issues6SelectsPerMessage() {
        assertEquals(6 * WINDOW, oldPathSelects(plainCorpus(WINDOW)))
    }

    @Test
    fun oldPath_rich180_issues24SelectsPerMessage() {
        assertEquals(24 * WINDOW, oldPathSelects(uniformRichCorpus(WINDOW)))
    }

    @Test
    fun newPath_plain180_issues6SelectsTotal() {
        assertEquals(6, newPathSelects(plainCorpus(WINDOW)))
    }

    @Test
    fun newPath_rich180_issues14SelectsTotal() {
        assertEquals(14, newPathSelects(uniformRichCorpus(WINDOW)))
    }

    private companion object {
        const val WINDOW = 180
    }
}
