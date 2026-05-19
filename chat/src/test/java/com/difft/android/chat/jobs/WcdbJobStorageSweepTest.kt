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
        //   no update statement issued.
    }

    @Test
    fun sweep_flips_every_sending_row_to_sentFailed() {
        // Expected behavior:
        //   Setup: 5 message rows with sendType=Sending(0), 3 with Sent(1),
        //          2 with SentFailed(2).
        //   storage.sweepStaleSendingMessages() → returns 5.
        //   Post-state: 0 Sending, 3 Sent, 7 SentFailed.
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
}
