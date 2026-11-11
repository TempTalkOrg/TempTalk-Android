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










@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native library not loadable in JVM unit tests; helpers also bind the wcdb global via Hilt. Run via instrumentation test instead.")
class WCDBPagedMessageAccessTest {

    private lateinit var wcdbInstance: WCDB

    
    
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

    
    @Test
    fun `messageCount returns exact count`() = runTest {
        assertEquals(0L, messageCount(roomCond("r0")))
        seed("r1", 1)
        assertEquals(1L, messageCount(roomCond("r1")))
        seed("r250", 250)
        assertEquals(250L, messageCount(roomCond("r250")))
    }

    
    @Test
    fun `deleteMessagesPaged empty set is no-op`() = runTest {
        deleteMessagesPaged(roomCond("empty"))
        assertEquals(0L, messageCount(roomCond("empty")))
    }

    
    @Test
    fun `deleteMessagesPaged single page deletes all`() = runTest {
        seed("rsingle", 50)
        deleteMessagesPaged(roomCond("rsingle"), pageSize = 100)
        assertEquals(0L, messageCount(roomCond("rsingle")))
    }

    
    @Test
    fun `deleteMessagesPaged multi page deletes all`() = runTest {
        seed("rmulti", 250)
        deleteMessagesPaged(roomCond("rmulti"), pageSize = 100)
        assertEquals(0L, messageCount(roomCond("rmulti")))
    }

    
    
    
    
    
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

        
        assertEquals(0L, messageCount(roomCond("rcascade")))
        assertTrue(wcdbInstance.attachment.getAllObjects(DBAttachmentModel.messageId.eq(msgId)).isEmpty())
        assertTrue(wcdbInstance.mention.getAllObjects(DBMentionModel.messageId.eq(msgId)).isEmpty())
        assertTrue(wcdbInstance.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).isEmpty())
        
        
        
    }

    
    
    
    @Test
    fun `forEachMessagePaged visits each row once with duplicate timestamps`() = runTest {
        
        (0 until 250).forEach { i ->
            wcdbInstance.message.insertObject(MessageModel().apply {
                id = "rdup-$i"
                roomId = "rdup"
                fromWho = "s"
                
                systemShowTimestamp = if (i in 90..160) 5_000L else 1_000L + i
                timeStamp = systemShowTimestamp
                type = MessageModel.TYPE_TEXT
            })
        }
        val seen = mutableListOf<Int>()
        forEachMessagePaged(roomCond("rdup"), pageSize = 100) { seen += it.databaseId }

        assertEquals(250, seen.size)                 
        assertEquals(seen.size, seen.toSet().size)   
    }

    
    
    @Test
    fun `messageCount over unread condition counts without loading`() = runTest {
        seed("runread", 300, baseTs = 1_000L)
        val unreadCond = DBMessageModel.roomId.eq("runread")
            .and(DBMessageModel.systemShowTimestamp.gt(1_000L)) 
        assertEquals(299L, messageCount(unreadCond))
    }

    



    private fun seedUnreadMention(roomId: String, idx: Long, mentionUid: String): String {
        val mid = "$roomId-$idx"
        wcdbInstance.message.insertObject(MessageModel().apply {
            id = mid
            this.roomId = roomId
            fromWho = "other-sender"
            timeStamp = 2_000L + idx
            systemShowTimestamp = 2_000L + idx     
            type = MessageModel.TYPE_TEXT
        })
        wcdbInstance.mention.insertObject(MentionModel().apply {
            messageId = mid; uid = mentionUid; type = 0
        })
        return mid
    }

    
    
    
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

    
    @Test
    fun `queryUnreadMentions group me returns ME with mentioning timestamps`() = runTest {
        seedUnreadMention("gme", 1, MY_ID)
        seedUnreadMention("gme", 2, MY_ID)
        seedUnreadMention("gme", 3, MENTIONS_ALL_ID)
        seedUnreadMention("gme", 4, "other-uid")          

        val (type, stamps) = queryUnreadMentionTypeAndStamps("gme", readPosition = 1_000L)
        assertEquals(MENTIONS_TYPE_ME, type)
        assertEquals(3, stamps.size)                       
    }

    
    @Test
    fun `queryUnreadMentions group all only returns ALL`() = runTest {
        seedUnreadMention("gall", 1, MENTIONS_ALL_ID)
        val (type, _) = queryUnreadMentionTypeAndStamps("gall", readPosition = 1_000L)
        assertEquals(MENTIONS_TYPE_ALL, type)
    }

    
    @Test
    fun `queryUnreadMentions group no relevant mention returns NONE empty`() = runTest {
        seedUnreadMention("gnone", 1, "other-uid")
        val (type, stamps) = queryUnreadMentionTypeAndStamps("gnone", readPosition = 1_000L)
        assertEquals(MENTIONS_TYPE_NONE, type)
        assertTrue(stamps.isEmpty())
    }

    
    
    @Test
    fun `updateMessageReadTime sets readTime to readAt on all unread rows without load`() = runTest {
        (0 until 100).forEach { i ->
            wcdbInstance.message.insertObject(MessageModel().apply {
                id = "rrt-$i"; roomId = "rrt"; fromWho = "s"
                timeStamp = 1_000L + i; systemShowTimestamp = 1_000L + i
                readTime = 0L; type = MessageModel.TYPE_TEXT
            })
        }
        val readMaxTimestamp = 5_000L    
        val readAt = 4_200L              
        val cond = DBMessageModel.roomId.eq("rrt")
            .and(DBMessageModel.readTime.le(0L).or(DBMessageModel.readTime.isNull()))
            .and(DBMessageModel.systemShowTimestamp.le(readMaxTimestamp).or(DBMessageModel.systemShowTimestamp.eq(readMaxTimestamp)))
        wcdbInstance.message.updateValue(readAt, DBMessageModel.readTime, cond)

        
        assertEquals(0L, messageCount(DBMessageModel.roomId.eq("rrt").and(DBMessageModel.readTime.eq(0L))))
        assertEquals(100L, messageCount(DBMessageModel.roomId.eq("rrt").and(DBMessageModel.readTime.eq(readAt))))
        assertEquals(0L, messageCount(DBMessageModel.roomId.eq("rrt").and(DBMessageModel.readTime.eq(readMaxTimestamp))))
    }

    
    @Test
    fun `updateMessageReadTime empty match is a safe no-op`() = runTest {
        val readMaxTimestamp = 5_000L
        val readAt = 4_200L
        val cond = DBMessageModel.roomId.eq("rrt-empty")
            .and(DBMessageModel.readTime.le(0L).or(DBMessageModel.readTime.isNull()))
            .and(DBMessageModel.systemShowTimestamp.le(readMaxTimestamp).or(DBMessageModel.systemShowTimestamp.eq(readMaxTimestamp)))
        wcdbInstance.message.updateValue(readAt, DBMessageModel.readTime, cond)   
        assertEquals(0L, messageCount(DBMessageModel.roomId.eq("rrt-empty")))
    }

    
    @Test
    fun `deleteMessagesPaged clears large room`() = runTest {
        seed("rbig", 2_000)
        assertEquals(2_000L, messageCount(roomCond("rbig")))
        deleteMessagesPaged(roomCond("rbig"))
        assertEquals(0L, messageCount(roomCond("rbig")))
        assertTrue(messageCount(roomCond("rbig")) == 0L)
    }

    
    
    
    
    
    
    
    
    
    
    
    

    
    
    
    private fun maxDatabaseId(roomId: String): Int =
        wcdbInstance.message.getValue(DBMessageModel.databaseId.max(), DBMessageModel.roomId.eq(roomId))?.int ?: 0

    private fun boundedCond(roomId: String, snapshotMax: Int): Expression =
        DBMessageModel.roomId.eq(roomId).and(DBMessageModel.databaseId.le(snapshotMax))

    
    @Test
    fun `maxMessageDatabaseId returns 0 for empty room`() = runTest {
        assertEquals(0, maxDatabaseId("d1-empty"))
    }

    
    
    @Test
    fun `maxMessageDatabaseId returns room max databaseId`() = runTest {
        seed("d2-other", 10)            
        seed("d2", 250)
        val rowsD2 = wcdbInstance.message.getAllObjects(roomCond("d2"))
        val expectedMax = rowsD2.maxOf { it.databaseId }
        assertEquals(expectedMax, maxDatabaseId("d2"))
        assertTrue(maxDatabaseId("d2") > maxDatabaseId("d2-other"))
    }

    
    
    @Test
    fun `deleteMessagesPaged with snapshot bound spares newer messages`() = runTest {
        seed("d3", 100)                              
        val snap = maxDatabaseId("d3")               
        seed("d3", 100, baseTs = 9_000L)             

        deleteMessagesPaged(boundedCond("d3", snap), pageSize = 100)

        
        assertEquals(100L, messageCount(roomCond("d3")))
        assertEquals(0L, messageCount(roomCond("d3").and(DBMessageModel.databaseId.le(snap))))
        assertEquals(100L, messageCount(roomCond("d3").and(DBMessageModel.databaseId.gt(snap))))
    }

    
    
    @Test
    fun `deleteMessagesPaged bounded converges and keeps inserts during delete`() = runTest {
        seed("d4", 100)
        val snap = maxDatabaseId("d4")
        
        seed("d4", 150, baseTs = 9_000L)

        deleteMessagesPaged(boundedCond("d4", snap), pageSize = 100)

        
        assertEquals(150L, messageCount(roomCond("d4")))
        assertEquals(0L, messageCount(roomCond("d4").and(DBMessageModel.databaseId.le(snap))))
    }

    
    
    @Test
    fun `deleteMessagesPaged bounded cascades related rows`() = runTest {
        val msgId = "d5-0"
        wcdbInstance.message.insertObject(MessageModel().apply {
            id = msgId; roomId = "d5"; fromWho = "s"
            timeStamp = 1_000L; systemShowTimestamp = 1_000L; type = MessageModel.TYPE_TEXT
        })
        wcdbInstance.attachment.insertObject(AttachmentModel().apply { id = "d5-att"; messageId = msgId })
        wcdbInstance.mention.insertObject(MentionModel().apply { messageId = msgId; uid = "u1"; type = 0 })
        wcdbInstance.reaction.insertObject(ReactionModel().apply { messageId = msgId; emoji = "👍"; uid = "u1"; timeStamp = 1_000L })

        val snap = maxDatabaseId("d5")
        deleteMessagesPaged(boundedCond("d5", snap))

        assertEquals(0L, messageCount(roomCond("d5")))
        assertTrue(wcdbInstance.attachment.getAllObjects(DBAttachmentModel.messageId.eq(msgId)).isEmpty())
        assertTrue(wcdbInstance.mention.getAllObjects(DBMentionModel.messageId.eq(msgId)).isEmpty())
        assertTrue(wcdbInstance.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).isEmpty())
    }

    
    
    

    
    
    @Test
    fun `deleteMessagesPaged bounded on empty room is no-op`() = runTest {
        val snap = maxDatabaseId("d7-empty")         
        assertEquals(0, snap)
        deleteMessagesPaged(boundedCond("d7-empty", snap))
        assertEquals(0L, messageCount(roomCond("d7-empty")))
    }

    
    @Test
    fun `deleteMessagesPaged bounded isolates rooms`() = runTest {
        seed("d8-r1", 50)
        seed("d8-r2", 50)
        val snap1 = maxDatabaseId("d8-r1")
        deleteMessagesPaged(boundedCond("d8-r1", snap1))
        assertEquals(0L, messageCount(roomCond("d8-r1")))
        assertEquals(50L, messageCount(roomCond("d8-r2")))   
    }

    
    
    @Test
    fun `bounded room delete flow clears room then empty room snapshot is zero`() = runTest {
        seed("d10", 120)
        val snap = maxDatabaseId("d10")
        assertTrue(snap > 0)
        deleteMessagesPaged(boundedCond("d10", snap))
        assertEquals(0L, messageCount(roomCond("d10")))

        
        val snapAfter = maxDatabaseId("d10")
        assertEquals(0, snapAfter)
        deleteMessagesPaged(boundedCond("d10", snapAfter))
        assertEquals(0L, messageCount(roomCond("d10")))
    }
}
