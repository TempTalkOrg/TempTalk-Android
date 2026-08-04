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

/**
 * Target-scoping guard for [DBMessageStore.updateMessageReaction] — the core of the
 * "非成员跨会话 reaction 注入" security fix (方案 A).
 *
 * A reaction's target message is located by a global `realMessageId`
 * (`source + timestamp + sourceDevice`), all attacker-controllable from the E2E inner
 * proto. The fix requires the target message to actually EXIST and belong to the
 * reaction's conversation before the reaction is persisted; otherwise it is deferred
 * (target not yet arrived) or dropped (cross-conversation injection).
 *
 * **Currently @Ignore-d** for the same reason as [org.difft.app.database.WCDBPagedMessageAccessTest]:
 * WCDB (Tencent SQLite wrapper) + its winq expression builders load native libraries via
 * `System.loadLibrary`, which are not available to JVM unit tests on the host machine. The
 * top-level `wcdb` global is redirected here to a real in-memory instance via `mockkStatic`
 * so `DBMessageStore` (which binds `wcdb` globally) exercises the real store logic. Run under
 * instrumentation (androidText source set + emulator/device). Kept here as a compilation guard
 * and documented expected behavior.
 *
 * NOTE: `conversationId` is currently still derived from the sender's inner proto
 * (`dataMessage.group.id`), so the group-injection vector is only fully closed once the
 * follow-up 方案 C (server-stamped `msgExtra.conversationId` + inner/outer consistency)
 * makes `conversationId` trustworthy. These tests pin the local binding invariant that 方案 C
 * will build upon: private-chat cross-injection drop + orphan pre-plant defer + same-room allow.
 */
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

    // Target message lives in R1, but the reaction is delivered against conversation R2
    // (e.g. attacker's 1v1 envelope). Must be dropped — cross-conversation injection.
    @Test
    fun `reaction targeting a message in another conversation is dropped`() = runTest {
        seedTargetMessage(roomId = "R1")

        store.updateMessageReaction("R2", incomingReaction(), "reaction-envelope-id", byteArrayOf(1, 2, 3))

        assertTrue("cross-conversation reaction must not be persisted", reactionRows().isEmpty())
    }

    // Legit case: target message and reaction share the same conversation → persisted.
    @Test
    fun `reaction targeting a message in the same conversation is persisted`() = runTest {
        seedTargetMessage(roomId = "R1")

        store.updateMessageReaction("R1", incomingReaction(emoji = "\uD83D\uDC4D"), "reaction-envelope-id", byteArrayOf(1, 2, 3))

        val rows = reactionRows()
        assertEquals(1, rows.size)
        assertEquals("\uD83D\uDC4D", rows.first().emoji)
    }

    // Target message not present locally → do NOT blind-insert an orphan (which would render
    // once the target arrives = pre-planting). Defer via pending_message_new for later replay.
    @Test
    fun `reaction whose target is absent is deferred, not orphan-inserted`() = runTest {
        // no seedTargetMessage()

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

    // Local optimistic write / rollback path passes null envelope: absent target simply drops
    // (nothing to defer) and never orphan-inserts.
    @Test
    fun `reaction with absent target and null envelope drops without persisting`() = runTest {
        // no seedTargetMessage()

        store.updateMessageReaction("R1", incomingReaction(), null, null)

        assertTrue(reactionRows().isEmpty())
        assertTrue(wcdbInstance.pendingMessageNew.allObjects.isEmpty())
    }
}
