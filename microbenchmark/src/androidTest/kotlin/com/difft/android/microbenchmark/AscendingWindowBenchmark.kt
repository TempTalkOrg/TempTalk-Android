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

/**
 * Full-window re-query cost at the pagination window tiers (issue #1162; the controlled-environment
 * companion of the PR #1148 P1 probe `observer window re-query rows=.. cost=..ms`).
 *
 * The query is re-expressed over `:database`'s public API rather than calling
 * `WcdbChatMessageWindowSource.ascendingFrom` — that class is `internal` to `:chat` and its own
 * KDoc certifies each expression as a verbatim, byte-identical migration, so this copy is faithful
 * by construction (drift risk noted in the PR). Both production branches are measured: the
 * absorbing branch issues `systemShowTimestamp >= fromTs` (upper bound null), the frozen branch
 * `BETWEEN fromTs AND toTs` — at both window tiers (180 = trim floor, 270 = trim ceiling).
 */
@RunWith(AndroidJUnit4::class)
class AscendingWindowBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wcdb: WCDB

    // Mirrors WcdbChatMessageWindowSource.roomCondition for a 1:1 room (For.Account typeValue 0).
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

    /** buildMessageSequence stamps message i with timestamp START_TS + i * STEP_MS. */
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
        // Window tiers sit inside a larger room so the predicate really filters.
        const val SEEDED = 400
        const val START_TS = 1_000L
        const val STEP_MS = 1_000L
    }
}
