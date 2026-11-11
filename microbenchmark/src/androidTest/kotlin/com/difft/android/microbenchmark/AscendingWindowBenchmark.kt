package com.difft.android.microbenchmark

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.wcdb.winq.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.DEFAULT_ROOM_ID
import org.difft.app.database.test.builders.plainCorpus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith












@RunWith(AndroidJUnit4::class)
class AscendingWindowBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wcdb: WCDB

    
    private val roomCondition =
        DBMessageModel.roomType.eq(0).and(DBMessageModel.roomId.eq(DEFAULT_ROOM_ID))

    @Before
    fun setUp() {
        context.deleteDatabase(WCDB.DATABASE_NAME)
        wcdb = WCDB(context, CoroutineScope(SupervisorJob()))
        wcdb.db
        wcdb.seed(plainCorpus(SEEDED))
    }

    @After
    fun tearDown() {
        wcdb.db.close()
        context.deleteDatabase(WCDB.DATABASE_NAME)
    }

    
    private fun fromTsFor(rows: Int): Long = START_TS + (SEEDED - rows) * STEP_MS

    private fun ascendingFrom(fromTs: Long, toTs: Long?): List<MessageModel> {
        val condition = if (toTs == null) {
            roomCondition.and(DBMessageModel.systemShowTimestamp.ge(fromTs))
        } else {
            roomCondition.and(DBMessageModel.systemShowTimestamp.between(fromTs, toTs))
        }
        return wcdb.message.getAllObjects(
            condition,
            DBMessageModel.systemShowTimestamp.order(Order.Asc),
        )
    }

    private fun measureCase(rows: Int, bounded: Boolean) {
        val fromTs = fromTsFor(rows)
        val toTs = if (bounded) START_TS + (SEEDED - 1) * STEP_MS else null
        assertEquals(rows, ascendingFrom(fromTs, toTs).size)
        benchmarkRule.measureRepeated {
            ascendingFrom(fromTs, toTs)
        }
    }

    @Test
    fun absorbing_ge_180rows() = measureCase(rows = 180, bounded = false)

    @Test
    fun absorbing_ge_270rows() = measureCase(rows = 270, bounded = false)

    @Test
    fun frozen_between_180rows() = measureCase(rows = 180, bounded = true)

    @Test
    fun frozen_between_270rows() = measureCase(rows = 270, bounded = true)

    private companion object {
        
        const val SEEDED = 400
        const val START_TS = 1_000L
        const val STEP_MS = 1_000L
    }
}
