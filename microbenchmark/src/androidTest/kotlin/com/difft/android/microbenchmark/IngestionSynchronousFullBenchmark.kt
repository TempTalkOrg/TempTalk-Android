package com.difft.android.microbenchmark

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.wcdb.core.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.difft.app.database.WCDB
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.builders.plainCorpus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deferred #971 justification: the same fsync-sensitive ingestion cases as
 * [IngestionBenchmark], but with `PRAGMA synchronous=FULL` — the pre-#971 mode. The
 * NORMAL/FULL ratio of the matching cases is the measured value of that fix.
 *
 * The override is test-side only: a low-priority per-handle config registered after the
 * production NORMAL config (config priorities run highest→lowest, so this executes last on
 * every handle). Production code is untouched; [setUp] read-back-asserts FULL (SQLite: 2)
 * actually applied to the write handle, mirroring WCDB.verifySynchronousApplied's caveat
 * that a PRAGMA can report without taking effect on the handle that matters.
 */
@RunWith(AndroidJUnit4::class)
class IngestionSynchronousFullBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wcdb: WCDB

    @Before
    fun setUp() {
        context.deleteDatabase(WCDB.DATABASE_NAME)
        wcdb = WCDB(context, CoroutineScope(SupervisorJob()))
        // Same config name as IngestionBenchmark's NORMAL pin: WCDB's config registry is
        // per-path process-global, so reusing the name REPLACES a leaked sibling pin
        // instead of racing it.
        wcdb.db.setConfig(
            "bench_synchronous_pin",
            { handle -> handle.execute("PRAGMA synchronous=2") },
            Database.ConfigPriority.low,
        )
        val mode = wcdb.db.getHandle(true).use { it.getValueFromSQL("PRAGMA synchronous")?.int }
        assertEquals("benchmark FULL override did not reach the write handle", 2, mode)
    }

    @After
    fun tearDown() {
        // The pin lives in WCDB's process-global per-path registry, so it would outlive this
        // class and leak FULL into any later test that opens the same path without its own
        // pin. Re-register the same name back to production's NORMAL before leaving.
        wcdb.db.setConfig(
            "bench_synchronous_pin",
            { handle -> handle.execute("PRAGMA synchronous=1") },
            Database.ConfigPriority.low,
        )
        wcdb.db.close()
        context.deleteDatabase(WCDB.DATABASE_NAME)
    }

    private fun measureIngestion(gcEvery: Int, insert: (ChildRowCorpus) -> Unit) {
        var iterations = 0
        benchmarkRule.measureRepeated {
            val corpus = runWithTimingDisabled {
                val c = plainCorpus(1000)
                wcdb.message.deleteObjects()
                if (++iterations % gcEvery == 0) System.gc()
                c
            }
            insert(corpus)
        }
    }

    @Test
    fun perRowAutocommit_plain1000_syncFull() =
        measureIngestion(gcEvery = 3) { corpus ->
            corpus.messages.forEach { wcdb.message.insertObject(it) }
        }

    @Test
    fun batchInsertObjects_plain1000_syncFull() =
        measureIngestion(gcEvery = 10) { corpus ->
            wcdb.message.insertObjects(corpus.messages)
        }
}
