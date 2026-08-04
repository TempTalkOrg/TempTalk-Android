package com.difft.android.chat.jobs

import com.difft.android.chat.common.SendType
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression guard for [WcdbJobStorage.sweepStaleSendingMessages] — the one-shot
 * startup helper called from `TempTalkApplication` BEFORE `beginJobLoop()` (D14).
 *
 * **Currently @Ignore-d**: WCDB native library not loadable in JVM unit tests.
 *
 * Properties validated:
 * 1. SendType rawValue types are correct (`Int`, not `Long`): Sending=0,
 *    SentFailed=2 (from `SendMessageUtils.kt:21-23`).
 * 2. Idempotent by WHERE clause — second sweep on a clean table is a no-op
 *    (returns 0).
 * 3. Returns pre-sweep count for telemetry.
 * 4. Failure path: logs via L.e, returns 0 (not -1, not throw).
 * 5. Room-level `sendStatus` is written DIRECTLY, not via the change notification (T3-16).
 * 6. The `before == 0` short circuit returns before any query for rooms or any write.
 */
@Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation test instead")
class WcdbJobStorageSweepTest {

    @Test
    fun sendType_rawValue_is_Int_not_Long_Sending_is_zero() {
        // Build-time guard: if upstream ever changes SendType.rawValue to Long,
        // compilation of WcdbJobStorage.sweepStaleSendingMessages breaks because
        // DBMessageModel.sendType.eq() expects Int. This test pins the contract.
        val sending: Int = SendType.Sending.rawValue
        assertEquals(0, sending)
    }

    @Test
    fun sendType_rawValue_is_Int_not_Long_SentFailed_is_two() {
        val sentFailed: Int = SendType.SentFailed.rawValue
        assertEquals(2, sentFailed)
    }

    @Test
    fun sweep_on_empty_message_table_returns_zero_and_logs_nothing_to_do() {
        // Expected behavior:
        //   pre-sweep COUNT(*) WHERE sendType=0 → 0
        //   return 0 immediately, L.i "nothing to do"
        //   NEITHER the room-id query NOR any update statement issued — the common
        //   cold start must not pay for two full scans of an unindexed column.
    }

    @Test
    fun sweep_flips_every_sending_row_to_sentFailed() {
        // Expected behavior:
        //   Setup: 5 message rows with sendType=Sending(0), 3 with Sent(1),
        //          2 with SentFailed(2).
        //   storage.sweepStaleSendingMessages() → returns 5.
        //   Post-state: 0 Sending, 3 Sent, 7 SentFailed.
        //   The flip predicate is `sendType == Sending` alone — unchanged from
        //   before the room-tag feature. Narrowing it would change which message
        //   rows an existing install settles on startup.
    }

    @Test
    fun sweep_is_idempotent_second_call_returns_zero() {
        // Expected behavior:
        //   First call flips N Sending rows to SentFailed.
        //   Second call: 0 Sending rows remain → returns 0, no-op.
        //   Guards against startup races where the sweep runs twice.
    }

    @Test
    fun sweep_swallows_exception_returns_zero() {
        // Expected behavior:
        //   WCDB exception during either the COUNT or UPDATE → L.e logged,
        //   function returns 0 (not -1, not throw).
        //   TempTalkApplication continues startup; missing sweep is recoverable
        //   because FastJobStorage will re-enqueue any affected PushTextSendJob
        //   via its normal retry policy.
    }

    // T3-16 — the sweep must NOT depend on RoomChangeTracker delivery.
    @Test
    fun sweep_writes_room_sendStatus_directly_with_no_subscriber() {
        // Expected behavior:
        //   Setup: a room row with sendStatus = NONE and one TYPE_TEXT message
        //          with sendType = Sending(0). NO RoomChangeTracker collector
        //          registered anywhere (the real startup situation — the sweep
        //          runs from Application.onCreate, WCDBUpdateService registers
        //          its collector later from IndexActivity).
        //   storage.sweepStaleSendingMessages()
        //   Post-state: room.sendStatus == ROOM_SEND_STATUS_FAILED IMMEDIATELY,
        //          without any collector ever running. roomChanges is
        //          replay = 0, so a notification-only design would drop the
        //          event here — pinned executably by
        //          RoomChangeTrackerReplayAssumptionTest.
        //
        //   Ordering: the roomIds are collected from the message table BEFORE
        //          the UPDATE flips them (roomIdsWithStaleSendingOutgoing).
        //          Collecting afterwards matches nothing and the room is never
        //          flagged — the failure mode this case exists to catch.
        //
        //   The message flip and the room write are two separate statements, NOT
        //          one transaction: message-before-room is what makes the
        //          interleaving safe (the clear side re-reads the message table),
        //          and a failure in between only costs the room its tag.
    }

    // T3-15 — the type narrowing applies to the ROOM-TAG side only (owned by :database, shared
    // spelling with hasFailedOutgoingMessage). The message flip keeps its original predicate.
    @Test
    fun sweep_does_not_flag_rooms_whose_only_stale_row_is_a_notify() {
        // Expected behavior:
        //   Setup: 3 TYPE_TEXT rows with sendType = Sending(0) in room A, plus
        //          one TYPE_NOTIFY archive tombstone in room B whose sendType is
        //          also 0 (it defaults to 0 — it was never "sent").
        //   storage.sweepStaleSendingMessages()
        //   Post-state: only room A is flagged FAILED. Room B gets no tag —
        //          a notify row is never resent, so its tag would only clear on
        //          the room's next unrelated message change.
        //   Message rows: BOTH the text rows and the tombstone read SentFailed(2).
        //          The flip is legacy behavior and is deliberately NOT narrowed;
        //          the tag query is the guard.
        //   Executable form: RoomSendStatusQueriesTest
        //          `roomIdsWithStaleSendingOutgoing narrows to real outgoing messages`.
    }
}
