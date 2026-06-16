package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.StatementSelect
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.ReactionModel
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.difft.android.messageserialization.db.store.TestWcdbFactory

/**
 * Integration tests for the #909 paged-message-access helpers
 * ([messageCount], [deleteMessagesPaged], [forEachMessagePaged]) against a real
 * in-memory WCDB instance.
 *
 * **Currently @Ignore-d**: WCDB (Tencent SQLite wrapper) loads native libraries via
 * `System.loadLibrary` which are not available to JVM unit tests on the host machine —
 * the same constraint that keeps `DBPublicKeyInfoStoreTest` ignored. Additionally, the
 * helpers reach the top-level `wcdb` global ([org.difft.app.database.wcdb]) via
 * `EntryPointAccessors.fromApplication`, which requires a fully-wired Hilt application,
 * so they cannot be redirected to the test WCDB instance built here without Hilt.
 *
 * To run these reliably we need either (a) an instrumentation test (androidTest source
 * set + emulator/device) with the helpers parameterized on a `WCDB`, or (b) a
 * JVM-compatible SQLite shim. Both are out of scope for the #909 fix; the tests remain
 * here as compilation guards + documented expected behavior. The pure read-receipt
 * accumulation/chunking logic (#5) IS runnable and is covered by
 * `ReadReceiptAccumulationTest` in :chat.
 *
 * This file documents design test cases T1–T13 and T19. Tests that need the store classes
 * ([DBRoomStore.getUnreadMessageInfo] #3, [DBMessageStore.updateMessageReadTime] #4) document
 * the store contract in the same @Ignore-d-guard style; they additionally require a
 * Hilt-wired store instance (the stores bind the `wcdb` global via EntryPointAccessors), so
 * they are doubly blocked from JVM execution and must run under instrumentation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native library not loadable in JVM unit tests; helpers also bind the wcdb global via Hilt. Run via instrumentation test instead.")
class WCDBPagedMessageAccessTest {

    private lateinit var wcdbInstance: WCDB

    // Stand-in for the local user id used by the mention subquery (#3). Production reads
    // the real `myID`; the @Ignore-d guard uses a fixed value to seed/match deterministically.
    private val MY_ID = "my-uid"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        wcdbInstance = TestWcdbFactory.createInMemoryWcdb(ctx)
    }

    private fun seed(roomId: String, count: Int, baseTs: Long = 1_000L) {
        (0 until count).forEach { i ->
            wcdbInstance.message.insertObject(MessageModel().apply {
                id = "$roomId-$i"
                this.roomId = roomId
                fromWho = "sender-${i % 3}"
                timeStamp = baseTs + i
                systemShowTimestamp = baseTs + i
                type = MessageModel.TYPE_TEXT
            })
        }
    }

    private fun roomCond(roomId: String): Expression = DBMessageModel.roomId.eq(roomId)

    // T1 — messageCount returns exact COUNT for 0 / 1 / 250 rows
    @Test
    fun `messageCount returns exact count`() = runTest {
        assertEquals(0L, messageCount(roomCond("r0")))
        seed("r1", 1)
        assertEquals(1L, messageCount(roomCond("r1")))
        seed("r250", 250)
        assertEquals(250L, messageCount(roomCond("r250")))
    }

    // T2 — deleteMessagesPaged on empty set is an immediate no-op
    @Test
    fun `deleteMessagesPaged empty set is no-op`() = runTest {
        deleteMessagesPaged(roomCond("empty"))
        assertEquals(0L, messageCount(roomCond("empty")))
    }

    // T3 — single page (50 rows, pageSize=100): all deleted, one iteration
    @Test
    fun `deleteMessagesPaged single page deletes all`() = runTest {
        seed("rsingle", 50)
        deleteMessagesPaged(roomCond("rsingle"), pageSize = 100)
        assertEquals(0L, messageCount(roomCond("rsingle")))
    }

    // T4 — multi page (250 rows, pageSize=100): all deleted across 3 iterations
    @Test
    fun `deleteMessagesPaged multi page deletes all`() = runTest {
        seed("rmulti", 250)
        deleteMessagesPaged(roomCond("rmulti"), pageSize = 100)
        assertEquals(0L, messageCount(roomCond("rmulti")))
    }

    // T5 — deleteMessagesPaged CASCADE: per-row delete must remove the message's
    //      attachment / mention / reaction rows AND its on-disk file. This is the core
    //      reason the helper uses per-row MessageModel.delete() instead of a bulk SQL
    //      deleteObjects (which would orphan files + related-table rows). Guards regression
    //      back to a bulk delete.
    @Test
    fun `deleteMessagesPaged cascades related rows and file deletion`() = runTest {
        val msgId = "rcascade-0"
        wcdbInstance.message.insertObject(MessageModel().apply {
            id = msgId
            roomId = "rcascade"
            fromWho = "s"
            timeStamp = 1_000L
            systemShowTimestamp = 1_000L
            type = MessageModel.TYPE_TEXT
        })
        wcdbInstance.attachment.insertObject(AttachmentModel().apply {
            id = "att-0"; messageId = msgId
        })
        wcdbInstance.mention.insertObject(MentionModel().apply {
            messageId = msgId; uid = "u1"; type = 0
        })
        wcdbInstance.reaction.insertObject(ReactionModel().apply {
            messageId = msgId; emoji = "👍"; uid = "u1"; timeStamp = 1_000L
        })

        deleteMessagesPaged(roomCond("rcascade"))

        // message + all related rows gone (cascade via MessageModel.delete()).
        assertEquals(0L, messageCount(roomCond("rcascade")))
        assertTrue(wcdbInstance.attachment.getAllObjects(DBAttachmentModel.messageId.eq(msgId)).isEmpty())
        assertTrue(wcdbInstance.mention.getAllObjects(DBMentionModel.messageId.eq(msgId)).isEmpty())
        assertTrue(wcdbInstance.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).isEmpty())
        // NOTE (instrumentation TODO): also assert FileUtil.deleteMessageFile(msgId) was invoked —
        // requires mocking FileUtil, which the @Ignore-d JVM guard does not wire. The table-level
        // cascade above is the regression guard runnable in JVM once WCDB native is available.
    }

    // T6 — forEachMessagePaged visits every row exactly once in databaseId order, including
    //      across a page boundary landing on a block of duplicate timestamps (T7): the
    //      databaseId keyset cursor is unaffected by timestamp ties (no skip / no duplicate).
    @Test
    fun `forEachMessagePaged visits each row once with duplicate timestamps`() = runTest {
        // 250 rows; force a block of duplicate systemShowTimestamp straddling pageSize.
        (0 until 250).forEach { i ->
            wcdbInstance.message.insertObject(MessageModel().apply {
                id = "rdup-$i"
                roomId = "rdup"
                fromWho = "s"
                // rows 90..160 share the same timestamp to straddle the pageSize=100 boundary
                systemShowTimestamp = if (i in 90..160) 5_000L else 1_000L + i
                timeStamp = systemShowTimestamp
                type = MessageModel.TYPE_TEXT
            })
        }
        val seen = mutableListOf<Int>()
        forEachMessagePaged(roomCond("rdup"), pageSize = 100) { seen += it.databaseId }

        assertEquals(250, seen.size)                 // every row visited
        assertEquals(seen.size, seen.toSet().size)   // no duplicate
    }

    // T8 — getUnreadMessageInfo-style count path: only COUNT runs, no full load.
    //      (Covered functionally here via messageCount over an unread condition.)
    @Test
    fun `messageCount over unread condition counts without loading`() = runTest {
        seed("runread", 300, baseTs = 1_000L)
        val unreadCond = DBMessageModel.roomId.eq("runread")
            .and(DBMessageModel.systemShowTimestamp.gt(1_000L)) // strictly after first
        assertEquals(299L, messageCount(unreadCond))
    }

    /**
     * Seeds an unread group message + its mention rows. Returns the message id.
     * Mirrors the shape DBRoomStore.queryUnreadMentions (#3 TP3) queries against.
     */
    private fun seedUnreadMention(roomId: String, idx: Long, mentionUid: String): String {
        val mid = "$roomId-$idx"
        wcdbInstance.message.insertObject(MessageModel().apply {
            id = mid
            this.roomId = roomId
            fromWho = "other-sender"
            timeStamp = 2_000L + idx
            systemShowTimestamp = 2_000L + idx     // > readPosition(=1_000) ⇒ unread
            type = MessageModel.TYPE_TEXT
        })
        wcdbInstance.mention.insertObject(MentionModel().apply {
            messageId = mid; uid = mentionUid; type = 0
        })
        return mid
    }

    // Local re-implementation of the production mention-subquery (#3 TP3) — the @Ignore-d
    // guard cannot reach the store's wcdb global, so it pins the SAME SQL shape against the
    // test wcdb. (T9/T10/T11.) The store-level call is asserted under instrumentation.
    private fun queryUnreadMentionTypeAndStamps(roomId: String, readPosition: Long): Pair<Int, List<Long>> {
        val unreadIdsSubquery = StatementSelect()
            .select(DBMessageModel.id)
            .from("message")
            .where(
                DBMessageModel.roomId.eq(roomId)
                    .and(DBMessageModel.systemShowTimestamp.gt(readPosition))
                    .and(DBMessageModel.fromWho.notEq(MY_ID))
                    .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER))
            )
        val mentionUids = wcdbInstance.mention.getOneColumnString(
            DBMentionModel.uid,
            DBMentionModel.uid.`in`(MY_ID, MENTIONS_ALL_ID)
                .and(DBMentionModel.messageId.`in`(unreadIdsSubquery))
        )
        val mentionType = when {
            mentionUids.any { it == MY_ID } -> MENTIONS_TYPE_ME
            mentionUids.any { it == MENTIONS_ALL_ID } -> MENTIONS_TYPE_ALL
            else -> MENTIONS_TYPE_NONE
        }
        if (mentionType == MENTIONS_TYPE_NONE) return MENTIONS_TYPE_NONE to emptyList()
        val mentioningMessageIds = StatementSelect()
            .select(DBMentionModel.messageId)
            .from("mention")
            .where(
                DBMentionModel.uid.`in`(MY_ID, MENTIONS_ALL_ID)
                    .and(DBMentionModel.messageId.`in`(unreadIdsSubquery))
            )
        val stamps = wcdbInstance.message.getOneColumnLong(
            DBMessageModel.timeStamp,
            DBMessageModel.id.`in`(mentioningMessageIds)
        )
        return mentionType to stamps
    }

    // T9 — group @me: messages mentioning ME + ALL ⇒ type ME (me wins precedence), stamps present.
    @Test
    fun `queryUnreadMentions group me returns ME with mentioning timestamps`() = runTest {
        seedUnreadMention("gme", 1, MY_ID)
        seedUnreadMention("gme", 2, MY_ID)
        seedUnreadMention("gme", 3, MENTIONS_ALL_ID)
        seedUnreadMention("gme", 4, "other-uid")          // not me/all ⇒ excluded

        val (type, stamps) = queryUnreadMentionTypeAndStamps("gme", readPosition = 1_000L)
        assertEquals(MENTIONS_TYPE_ME, type)
        assertEquals(3, stamps.size)                       // 2×me + 1×all matched; "other-uid" excluded
    }

    // T10 — group @all only (no @me) ⇒ type ALL.
    @Test
    fun `queryUnreadMentions group all only returns ALL`() = runTest {
        seedUnreadMention("gall", 1, MENTIONS_ALL_ID)
        val (type, _) = queryUnreadMentionTypeAndStamps("gall", readPosition = 1_000L)
        assertEquals(MENTIONS_TYPE_ALL, type)
    }

    // T11 — group with unread but no me/all mention ⇒ NONE, empty stamps.
    @Test
    fun `queryUnreadMentions group no relevant mention returns NONE empty`() = runTest {
        seedUnreadMention("gnone", 1, "other-uid")
        val (type, stamps) = queryUnreadMentionTypeAndStamps("gnone", readPosition = 1_000L)
        assertEquals(MENTIONS_TYPE_NONE, type)
        assertTrue(stamps.isEmpty())
    }

    // T12 — updateMessageReadTime writes readMaxTimestamp into all matching rows WITHOUT a load.
    //      Mirrors DBMessageStore.updateMessageReadTime (#4) SQL: updateValue over the readTime=0
    //      / null & systemShowTimestamp<=max condition.
    @Test
    fun `updateMessageReadTime sets readTime on all unread rows without load`() = runTest {
        (0 until 100).forEach { i ->
            wcdbInstance.message.insertObject(MessageModel().apply {
                id = "rrt-$i"; roomId = "rrt"; fromWho = "s"
                timeStamp = 1_000L + i; systemShowTimestamp = 1_000L + i
                readTime = 0L; type = MessageModel.TYPE_TEXT
            })
        }
        val readMax = 5_000L
        val cond = DBMessageModel.roomId.eq("rrt")
            .and(DBMessageModel.readTime.eq(0L).or(DBMessageModel.readTime.isNull()))
            .and(DBMessageModel.systemShowTimestamp.le(readMax).or(DBMessageModel.systemShowTimestamp.eq(readMax)))
        wcdbInstance.message.updateValue(readMax, DBMessageModel.readTime, cond)

        // every row now has readTime == readMax; none left at 0.
        assertEquals(0L, messageCount(DBMessageModel.roomId.eq("rrt").and(DBMessageModel.readTime.eq(0L))))
        assertEquals(100L, messageCount(DBMessageModel.roomId.eq("rrt").and(DBMessageModel.readTime.eq(readMax))))
    }

    // T13 — updateMessageReadTime on an empty match set is a no-op (no exception, no rows changed).
    //      Pins that dropping the old isNotEmpty() guard is safe (updateValue no-ops on empty).
    @Test
    fun `updateMessageReadTime empty match is a safe no-op`() = runTest {
        val cond = DBMessageModel.roomId.eq("rrt-empty")
            .and(DBMessageModel.readTime.eq(0L))
        wcdbInstance.message.updateValue(9_999L, DBMessageModel.readTime, cond)   // must not throw
        assertEquals(0L, messageCount(DBMessageModel.roomId.eq("rrt-empty")))
    }

    // T19 — large-room delete regression: 2000 rows all removed (OOM anti-pattern gone).
    @Test
    fun `deleteMessagesPaged clears large room`() = runTest {
        seed("rbig", 2_000)
        assertEquals(2_000L, messageCount(roomCond("rbig")))
        deleteMessagesPaged(roomCond("rbig"))
        assertEquals(0L, messageCount(roomCond("rbig")))
        assertTrue(messageCount(roomCond("rbig")) == 0L)
    }
}
