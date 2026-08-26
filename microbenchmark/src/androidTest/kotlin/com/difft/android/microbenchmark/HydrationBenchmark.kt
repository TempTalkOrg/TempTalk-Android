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

/**
 * Old-vs-new message sub-data loading over the real encrypted WCDB (issue #1162, perf doc item #1).
 *
 * Old path = [PointQueryPath], the pre-#1143 per-row point queries (6 SELECTs/message plain,
 * 24/message rich). New path = production [MessageHydrator] + [WcdbMessageChildRowLoader]
 * (6 batched SELECTs per window plain, 14 rich, at <=500 keys per IN chunk).
 *
 * Measurement notes: `hydrate` is driven by `runBlocking(Dispatchers.IO)` so the inner
 * same-dispatcher `withContext` adds no thread hop; the production `L.i` exit line inside the
 * hydrator allocates a Throwable per call even with no log tree planted, a single-digit-us bias
 * IN FAVOR of the old path. Emulator runs carry an `EMULATOR_` metric prefix — per-tier old/new
 * RATIOS are venue-valid, absolute times are trustworthy only from a real device.
 */
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
        // Absorb the heavy first open (cipher/PBKDF + header I/O) outside the measured region.
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

    /**
     * measureRepeated with a periodic untimed GC. WCDB's winq/handle wrappers are small Java
     * objects backed by native allocations that only a collection releases; a tight benchmark
     * loop outruns the cleaner and dies with a Scudo native OOM (observed at ~1 minute uptime).
     * Production never sees this allocation rate — emission is throttled to 2/s.
     */
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
        // The old path allocates ~180x more native wrappers per iteration — collect often.
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
