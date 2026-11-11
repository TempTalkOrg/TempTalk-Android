package com.difft.android.microbenchmark

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.difft.app.database.WCDB
import org.difft.app.database.PendingRowKey
import org.difft.app.database.WcdbPendingMessageStore
import org.difft.app.database.models.PendingMessageModelNew
import org.difft.app.database.test.builders.buildMessageModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith














@RunWith(AndroidJUnit4::class)
class PendingMessageStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wcdb: WCDB

    private val target = 1_700_000_000_000L
    private val otherTarget = target + 1
    private val iosId = "ios:+10001:2:1700000000500:$target"
    private val androidId = "+10002:1700000000600:1"
    private val otherId = "+10003:1700000000700:1"

    @Before
    fun setUp() {
        context.deleteDatabase(WCDB.DATABASE_NAME)
        wcdb = open()
    }

    @After
    fun tearDown() {
        
        
        wcdb.db.close()
        context.deleteDatabase(WCDB.DATABASE_NAME)
    }

    private fun open() = WCDB(context, CoroutineScope(SupervisorJob()))

    private fun row(id: String, targetTs: Long) = PendingMessageModelNew().apply {
        messageId = id
        originalMessageTimeStamp = targetTs
        messageEnvelopBytes = id.toByteArray()
    }

    private fun seed() {
        wcdb.pendingMessageNew.insertOrReplaceObjects(
            listOf(row(iosId, target), row(androidId, target), row(otherId, otherTarget))
        )
    }

    private fun storedIds(db: WCDB): Set<String?> = WcdbPendingMessageStore(db).loadAll().map { it.messageId }.toSet()

    @Test
    fun consumingOneRowKeepsItsSiblingOnTheSameTargetAcrossAReopen() {
        seed()

        
        WcdbPendingMessageStore(wcdb).deleteConsumed(listOf(PendingRowKey(iosId, target)))
        assertEquals(setOf(androidId, otherId), storedIds(wcdb))

        wcdb.db.close()
        wcdb = open()
        val reopened = WcdbPendingMessageStore(wcdb)
        assertEquals(setOf(androidId, otherId), storedIds(wcdb))
        assertEquals(2, reopened.count())

        
        reopened.deleteConsumed(listOf(PendingRowKey(androidId, target)))
        assertEquals(setOf(otherId), storedIds(wcdb))
    }

    @Test
    fun aPermanentFailureDeletesOnlyItsOwnRow() {
        seed()

        WcdbPendingMessageStore(wcdb).deleteConsumed(listOf(PendingRowKey(androidId, target)))

        assertEquals(setOf(iosId, otherId), storedIds(wcdb))
    }

    @Test
    fun aRowReSavedForAnotherTargetSurvivesTheConsumeOfItsLoadedTarget() {
        seed()
        
        val reSavedTarget = otherTarget + 1
        wcdb.pendingMessageNew.insertOrReplaceObject(row(iosId, reSavedTarget))

        WcdbPendingMessageStore(wcdb).deleteConsumed(listOf(PendingRowKey(iosId, target)))

        val survivor = WcdbPendingMessageStore(wcdb).loadAll().single { it.messageId == iosId }
        assertEquals(reSavedTarget, survivor.originalMessageTimeStamp)
        assertEquals(setOf(iosId, androidId, otherId), storedIds(wcdb))
    }

    @Test
    fun theExpirySweepDeletesEveryRowOnTheExpiredTarget() {
        seed()

        WcdbPendingMessageStore(wcdb).deleteByTargetTimestamps(listOf(target))

        assertEquals(setOf(otherId), storedIds(wcdb))
    }

    @Test
    fun deletesByIdBeyondOneInClauseChunk() {
        val rows = (0 until 1_200).map { row("ios:+1:1:$it:$target", target) }
        wcdb.pendingMessageNew.insertOrReplaceObjects(rows)
        wcdb.pendingMessageNew.insertOrReplaceObject(row(androidId, target))

        WcdbPendingMessageStore(wcdb).deleteConsumed(rows.map { PendingRowKey(requireNotNull(it.messageId), target) })

        assertEquals(setOf(androidId), storedIds(wcdb))
    }

    @Test
    fun onlyStoredTargetsAreReported() {
        wcdb.message.insertObject(buildMessageModel(id = "target", systemShowTimestamp = target))

        val existing = WcdbPendingMessageStore(wcdb).existingTargetTimestamps(listOf(target, otherTarget))

        assertEquals(setOf(target), existing)
    }
}
