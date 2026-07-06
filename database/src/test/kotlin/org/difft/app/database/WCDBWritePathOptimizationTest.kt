package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.messageserialization.db.store.TestWcdbFactory
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.TextMessage
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.ReactionModel
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #971 write-path optimization — integration tests against a REAL in-memory WCDB.
 *
 * Covers the two production changes:
 *  - Lever ②: [WCDB.putMessageIfNotExists] now wraps the single-message cascade in one
 *    `runTransaction`. T3 pins partial-cascade rollback (atomicity), T4 pins normal commit
 *    + idempotency.
 *  - Lever ①: [WCDB.verifySynchronousApplied] reads `PRAGMA synchronous` back through the
 *    write handle. T1 pins the confirmed path + that the setConfig actually reaches the
 *    write handle (== 1, NORMAL).
 *
 * **Currently @Ignore-d** — same constraint documented on [WCDBPagedMessageAccessTest] /
 * [QuoteAttachmentRoundTripTest]: WCDB (Tencent SQLite wrapper) loads native libraries via
 * `System.loadLibrary`, which are not available to host JVM unit tests. [insertChildrenAndBuildMessageModel]
 * additionally reads the `globalServices` global (myId / gson) which needs a Hilt-wired app.
 * The cases remain as compilation guards + documented expected behavior; they must run under
 * instrumentation (`:database:connectedDebugAndroidTest`) to execute.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native library not loadable in JVM unit tests; insertChildrenAndBuildMessageModel binds the globalServices global. Run via instrumentation test instead.")
class WCDBWritePathOptimizationTest {

    private lateinit var wcdb: WCDB

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        wcdb = TestWcdbFactory.createInMemoryWcdb(ctx)
    }

    private fun textMessage(
        id: String,
        timeStamp: Long,
        reactions: List<Reaction>? = null,
    ) = TextMessage(
        id = id,
        fromWho = For.Account("sender"),
        forWhat = For.Account("room"),
        systemShowTimestamp = timeStamp,
        timeStamp = timeStamp,
        receivedTimeStamp = timeStamp,
        sendType = 0,
        expiresInSeconds = 0,
        notifySequenceId = 0L,
        sequenceId = 0L,
        mode = 0,
        text = "body",
    ).apply { this.reactions = reactions }

    // ------------------------------------------------------------------
    // Lever ② — single-message cascade transaction
    // ------------------------------------------------------------------

    // T3 — partial-cascade rollback: a child insert that fails mid-cascade must roll back the
    //      WHOLE single-message cascade, leaving NO half message.
    //      Trigger: ReactionModel has multiUnique(messageId, emoji, uid). Pre-insert a reaction
    //      row keyed (msgId, "👍", "u1"); then putMessageIfNotExists for a message with id=msgId
    //      whose own message row does NOT yet exist (existence check passes) but whose cascade
    //      re-inserts the same reaction → UNIQUE conflict mid-cascade → runTransaction rolls back.
    //      Without the transaction the message row would have been inserted after the (now-failed)
    //      reaction insert path, leaving an orphaned/half state.
    @Test
    fun `putMessageIfNotExists rolls back whole cascade on mid-cascade unique conflict`() {
        val msgId = "t3-conflict"
        // Pre-seed the conflicting reaction row (no message row for msgId yet).
        wcdb.reaction.insertObject(ReactionModel().apply {
            messageId = msgId; emoji = "👍"; uid = "u1"; timeStamp = 1_000L
        })
        assertEquals(1, wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).size)
        assertNull(wcdb.message.getFirstObject(DBMessageModel.id.eq(msgId))) // no message row yet

        val msg = textMessage(
            msgId, 1_000L,
            reactions = listOf(Reaction(emoji = "👍", uid = "u1", originTimestamp = 1_000L)),
        )

        var threw = false
        try {
            wcdb.putMessageIfNotExists(msg)
        } catch (e: Exception) {
            threw = true // expected: UNIQUE conflict propagates out of runTransaction (rollback + rethrow)
        }
        assertTrue(threw, "the mid-cascade UNIQUE conflict must propagate (rollback + rethrow)")

        // Atomicity: message row never landed; reaction count unchanged (no duplicate from the rolled-back cascade).
        assertNull(wcdb.message.getFirstObject(DBMessageModel.id.eq(msgId)))
        assertEquals(1, wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).size)
    }

    // T4 — normal commit + idempotency: a clean message commits with its child rows; a second
    //      call with the same id is a no-op (existence check hits, no duplicate insert).
    @Test
    fun `putMessageIfNotExists commits then is idempotent on repeat`() {
        val msgId = "t4-ok"
        val msg = textMessage(
            msgId, 2_000L,
            reactions = listOf(Reaction(emoji = "🎉", uid = "u2", originTimestamp = 2_000L)),
        )

        wcdb.putMessageIfNotExists(msg)

        assertNotNull(wcdb.message.getFirstObject(DBMessageModel.id.eq(msgId)))
        assertEquals(1, wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).size)

        // Second call: existence check hits → no re-insert, no duplicate reaction.
        wcdb.putMessageIfNotExists(msg)
        assertEquals(1L, wcdb.message.getAllObjects(DBMessageModel.id.eq(msgId)).size.toLong())
        assertEquals(1, wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).size)
    }

    // ------------------------------------------------------------------
    // Lever ① — synchronous=NORMAL setConfig reaches the write handle
    // ------------------------------------------------------------------

    // T1 — verifySynchronousApplied confirms NORMAL on the write handle. The setConfig in the
    //      WCDB lazy block runs `PRAGMA synchronous=1` on every handle; reading it back through
    //      getHandle(true) must yield 1 (NORMAL). Also pins the one-shot guard (second call is
    //      a no-op — no exception).
    @Test
    fun `verifySynchronousApplied reads NORMAL back on the write handle`() {
        // Force lazy open.
        wcdb.message.getAllObjects(DBMessageModel.id.eq("none"))

        val readBack = wcdb.db.getHandle(true).use {
            it.getValueFromSQL("PRAGMA synchronous")?.int
        }
        assertEquals(1, readBack, "setConfig must put synchronous=NORMAL(1) on the write handle")

        // The production helper logs the confirmed branch and is a one-shot (must not throw on
        // repeat). We can only assert it does not throw here; the log assertion is manual on device.
        wcdb.verifySynchronousApplied()
        wcdb.verifySynchronousApplied() // idempotent — second call short-circuits via synchronousVerified
    }
}
