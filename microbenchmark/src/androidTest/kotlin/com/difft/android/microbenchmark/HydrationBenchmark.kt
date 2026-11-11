package com.difft.android.microbenchmark

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.difft.app.database.WCDB
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.hydration.WcdbMessageChildRowLoader
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.builders.plainCorpus
import org.difft.app.database.test.builders.uniformRichCorpus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith














@RunWith(AndroidJUnit4::class)
class HydrationBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

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

    private fun seedAndWindow(corpus: ChildRowCorpus): List<MessageModel> {
        wcdb.seed(corpus)
        return corpus.messages
    }

    





    private fun measureWithPeriodicGc(gcEvery: Int, block: () -> Unit) {
        var iterations = 0
        benchmarkRule.measureRepeated {
            block()
            if (++iterations % gcEvery == 0) {
                runWithTimingDisabled { System.gc() }
            }
        }
    }

    @Test
    fun newPath_hydrate_plain180() {
        val window = seedAndWindow(plainCorpus(WINDOW))
        val hydrator = MessageHydrator(WcdbMessageChildRowLoader(wcdb))
        measureWithPeriodicGc(gcEvery = 100) {
            runBlocking(Dispatchers.IO) { hydrator.hydrate(window) }
        }
    }

    @Test
    fun newPath_hydrate_rich180() {
        val window = seedAndWindow(uniformRichCorpus(WINDOW))
        val hydrator = MessageHydrator(WcdbMessageChildRowLoader(wcdb))
        measureWithPeriodicGc(gcEvery = 100) {
            runBlocking(Dispatchers.IO) { hydrator.hydrate(window) }
        }
    }

    @Test
    fun oldPath_pointQueries_plain180() {
        val window = seedAndWindow(plainCorpus(WINDOW))
        val pointQueries = PointQueryPath(wcdb)
        
        measureWithPeriodicGc(gcEvery = 5) {
            window.forEach { pointQueries.subDataFor(it) }
        }
    }

    @Test
    fun oldPath_pointQueries_rich180() {
        val window = seedAndWindow(uniformRichCorpus(WINDOW))
        val pointQueries = PointQueryPath(wcdb)
        measureWithPeriodicGc(gcEvery = 5) {
            window.forEach { pointQueries.subDataFor(it) }
        }
    }

    private companion object {
        const val WINDOW = 180
    }
}
