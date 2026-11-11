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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue









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

    
    
    

    
    
    
    
    
    
    
    
    @Test
    fun `putMessageIfNotExists rolls back whole cascade on mid-cascade unique conflict`() {
        val msgId = "t3-conflict"
        
        wcdb.reaction.insertObject(ReactionModel().apply {
            messageId = msgId; emoji = "👍"; uid = "u1"; timeStamp = 1_000L
        })
        assertEquals(1, wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).size)
        assertNull(wcdb.message.getFirstObject(DBMessageModel.id.eq(msgId))) 

        val msg = textMessage(
            msgId, 1_000L,
            reactions = listOf(Reaction(emoji = "👍", uid = "u1", originTimestamp = 1_000L)),
        )

        var threw = false
        try {
            wcdb.putMessageIfNotExists(msg)
        } catch (e: Exception) {
            threw = true 
        }
        assertTrue(threw, "the mid-cascade UNIQUE conflict must propagate (rollback + rethrow)")

        
        assertNull(wcdb.message.getFirstObject(DBMessageModel.id.eq(msgId)))
        assertEquals(1, wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).size)
    }

    
    
    @Test
    fun `putMessageIfNotExists commits then is idempotent on repeat`() {
        val msgId = "t4-ok"
        val msg = textMessage(
            msgId, 2_000L,
            reactions = listOf(Reaction(emoji = "🎉", uid = "u2", originTimestamp = 2_000L)),
        )

        assertTrue(wcdb.putMessageIfNotExists(msg), "first call inserts")

        assertNotNull(wcdb.message.getFirstObject(DBMessageModel.id.eq(msgId)))
        assertEquals(1, wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).size)

        
        assertFalse(wcdb.putMessageIfNotExists(msg), "repeat reports no insert")
        assertEquals(1L, wcdb.message.getAllObjects(DBMessageModel.id.eq(msgId)).size.toLong())
        assertEquals(1, wcdb.reaction.getAllObjects(DBReactionModel.messageId.eq(msgId)).size)
    }

    
    
    

    
    
    
    
    @Test
    fun `verifySynchronousApplied reads NORMAL back on the write handle`() {
        
        wcdb.message.getAllObjects(DBMessageModel.id.eq("none"))

        val readBack = wcdb.db.getHandle(true).use {
            it.getValueFromSQL("PRAGMA synchronous")?.int
        }
        assertEquals(1, readBack, "setConfig must put synchronous=NORMAL(1) on the write handle")

        
        
        wcdb.verifySynchronousApplied()
        wcdb.verifySynchronousApplied() 
    }
}
