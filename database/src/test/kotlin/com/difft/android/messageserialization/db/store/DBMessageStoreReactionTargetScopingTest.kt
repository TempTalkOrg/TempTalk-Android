package com.difft.android.messageserialization.db.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.RoomChangeTracker
import difft.android.messageserialization.model.MessageId
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.RealSource
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.wcdb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config











@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Ignore("WCDB native library not loadable in JVM unit tests; store binds the wcdb global. Run via instrumentation test instead.")
class DBMessageStoreReactionTargetScopingTest {

    private lateinit var wcdbInstance: WCDB
    private lateinit var store: DBMessageStore

    private val targetSender = "+10086"
    private val targetTs = 5_000L
    private val targetDevice = 1
    private val targetId = MessageId(targetSender, targetTs, targetDevice).idValue

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        wcdbInstance = TestWcdbFactory.createInMemoryWcdb(ctx)

        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        every { wcdb } returns wcdbInstance

        mockkObject(RoomChangeTracker)
        every { RoomChangeTracker.trackRoom(any(), any()) } just runs

        store = DBMessageStore(mockk<DBRoomStore>(relaxed = true))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun seedTargetMessage(roomId: String) {
        wcdbInstance.message.insertObject(MessageModel().apply {
            id = targetId
            this.roomId = roomId
            fromWho = targetSender
            timeStamp = targetTs
            systemShowTimestamp = targetTs
            type = MessageModel.TYPE_TEXT
        })
    }

    private fun incomingReaction(uid: String = "+99999", emoji: String = "\uD83D\uDC4D", remove: Boolean = false) =
        Reaction(
            emoji = emoji,
            uid = uid,
            remove = remove,
            originTimestamp = 9_000L,
            realSource = RealSource(targetSender, targetDevice, targetTs, 0L),
        )

    private fun reactionRows() =
        wcdbInstance.reaction.getAllObjects(DBReactionModel.messageId.eq(targetId))

    
    
    @Test
    fun `reaction targeting a message in another conversation is dropped`() = runTest {
        seedTargetMessage(roomId = "R1")

        store.updateMessageReaction("R2", incomingReaction(), "reaction-envelope-id", byteArrayOf(1, 2, 3))

        assertTrue("cross-conversation reaction must not be persisted", reactionRows().isEmpty())
    }

    
    @Test
    fun `reaction targeting a message in the same conversation is persisted`() = runTest {
        seedTargetMessage(roomId = "R1")

        store.updateMessageReaction("R1", incomingReaction(emoji = "\uD83D\uDC4D"), "reaction-envelope-id", byteArrayOf(1, 2, 3))

        val rows = reactionRows()
        assertEquals(1, rows.size)
        assertEquals("\uD83D\uDC4D", rows.first().emoji)
    }

    
    
    @Test
    fun `reaction whose target is absent is deferred, not orphan-inserted`() = runTest {
        

        store.updateMessageReaction("R1", incomingReaction(), "reaction-envelope-id", byteArrayOf(1, 2, 3))

        assertTrue("orphan reaction must not be persisted", reactionRows().isEmpty())
        assertEquals(
            "absent-target reaction must be deferred via savePendingMessage",
            1,
            wcdbInstance.pendingMessageNew.getAllObjects(
                DBPendingMessageModelNew.originalMessageTimeStamp.eq(targetTs)
            ).size
        )
    }

    
    
    @Test
    fun `reaction with absent target and null envelope drops without persisting`() = runTest {
        

        store.updateMessageReaction("R1", incomingReaction(), null, null)

        assertTrue(reactionRows().isEmpty())
        assertTrue(wcdbInstance.pendingMessageNew.allObjects.isEmpty())
    }
}
