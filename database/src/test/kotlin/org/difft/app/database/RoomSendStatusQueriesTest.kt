package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.messageserialization.db.store.TestWcdbFactory
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Order
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_ACTIVE
import difft.android.messageserialization.model.ROOM_SENDING_STATUS_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import difft.android.messageserialization.model.needsSendStatusRecompute
import difft.android.messageserialization.model.resolveRoomSendStatus
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.RoomModel
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
@Ignore("WCDB native library not loadable in JVM unit tests. Run via instrumentation test instead.")
class RoomSendStatusQueriesTest {

    private lateinit var wcdbInstance: WCDB

    private val myId = "my-uid"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        wcdbInstance = TestWcdbFactory.createInMemoryWcdb(ctx)
    }

    

    private fun insertMessage(
        id: String,
        roomId: String,
        ts: Long,
        sendType: Int,
        type: Int = MessageModel.TYPE_TEXT,
        from: String = myId,
        roomType: Int = 0,
        screenShotJson: String? = null,
    ) {
        wcdbInstance.message.insertObject(MessageModel().apply {
            this.id = id
            this.roomId = roomId
            this.roomType = roomType
            this.fromWho = from
            this.timeStamp = ts
            this.systemShowTimestamp = ts
            this.type = type
            this.sendType = sendType
            this.screenShotJson = screenShotJson
        })
    }

    





    private fun insertScreenshotRow(roomId: String, sendType: Int = MessageModel.SEND_TYPE_FAILED) {
        insertMessage(
            id = "$roomId-screenshot",
            roomId = roomId,
            ts = 100L,
            sendType = sendType,
            screenShotJson = """{"realSource":{"source":"$myId","sourceDevice":1,"timestamp":100,"serverTimestamp":100}}""",
        )
    }

    




    private fun insertArchiveTombstone(roomId: String, sendType: Int = MessageModel.SEND_TYPE_SENDING) {
        insertMessage(
            id = "$roomId-tombstone",
            roomId = roomId,
            ts = MessageModel.ARCHIVE_TOMBSTONE_SORT_SENTINEL,
            sendType = sendType,
            type = MessageModel.TYPE_NOTIFY,
        )
    }

    private fun insertRoom(roomId: String, sendStatus: Int, roomType: Int = 0) {
        wcdbInstance.room.insertObject(RoomModel().apply {
            this.roomId = roomId
            this.roomType = roomType
            this.sendStatus = sendStatus
        })
    }

    private fun storedSendStatus(roomId: String): Int? =
        wcdbInstance.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.sendStatus

    

    
    
    
    @Test
    fun `hasFailedOutgoingMessage ignores the archive tombstone`() {
        insertArchiveTombstone("r-tombstone", sendType = MessageModel.SEND_TYPE_FAILED)

        assertFalse(wcdbInstance.hasFailedOutgoingMessage("r-tombstone"))
    }

    
    @Test
    fun `hasFailedOutgoingMessage finds a real failure and scans newest-first`() {
        insertMessage("r-hit-failed", "r-hit", ts = 1_000L, sendType = MessageModel.SEND_TYPE_FAILED)
        (1..50).forEach { i ->
            insertMessage("r-hit-sent-$i", "r-hit", ts = 1_000L + i, sendType = MessageModel.SEND_TYPE_SENT)
        }

        assertTrue(wcdbInstance.hasFailedOutgoingMessage("r-hit"))

        
        
        
        val firstDesc = wcdbInstance.message.getFirstObject(
            DBMessageModel.roomId.eq("r-hit")
                .and(DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_FAILED))
                .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER)),
            DBMessageModel.systemShowTimestamp.order(Order.Desc)
        )
        assertEquals("r-hit-failed", firstDesc?.id)
    }

    
    
    @Test
    fun `hasFailedOutgoingMessage ignores a failed screenshot row`() {
        insertScreenshotRow("r-screenshot")

        assertFalse(wcdbInstance.hasFailedOutgoingMessage("r-screenshot"))
    }

    
    
    @Test
    fun `hasFailedOutgoingMessage still sees a real failure beside a screenshot row`() {
        insertScreenshotRow("r-screenshot-mixed")
        insertMessage("mixed-failed", "r-screenshot-mixed", ts = 200L, sendType = MessageModel.SEND_TYPE_FAILED)

        assertTrue(wcdbInstance.hasFailedOutgoingMessage("r-screenshot-mixed"))
    }

    @Test
    fun `hasFailedOutgoingMessage is false for an empty room`() {
        assertFalse(wcdbInstance.hasFailedOutgoingMessage("r-empty"))
    }

    
    
    @Test
    fun `earliestFailedOutgoingMessage returns the oldest real failure`() {
        insertArchiveTombstone("r-anchor", sendType = MessageModel.SEND_TYPE_FAILED)
        insertMessage("r-anchor-100", "r-anchor", ts = 100L, sendType = MessageModel.SEND_TYPE_FAILED)
        insertMessage("r-anchor-200", "r-anchor", ts = 200L, sendType = MessageModel.SEND_TYPE_FAILED)

        val earliest = wcdbInstance.earliestFailedOutgoingMessage("r-anchor")

        assertNotNull(earliest)
        assertEquals("r-anchor-100", earliest.id)
        assertEquals(100L, earliest.systemShowTimestamp)
        
        assertEquals(100L, earliest.timeStamp)
    }

    @Test
    fun `earliestFailedOutgoingMessage is null when only a tombstone looks failed`() {
        insertArchiveTombstone("r-anchor-none", sendType = MessageModel.SEND_TYPE_FAILED)

        assertNull(wcdbInstance.earliestFailedOutgoingMessage("r-anchor-none"))
    }

    
    
    
    @Test
    fun `firstUnreadFromOthersMessage excludes my own messages exactly`() {
        val readPosition = 100L
        insertMessage("u-mine-failed", "r-unread", ts = 110L, sendType = MessageModel.SEND_TYPE_FAILED, from = myId)
        insertMessage("u-mine-upper", "r-unread", ts = 120L, sendType = MessageModel.SEND_TYPE_SENT, from = myId.uppercase())
        insertMessage("u-other", "r-unread", ts = 130L, sendType = MessageModel.SEND_TYPE_SENT, from = "other-uid")

        val first = wcdbInstance.firstUnreadFromOthersMessage(
            roomId = "r-unread",
            roomType = 0,
            readPosition = readPosition,
            myId = myId,
        )

        assertNotNull(first)
        
        
        assertEquals("u-mine-upper", first.id)
        
        
        assertTrue(first.id != "u-mine-failed")
    }

    @Test
    fun `firstUnreadFromOthersMessage is null when everything newer is mine`() {
        insertMessage("only-mine", "r-only-mine", ts = 110L, sendType = MessageModel.SEND_TYPE_FAILED, from = myId)

        assertNull(
            wcdbInstance.firstUnreadFromOthersMessage(
                roomId = "r-only-mine",
                roomType = 0,
                readPosition = 100L,
                myId = myId,
            )
        )
    }

    @Test
    fun `firstUnreadFromOthersMessage respects readPosition and roomType`() {
        insertMessage("older-other", "r-rp", ts = 50L, sendType = MessageModel.SEND_TYPE_SENT, from = "other")
        insertMessage("newer-other", "r-rp", ts = 150L, sendType = MessageModel.SEND_TYPE_SENT, from = "other")
        
        insertMessage("group-other", "r-rp", ts = 120L, sendType = MessageModel.SEND_TYPE_SENT, from = "other", roomType = 1)

        val first = wcdbInstance.firstUnreadFromOthersMessage("r-rp", roomType = 0, readPosition = 100L, myId = myId)

        assertEquals("newer-other", first?.id)
    }

    
    
    
    
    
    
    
    
    @Test
    fun `sendStatus reads back as NONE when never written`() {
        wcdbInstance.room.insertObject(RoomModel().apply {
            roomId = "r-default"
            roomType = 0
        })

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-default"))
    }

    
    
    
    @Test
    fun `clearRoomSendStatusIfNoFailure clears a flagged room with no failures left`() {
        insertRoom("r-clear-a", ROOM_SEND_STATUS_FAILED)
        insertMessage("a-sent", "r-clear-a", ts = 100L, sendType = MessageModel.SEND_TYPE_SENT)

        wcdbInstance.clearRoomSendStatusIfNoFailure("r-clear-a")

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-clear-a"))
    }

    @Test
    fun `clearRoomSendStatusIfNoFailure is a no-op while a failure remains`() {
        insertRoom("r-clear-b", ROOM_SEND_STATUS_FAILED)
        insertMessage("b-failed", "r-clear-b", ts = 100L, sendType = MessageModel.SEND_TYPE_FAILED)

        
        wcdbInstance.clearRoomSendStatusIfNoFailure("r-clear-b")

        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-clear-b"))
    }

    @Test
    fun `clearRoomSendStatusIfNoFailure treats a tombstone as no failure`() {
        insertRoom("r-clear-c", ROOM_SEND_STATUS_FAILED)
        insertArchiveTombstone("r-clear-c", sendType = MessageModel.SEND_TYPE_FAILED)

        wcdbInstance.clearRoomSendStatusIfNoFailure("r-clear-c")

        
        
        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-clear-c"))
    }

    @Test
    fun `clearRoomSendStatusIfNoFailure treats a failed screenshot row as no failure`() {
        insertRoom("r-clear-screenshot", ROOM_SEND_STATUS_FAILED)
        insertScreenshotRow("r-clear-screenshot")

        wcdbInstance.clearRoomSendStatusIfNoFailure("r-clear-screenshot")

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-clear-screenshot"))
    }

    
    
    
    @Test
    fun `flagged-room listing plus guarded clears heal a room flagged only by a screenshot row`() {
        insertRoom("r-reconcile-screenshot", ROOM_SEND_STATUS_FAILED)
        insertRoom("r-reconcile-real", ROOM_SEND_STATUS_FAILED)
        insertRoom("r-reconcile-clean", ROOM_SEND_STATUS_NONE)
        insertScreenshotRow("r-reconcile-screenshot")
        insertMessage("reconcile-failed", "r-reconcile-real", ts = 100L, sendType = MessageModel.SEND_TYPE_FAILED)

        val flagged = wcdbInstance.roomIdsWithNonNoneSendStatus()
        assertEquals(setOf("r-reconcile-screenshot", "r-reconcile-real"), flagged)
        flagged.forEach { wcdbInstance.clearRoomSendStatusIfNoFailure(it) }

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-reconcile-screenshot"))
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-reconcile-real"))
        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-reconcile-clean"))
    }

    @Test
    fun `clearRoomSendStatusIfNoFailure leaves an already-clear room untouched`() {
        insertRoom("r-clear-d", ROOM_SEND_STATUS_NONE)

        wcdbInstance.clearRoomSendStatusIfNoFailure("r-clear-d")

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-clear-d"))
    }

    @Test
    fun `clearRoomSendStatusIfNoFailure only touches the addressed room`() {
        insertRoom("r-clear-target", ROOM_SEND_STATUS_FAILED)
        insertRoom("r-clear-other", ROOM_SEND_STATUS_FAILED)
        insertMessage("other-failed", "r-clear-other", ts = 100L, sendType = MessageModel.SEND_TYPE_FAILED)

        wcdbInstance.clearRoomSendStatusIfNoFailure("r-clear-target")

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-clear-target"))
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-clear-other"))
    }

    
    @Test
    fun `writeRoomSendStatus and writeRoomSendStatusFor set the flag`() {
        insertRoom("r-write-1", ROOM_SEND_STATUS_NONE)
        insertRoom("r-write-2", ROOM_SEND_STATUS_NONE)

        wcdbInstance.writeRoomSendStatus("r-write-1", ROOM_SEND_STATUS_FAILED)
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-write-1"))

        wcdbInstance.writeRoomSendStatusFor(listOf("r-write-1", "r-write-2"), ROOM_SEND_STATUS_FAILED)
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-write-2"))

        
        wcdbInstance.writeRoomSendStatusFor(emptyList(), ROOM_SEND_STATUS_NONE)
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-write-1"))
    }

    @Test
    fun `backfill scan helpers report the reconciliation inputs`() {
        insertRoom("r-bf-flagged", ROOM_SEND_STATUS_FAILED)
        insertRoom("r-bf-clean", ROOM_SEND_STATUS_NONE)
        insertMessage("bf-failed", "r-bf-clean", ts = 100L, sendType = MessageModel.SEND_TYPE_FAILED)
        insertArchiveTombstone("r-bf-tombstone", sendType = MessageModel.SEND_TYPE_FAILED)

        assertEquals(setOf("r-bf-clean"), wcdbInstance.roomIdsWithFailedOutgoingMessage())
        assertEquals(setOf("r-bf-flagged"), wcdbInstance.roomIdsWithNonNoneSendStatus())
    }

    

    
    
    
    
    @Test
    fun `roomIdsWithStaleSendingOutgoing narrows to real outgoing messages`() {
        (1..3).forEach { i ->
            insertMessage("s-text-$i", "r-sweep", ts = 100L + i, sendType = MessageModel.SEND_TYPE_SENDING)
        }
        insertArchiveTombstone("r-tomb")

        
        assertEquals(listOf("r-sweep"), wcdbInstance.roomIdsWithStaleSendingOutgoing())
    }

    @Test
    fun `roomIdsWithStaleSendingOutgoing writes nothing and is empty on a clean table`() {
        insertMessage("s-once", "r-sweep-idem", ts = 100L, sendType = MessageModel.SEND_TYPE_SENDING)

        assertEquals(listOf("r-sweep-idem"), wcdbInstance.roomIdsWithStaleSendingOutgoing())
        
        assertEquals(listOf("r-sweep-idem"), wcdbInstance.roomIdsWithStaleSendingOutgoing())
        assertEquals(
            MessageModel.SEND_TYPE_SENDING,
            wcdbInstance.message.getFirstObject(DBMessageModel.id.eq("s-once"))?.sendType
        )
    }

    @Test
    fun `roomIdsWithStaleSendingOutgoing is empty when nothing looks stale`() {
        insertMessage("s-sent", "r-sweep-clean", ts = 100L, sendType = MessageModel.SEND_TYPE_SENT)

        assertEquals(emptyList<String>(), wcdbInstance.roomIdsWithStaleSendingOutgoing())
    }

    
    
    
    
    
    
    
    
    @Test
    fun `gated clear applies when no preview field changed`() {
        insertRoom("r-gate-a", ROOM_SEND_STATUS_FAILED)
        insertMessage("gate-a-sent", "r-gate-a", ts = 100L, sendType = MessageModel.SEND_TYPE_SENT)

        runGatedClear("r-gate-a")

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-gate-a"))
    }

    @Test
    fun `gated clear applies to a room with no messages at all`() {
        insertRoom("r-gate-b", ROOM_SEND_STATUS_FAILED)

        runGatedClear("r-gate-b")

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-gate-b"))
    }

    @Test
    fun `gated clear keeps FAILED when an archived room still holds a real failure`() {
        insertRoom("r-gate-c", ROOM_SEND_STATUS_FAILED)
        insertArchiveTombstone("r-gate-c")
        insertMessage("gate-c-failed", "r-gate-c", ts = 100L, sendType = MessageModel.SEND_TYPE_FAILED)

        runGatedClear("r-gate-c")

        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-gate-c"))
    }

    @Test
    fun `gated clear issues no statement for a room stored as NONE`() {
        insertRoom("r-gate-d", ROOM_SEND_STATUS_NONE)
        insertMessage("gate-d-failed", "r-gate-d", ts = 100L, sendType = MessageModel.SEND_TYPE_FAILED)

        
        
        assertFalse(needsSendStatusRecompute(ROOM_SEND_STATUS_NONE))
        runGatedClear("r-gate-d")

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-gate-d"))
    }

    
    private fun runGatedClear(roomId: String) {
        val stored = storedSendStatus(roomId) ?: return
        if (!needsSendStatusRecompute(stored)) return
        resolveRoomSendStatus(stored, wcdbInstance.hasFailedOutgoingMessage(roomId))
            ?.let { wcdbInstance.clearRoomSendStatusIfNoFailure(roomId) }
    }

    
    
    
    
    
    
    
    @Test
    fun `the reset-unread column set leaves sendStatus alone`() {
        insertRoom("r-reset", ROOM_SEND_STATUS_FAILED)

        
        
        wcdbInstance.room.updateRow(
            arrayOf(Value(0), Value(MENTIONS_TYPE_NONE), Value(CRITICAL_ALERT_TYPE_NONE)),
            arrayOf(DBRoomModel.unreadMessageNum, DBRoomModel.mentionType, DBRoomModel.criticalAlertType),
            DBRoomModel.roomId.eq("r-reset")
        )

        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-reset"))
    }

    
    
    
    @Test
    fun `a stale clear cannot undo a concurrent failure`() {
        insertRoom("r-race", ROOM_SEND_STATUS_FAILED)
        insertMessage("race-sent", "r-race", ts = 100L, sendType = MessageModel.SEND_TYPE_SENT)

        
        insertMessage("race-failed", "r-race", ts = 200L, sendType = MessageModel.SEND_TYPE_FAILED)

        
        wcdbInstance.clearRoomSendStatusIfNoFailure("r-race")
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-race"))

        
        wcdbInstance.writeRoomSendStatus("r-race", ROOM_SEND_STATUS_FAILED)

        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-race"))
    }

    
    
    
    @Test
    fun `an unconditional clear does destroy a concurrent failure`() {
        insertRoom("r-race-control", ROOM_SEND_STATUS_FAILED)
        insertMessage("race-control-failed", "r-race-control", ts = 200L, sendType = MessageModel.SEND_TYPE_FAILED)

        wcdbInstance.writeRoomSendStatus("r-race-control", ROOM_SEND_STATUS_NONE)

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-race-control"))
    }

    

    private fun insertRoomSending(roomId: String, sendingStatus: Int, roomType: Int = 0) {
        wcdbInstance.room.insertObject(RoomModel().apply {
            this.roomId = roomId
            this.roomType = roomType
            this.sendingStatus = sendingStatus
        })
    }

    private fun storedSendingStatus(roomId: String): Int? =
        wcdbInstance.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.sendingStatus

    
    
    @Test
    fun `hasSendingOutgoingMessage ignores the archive tombstone`() {
        insertArchiveTombstone("rs-tombstone")

        assertFalse(wcdbInstance.hasSendingOutgoingMessage("rs-tombstone"))
    }

    
    
    
    @Test
    fun `hasSendingOutgoingMessage ignores a screenshot row`() {
        insertScreenshotRow("rs-screenshot", sendType = MessageModel.SEND_TYPE_SENDING)

        assertFalse(wcdbInstance.hasSendingOutgoingMessage("rs-screenshot"))
    }

    @Test
    fun `hasSendingOutgoingMessage sees a real sending row`() {
        insertMessage("rs-m1", "rs-real", ts = 100L, sendType = MessageModel.SEND_TYPE_SENDING)

        assertTrue(wcdbInstance.hasSendingOutgoingMessage("rs-real"))
    }

    @Test
    fun `guarded sending clear clears when no sending row remains`() {
        insertRoomSending("rs-clear-a", ROOM_SENDING_STATUS_ACTIVE)
        insertMessage("rs-a-sent", "rs-clear-a", ts = 100L, sendType = MessageModel.SEND_TYPE_SENT)

        wcdbInstance.clearRoomSendingStatusIfNoSending("rs-clear-a")

        assertEquals(ROOM_SENDING_STATUS_NONE, storedSendingStatus("rs-clear-a"))
    }

    @Test
    fun `guarded sending clear is a no-op while a sending row remains`() {
        insertRoomSending("rs-clear-b", ROOM_SENDING_STATUS_ACTIVE)
        insertMessage("rs-b-sending", "rs-clear-b", ts = 100L, sendType = MessageModel.SEND_TYPE_SENDING)

        wcdbInstance.clearRoomSendingStatusIfNoSending("rs-clear-b")

        assertEquals(ROOM_SENDING_STATUS_ACTIVE, storedSendingStatus("rs-clear-b"))
    }

    @Test
    fun `guarded sending clear leaves other rooms alone`() {
        insertRoomSending("rs-clear-target", ROOM_SENDING_STATUS_ACTIVE)
        insertRoomSending("rs-clear-other", ROOM_SENDING_STATUS_ACTIVE)
        insertMessage("rs-other-sending", "rs-clear-other", ts = 100L, sendType = MessageModel.SEND_TYPE_SENDING)

        wcdbInstance.clearRoomSendingStatusIfNoSending("rs-clear-target")

        assertEquals(ROOM_SENDING_STATUS_NONE, storedSendingStatus("rs-clear-target"))
        assertEquals(ROOM_SENDING_STATUS_ACTIVE, storedSendingStatus("rs-clear-other"))
    }

    
    
    @Test
    fun `a stale sending clear cannot undo a concurrent new send`() {
        insertRoomSending("rs-race", ROOM_SENDING_STATUS_ACTIVE)
        insertMessage("rs-race-sending", "rs-race", ts = 200L, sendType = MessageModel.SEND_TYPE_SENDING)

        wcdbInstance.clearRoomSendingStatusIfNoSending("rs-race")
        assertEquals(ROOM_SENDING_STATUS_ACTIVE, storedSendingStatus("rs-race"))

        wcdbInstance.writeRoomSendingStatus("rs-race", ROOM_SENDING_STATUS_ACTIVE)

        assertEquals(ROOM_SENDING_STATUS_ACTIVE, storedSendingStatus("rs-race"))
    }

    
    
    @Test
    fun `flagged-room listing plus per-room guarded clears heal only truly stale flags`() {
        insertRoomSending("rs-global-stale", ROOM_SENDING_STATUS_ACTIVE)
        insertRoomSending("rs-global-live", ROOM_SENDING_STATUS_ACTIVE)
        insertRoomSending("rs-global-clean", ROOM_SENDING_STATUS_NONE)
        insertMessage("rs-live-sending", "rs-global-live", ts = 100L, sendType = MessageModel.SEND_TYPE_SENDING)

        val flagged = wcdbInstance.roomIdsWithSendingStatusFlagged()
        assertEquals(setOf("rs-global-stale", "rs-global-live"), flagged.toSet())
        flagged.forEach { wcdbInstance.clearRoomSendingStatusIfNoSending(it) }

        assertEquals(ROOM_SENDING_STATUS_NONE, storedSendingStatus("rs-global-stale"))
        assertEquals(ROOM_SENDING_STATUS_ACTIVE, storedSendingStatus("rs-global-live"))
        assertEquals(ROOM_SENDING_STATUS_NONE, storedSendingStatus("rs-global-clean"))
    }

    
}
