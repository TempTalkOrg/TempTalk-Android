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
