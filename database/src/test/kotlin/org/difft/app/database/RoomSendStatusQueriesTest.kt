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

/**
 * Integration tests for the room send-status queries in [RoomSendStatusQueries.kt] against a real
 * in-memory WCDB instance.
 *
 * **`@Ignore`-d, exactly like [WCDBPagedMessageAccessTest]**: WCDB loads its SQLite engine through
 * `System.loadLibrary`, which is unavailable to JVM unit tests on the host. These tests therefore
 * act as compilation guards + an executable specification of the SQL predicates; removing the
 * `@Ignore` locally (with a native-capable runner) runs them unchanged. The predicates they pin are
 * additionally covered by the on-device verification matrix.
 *
 * All decision logic deliberately lives in the pure functions covered by
 * `difft.android.messageserialization.model.RoomSendStatusTest`, which IS executable.
 *
 * Covers T3-10 … T3-15, T3-17, T3-18, T3-20, T3-21, T3-22.
 */
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

    // region seeding helpers

    private fun insertMessage(
        id: String,
        roomId: String,
        ts: Long,
        sendType: Int,
        type: Int = MessageModel.TYPE_TEXT,
        from: String = myId,
        roomType: Int = 0,
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
        })
    }

    /**
     * The archive tombstone as `LocalMessageCreator` builds it: `TYPE_NOTIFY`, sort sentinel 1L,
     * and — critically — `sendType` left at its default 0 (== SEND_TYPE_SENDING). This row is the
     * whole reason the predicates carry a type exclusion.
     */
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

    // endregion

    // T3-10 — a room whose ONLY failed-looking row is an archive tombstone must not be flagged.
    // A tombstone is never resent, so a tag earned by one would linger until the room's next
    // unrelated message change; the type exclusion keeps it from being reported as a failure at all.
    @Test
    fun `hasFailedOutgoingMessage ignores the archive tombstone`() {
        insertArchiveTombstone("r-tombstone", sendType = MessageModel.SEND_TYPE_FAILED)

        assertFalse(wcdbInstance.hasFailedOutgoingMessage("r-tombstone"))
    }

    // T3-11 — positive case, plus the DESC ordering that gives the probe its early exit.
    @Test
    fun `hasFailedOutgoingMessage finds a real failure and scans newest-first`() {
        insertMessage("r-hit-failed", "r-hit", ts = 1_000L, sendType = MessageModel.SEND_TYPE_FAILED)
        (1..50).forEach { i ->
            insertMessage("r-hit-sent-$i", "r-hit", ts = 1_000L + i, sendType = MessageModel.SEND_TYPE_SENT)
        }

        assertTrue(wcdbInstance.hasFailedOutgoingMessage("r-hit"))

        // Same predicate + ordering the probe uses: the first row SQLite reaches walking
        // idx_room_timestamp backwards is the failure, so the probe stops there instead of
        // scanning the room. Guards a regression to ASC (which would scan all 51 rows).
        val firstDesc = wcdbInstance.message.getFirstObject(
            DBMessageModel.roomId.eq("r-hit")
                .and(DBMessageModel.sendType.eq(MessageModel.SEND_TYPE_FAILED))
                .and(DBMessageModel.type.notIn(MessageModel.TYPE_NOTIFY, MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER)),
            DBMessageModel.systemShowTimestamp.order(Order.Desc)
        )
        assertEquals("r-hit-failed", firstDesc?.id)
    }

    @Test
    fun `hasFailedOutgoingMessage is false for an empty room`() {
        assertFalse(wcdbInstance.hasFailedOutgoingMessage("r-empty"))
    }

    // T3-12 — the anchor query returns the EARLIEST real failure, never the tombstone (whose
    // sentinel timestamp sorts before every real message), and exposes `timeStamp` for ToMessage.
    @Test
    fun `earliestFailedOutgoingMessage returns the oldest real failure`() {
        insertArchiveTombstone("r-anchor", sendType = MessageModel.SEND_TYPE_FAILED)
        insertMessage("r-anchor-100", "r-anchor", ts = 100L, sendType = MessageModel.SEND_TYPE_FAILED)
        insertMessage("r-anchor-200", "r-anchor", ts = 200L, sendType = MessageModel.SEND_TYPE_FAILED)

        val earliest = wcdbInstance.earliestFailedOutgoingMessage("r-anchor")

        assertNotNull(earliest)
        assertEquals("r-anchor-100", earliest.id)
        assertEquals(100L, earliest.systemShowTimestamp)
        // `timeStamp` (not systemShowTimestamp) is the key ScrollAction.ToMessage matches on.
        assertEquals(100L, earliest.timeStamp)
    }

    @Test
    fun `earliestFailedOutgoingMessage is null when only a tombstone looks failed`() {
        insertArchiveTombstone("r-anchor-none", sendType = MessageModel.SEND_TYPE_FAILED)

        assertNull(wcdbInstance.earliestFailedOutgoingMessage("r-anchor-none"))
    }

    // T3-13 — "from others" is an EXACT comparison, matching how `isMine` is derived. A
    // case-insensitive spelling would be a third definition of "from others" and would put the
    // anchor and the NEW MESSAGES divider on different messages.
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
        // The uppercase variant IS treated as someone else by an exact comparison — it is a
        // DIFFERENT uid string, so it legitimately precedes the real other-party message.
        assertEquals("u-mine-upper", first.id)
        // The load-bearing part: my own failed message (exact match on myId) is excluded, so the
        // conservative branch does not fire unconditionally.
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
        // Same roomId but the group flavour — must not leak into a 1:1 query.
        insertMessage("group-other", "r-rp", ts = 120L, sendType = MessageModel.SEND_TYPE_SENT, from = "other", roomType = 1)

        val first = wcdbInstance.firstUnreadFromOthersMessage("r-rp", roomType = 0, readPosition = 100L, myId = myId)

        assertEquals("newer-other", first?.id)
    }

    // T3-20 (FA1) — `sendStatus` ships with @WCDBDefault(intValue = 0), so a row written without
    // it reads back 0 (== NONE) rather than null or an exception.
    //
    // SCOPE NOTE: the genuine upgrade path (open a database file created by the PREVIOUS schema,
    // which has no `sendStatus` column at all, then mount the new RoomModel) cannot be built from
    // a JVM unit test — it needs a pre-upgrade database fixture and a native-capable runner. What
    // this pins is the default-value contract that the upgrade relies on; the migration itself is
    // covered by the on-device upgrade check.
    @Test
    fun `sendStatus reads back as NONE when never written`() {
        wcdbInstance.room.insertObject(RoomModel().apply {
            roomId = "r-default"
            roomType = 0
        })

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-default"))
    }

    // T3-21 — the conditional clear. Four cases; (a) + (b) passing together is also what proves
    // the subquery's unqualified `roomId` resolves against `message` and not the outer `room`
    // (a wrong resolution collapses the guard into "always clear" or "never clear").
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

        // Called WITHOUT any Kotlin-side pre-filter: the statement must defend itself.
        wcdbInstance.clearRoomSendStatusIfNoFailure("r-clear-b")

        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-clear-b"))
    }

    @Test
    fun `clearRoomSendStatusIfNoFailure treats a tombstone as no failure`() {
        insertRoom("r-clear-c", ROOM_SEND_STATUS_FAILED)
        insertArchiveTombstone("r-clear-c", sendType = MessageModel.SEND_TYPE_FAILED)

        wcdbInstance.clearRoomSendStatusIfNoFailure("r-clear-c")

        // Same predicate as `hasFailedOutgoingMessage`: the tombstone is not a failure, so the
        // room clears — the clear side is what recovers a room flagged on older rows.
        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-clear-c"))
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

    // Companion coverage for the unconditional writers, which are legal for SET sources only.
    @Test
    fun `writeRoomSendStatus and writeRoomSendStatusFor set the flag`() {
        insertRoom("r-write-1", ROOM_SEND_STATUS_NONE)
        insertRoom("r-write-2", ROOM_SEND_STATUS_NONE)

        wcdbInstance.writeRoomSendStatus("r-write-1", ROOM_SEND_STATUS_FAILED)
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-write-1"))

        wcdbInstance.writeRoomSendStatusFor(listOf("r-write-1", "r-write-2"), ROOM_SEND_STATUS_FAILED)
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-write-2"))

        // Empty list must not issue a statement (and must not touch anything).
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

    // ─── Write-path coverage (T3-15, T3-17, T3-18, T3-22) ─────────────────────────────────────

    // T3-15 — the cold-start sweep's ROOM-TAG side sees only REAL outgoing messages. A locally
    // created notify row (the archive tombstone) carries sendType 0 without ever having been sent,
    // so its room must not earn a tag; the message flip itself stays with WcdbJobStorage and keeps
    // its original, unnarrowed predicate.
    @Test
    fun `roomIdsWithStaleSendingOutgoing narrows to real outgoing messages`() {
        (1..3).forEach { i ->
            insertMessage("s-text-$i", "r-sweep", ts = 100L + i, sendType = MessageModel.SEND_TYPE_SENDING)
        }
        insertArchiveTombstone("r-tomb")

        // Distinct: three stale rows in one room yield one roomId, and the tombstone's room is absent.
        assertEquals(listOf("r-sweep"), wcdbInstance.roomIdsWithStaleSendingOutgoing())
    }

    @Test
    fun `roomIdsWithStaleSendingOutgoing writes nothing and is empty on a clean table`() {
        insertMessage("s-once", "r-sweep-idem", ts = 100L, sendType = MessageModel.SEND_TYPE_SENDING)

        assertEquals(listOf("r-sweep-idem"), wcdbInstance.roomIdsWithStaleSendingOutgoing())
        // Read-only: repeating the query does not consume the rows it reported.
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

    // T3-17 — the gated clear in WCDBUpdateService, exercised through the exact three-step sequence
    // that branch runs. The gate + resolve are pure functions (covered executably by
    // RoomSendStatusTest); what is pinned here is the DB-visible outcome for the three preview
    // shapes the surrounding branch can take.
    //
    // (a) is the load-bearing one: the clear is issued from its OWN statement, so it lands even
    // though no preview field changed — merging it into the preview writes (which sit behind a
    // `needsUpdate` guard that is false in exactly this case) would write nothing at all.
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

        // The gate short-circuits, so the failure is NOT discovered here. Escalation is the set
        // source's job — see the invariant in RoomSendStatus.kt.
        assertFalse(needsSendStatusRecompute(ROOM_SEND_STATUS_NONE))
        runGatedClear("r-gate-d")

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-gate-d"))
    }

    /** The WCDBUpdateService MESSAGE-branch sequence: gate, resolve, then the conditional clear. */
    private fun runGatedClear(roomId: String) {
        val stored = storedSendStatus(roomId) ?: return
        if (!needsSendStatusRecompute(stored)) return
        resolveRoomSendStatus(stored, wcdbInstance.hasFailedOutgoingMessage(roomId))
            ?.let { wcdbInstance.clearRoomSendStatusIfNoFailure(roomId) }
    }

    // T3-18 — a failed outgoing message is NOT an unread marker. `resetRoomUnreadState` runs every
    // time the user opens and leaves a conversation, so adding `sendStatus` to its column list would
    // make the tag disappear on the first visit and never come back.
    //
    // The real function resolves the global `wcdb` singleton, which no JVM unit test can point at
    // this in-memory instance; this applies its exact column list to the test DB instead. The
    // primary guard is the comment on `RoomModel.resetRoomUnreadState` itself.
    @Test
    fun `the reset-unread column set leaves sendStatus alone`() {
        insertRoom("r-reset", ROOM_SEND_STATUS_FAILED)

        // Mirrors WCDBExtensions.resetRoomUnreadState verbatim: three columns, none of them
        // sendStatus. Adding a fourth Value/field pair here means the production function changed.
        wcdbInstance.room.updateRow(
            arrayOf(Value(0), Value(MENTIONS_TYPE_NONE), Value(CRITICAL_ALERT_TYPE_NONE)),
            arrayOf(DBRoomModel.unreadMessageNum, DBRoomModel.mentionType, DBRoomModel.criticalAlertType),
            DBRoomModel.roomId.eq("r-reset")
        )

        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-reset"))
    }

    // T3-22 — the RACE-1 interleaving. A clear started from an already-stale probe must not undo a
    // FAILED that landed in the meantime, and the loss would not self-heal: the gate would read
    // NONE from then on and skip every later recompute.
    //
    // Sequence: S1 (message row flips to FAILED) -> B (a clear based on a pre-S1 probe) -> S2 (the
    // room write that follows S1 in the set source). Final state must be FAILED, and B specifically
    // must not have cleared anything.
    @Test
    fun `a stale clear cannot undo a concurrent failure`() {
        insertRoom("r-race", ROOM_SEND_STATUS_FAILED)
        insertMessage("race-sent", "r-race", ts = 100L, sendType = MessageModel.SEND_TYPE_SENT)

        // S1 — the set source commits the message row first. This ordering is load-bearing.
        insertMessage("race-failed", "r-race", ts = 200L, sendType = MessageModel.SEND_TYPE_FAILED)

        // B — a clear issued on the strength of a probe taken BEFORE S1.
        wcdbInstance.clearRoomSendStatusIfNoFailure("r-race")
        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-race"))

        // S2 — the room write that follows S1.
        wcdbInstance.writeRoomSendStatus("r-race", ROOM_SEND_STATUS_FAILED)

        assertEquals(ROOM_SEND_STATUS_FAILED, storedSendStatus("r-race"))
    }

    // Reverse control for the case above — WITHOUT this arm the test proves nothing, because a
    // no-op implementation would also "pass". Same sequence with an unconditional clear: the FAILED
    // is destroyed, which is exactly the regression `clearRoomSendStatusIfNoFailure` prevents.
    @Test
    fun `an unconditional clear does destroy a concurrent failure`() {
        insertRoom("r-race-control", ROOM_SEND_STATUS_FAILED)
        insertMessage("race-control-failed", "r-race-control", ts = 200L, sendType = MessageModel.SEND_TYPE_FAILED)

        wcdbInstance.writeRoomSendStatus("r-race-control", ROOM_SEND_STATUS_NONE)

        assertEquals(ROOM_SEND_STATUS_NONE, storedSendStatus("r-race-control"))
    }

    // region sendingStatus — the independent "sending" aggregate (same machinery, cloned per column)

    private fun insertRoomSending(roomId: String, sendingStatus: Int, roomType: Int = 0) {
        wcdbInstance.room.insertObject(RoomModel().apply {
            this.roomId = roomId
            this.roomType = roomType
            this.sendingStatus = sendingStatus
        })
    }

    private fun storedSendingStatus(roomId: String): Int? =
        wcdbInstance.room.getFirstObject(DBRoomModel.roomId.eq(roomId))?.sendingStatus

    // The tombstone leaves sendType at 0 (== SENDING), so without the type exclusion every
    // archived-only conversation would show the sending icon forever.
    @Test
    fun `hasSendingOutgoingMessage ignores the archive tombstone`() {
        insertArchiveTombstone("rs-tombstone")

        assertFalse(wcdbInstance.hasSendingOutgoingMessage("rs-tombstone"))
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

    // RACE-1 transplanted: a clear based on a stale probe must not undo an ACTIVE that a new
    // send committed in the meantime (its message row lands first — the load-bearing ordering).
    @Test
    fun `a stale sending clear cannot undo a concurrent new send`() {
        insertRoomSending("rs-race", ROOM_SENDING_STATUS_ACTIVE)
        insertMessage("rs-race-sending", "rs-race", ts = 200L, sendType = MessageModel.SEND_TYPE_SENDING)

        wcdbInstance.clearRoomSendingStatusIfNoSending("rs-race")
        assertEquals(ROOM_SENDING_STATUS_ACTIVE, storedSendingStatus("rs-race"))

        wcdbInstance.writeRoomSendingStatus("rs-race", ROOM_SENDING_STATUS_ACTIVE)

        assertEquals(ROOM_SENDING_STATUS_ACTIVE, storedSendingStatus("rs-race"))
    }

    // The sweep's post-flip cleanup: GLOBAL scope, so a room flagged stale for a reason
    // unrelated to the flip heals on the same pass; a room with a live sending row survives.
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

    // endregion
}
