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
import org.difft.app.database.test.builders.uniformRichCorpus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith















@RunWith(AndroidJUnit4::class)
class IngestionBenchmark {

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
            { handle -> handle.execute("PRAGMA synchronous=1") },
            Database.ConfigPriority.low,
        )
        
        val mode = wcdb.db.getHandle(true).use { it.getValueFromSQL("PRAGMA synchronous")?.int }
        assertEquals("synchronous=NORMAL pin did not reach the write handle", 1, mode)
    }

    @After
    fun tearDown() {
        wcdb.db.close()
        context.deleteDatabase(WCDB.DATABASE_NAME)
    }

    
    private fun wipeAll(corpus: ChildRowCorpus) {
        wcdb.message.deleteObjects()
        if (corpus.attachments.isNotEmpty()) wcdb.attachment.deleteObjects()
        if (corpus.mentions.isNotEmpty()) wcdb.mention.deleteObjects()
        if (corpus.reactions.isNotEmpty()) wcdb.reaction.deleteObjects()
        if (corpus.sharedContacts.isNotEmpty()) wcdb.sharedContact.deleteObjects()
        if (corpus.sharedContactPhones.isNotEmpty()) wcdb.sharedContactPhone.deleteObjects()
        if (corpus.translates.isNotEmpty()) wcdb.translate.deleteObjects()
        if (corpus.speechToTexts.isNotEmpty()) wcdb.speechToText.deleteObjects()
        if (corpus.quotes.isNotEmpty()) wcdb.quote.deleteObjects()
        if (corpus.forwardContexts.isNotEmpty()) wcdb.forwardContext.deleteObjects()
        if (corpus.forwards.isNotEmpty()) wcdb.forward.deleteObjects()
    }

    
    private fun measureIngestion(
        buildCorpus: () -> ChildRowCorpus,
        gcEvery: Int,
        insert: (ChildRowCorpus) -> Unit,
    ) {
        var iterations = 0
        benchmarkRule.measureRepeated {
            val corpus = runWithTimingDisabled {
                val c = buildCorpus()
                wipeAll(c)
                if (++iterations % gcEvery == 0) System.gc()
                c
            }
            insert(corpus)
        }
    }

    @Test
    fun perRowAutocommit_plain1000() =
        measureIngestion({ plainCorpus(1000) }, gcEvery = 3) { corpus ->
            corpus.messages.forEach { wcdb.message.insertObject(it) }
        }

    @Test
    fun perRowSingleTransaction_plain1000() =
        measureIngestion({ plainCorpus(1000) }, gcEvery = 3) { corpus ->
            wcdb.db.runTransaction {
                corpus.messages.forEach { wcdb.message.insertObject(it) }
                true
            }
        }

    @Test
    fun batchInsertObjects_plain1000() =
        measureIngestion({ plainCorpus(1000) }, gcEvery = 10) { corpus ->
            wcdb.message.insertObjects(corpus.messages)
        }

    @Test
    fun batchInsertObjects_plain5000() =
        measureIngestion({ plainCorpus(5000) }, gcEvery = 5) { corpus ->
            wcdb.message.insertObjects(corpus.messages)
        }

    @Test
    fun batchInsertObjects_rich1000() =
        measureIngestion({ uniformRichCorpus(1000) }, gcEvery = 5) { corpus ->
            
            
            
            wcdb.seed(corpus)
        }
}
