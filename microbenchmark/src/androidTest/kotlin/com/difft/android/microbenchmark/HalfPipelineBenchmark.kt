package com.difft.android.microbenchmark

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.difft.android.websocket.api.util.removePadding
import com.tencent.wcdb.core.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.whispersystems.signalservice.internal.push.SignalServiceProtos














@RunWith(AndroidJUnit4::class)
class HalfPipelineBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wcdb: WCDB
    private lateinit var fixture: PipelineFixture

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
        fixture = PipelineFixture.create(BACKLOG)
    }

    @After
    fun tearDown() {
        wcdb.db.close()
        context.deleteDatabase(WCDB.DATABASE_NAME)
    }

    @Test
    fun halfPipeline_parseDecryptConvertPersist_1000() {
        var iterations = 0
        benchmarkRule.measureRepeated {
            runWithTimingDisabled {
                wcdb.message.deleteObjects()
                if (++iterations % 5 == 0) System.gc()
            }
            val models = fixture.envelopeBytes.map { bytes ->
                val envelope = SignalServiceProtos.Envelope.parseFrom(bytes)
                val plaintext = fixture.decryptOne(envelope).removePadding()
                val content = SignalServiceProtos.Content.parseFrom(plaintext)
                toMessageModel(envelope, content)
            }
            wcdb.message.insertObjects(models)
        }
    }

    private companion object {
        const val BACKLOG = 1000
    }
}
