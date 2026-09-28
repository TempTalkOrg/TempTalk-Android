package com.difft.android.migration.v2.transfer

import app.cash.turbine.test
import com.difft.android.p2p.engine.model.QrPayload
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * I5 T-SM1 — [MigrationTransferStateHolder] state-machine transitions + re-attach (design §7).
 *
 * Pure-JVM (no Robolectric): the holder is a process-singleton `StateFlow` pair with no Android types.
 * Asserts (a) the ordered transition sequence the FGS writes, and (b) RE-ATTACH — a LATE collector sees
 * the latest value immediately (StateFlow replays), which is exactly how a recreated VM re-binds to an
 * in-flight transfer after Activity death.
 */
class MigrationTransferStateHolderTest {

    @Before
    fun setUp() = MigrationTransferStateHolder.reset()

    @After
    fun tearDown() = MigrationTransferStateHolder.reset()

    @Test
    fun `T-SM1a — ordered receiver transitions land in Promoted`() = runTest {
        MigrationTransferStateHolder.state.test {
            assertEquals(TransferState.Idle, awaitItem(), "starts Idle after reset")

            MigrationTransferStateHolder.update(TransferState.Starting)
            assertEquals(TransferState.Starting, awaitItem())

            MigrationTransferStateHolder.update(TransferState.WaitingForPeer("10.0.0.2", 53317, "123456"))
            assertIs<TransferState.WaitingForPeer>(awaitItem())

            MigrationTransferStateHolder.update(TransferState.Transferring(TransferState.Role.RECEIVER, -1f))
            assertIs<TransferState.Transferring>(awaitItem())

            MigrationTransferStateHolder.update(TransferState.Promoting)
            assertEquals(TransferState.Promoting, awaitItem())

            MigrationTransferStateHolder.update(TransferState.Promoted)
            assertEquals(TransferState.Promoted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(TransferState.Promoted, MigrationTransferStateHolder.state.value)
    }

    @Test
    fun `T-SM1b — a late collector sees the latest state (re-attach)`() = runTest {
        // Write an in-flight state BEFORE any collector attaches (mimics Activity death mid-transfer).
        MigrationTransferStateHolder.update(TransferState.Transferring(TransferState.Role.RECEIVER, 0.4f))

        // A NEW collector (recreated VM) attaches AFTER the write — must immediately see the latest.
        MigrationTransferStateHolder.state.test {
            val first = awaitItem()
            assertIs<TransferState.Transferring>(first)
            assertEquals(0.4f, first.progress, "late collector replays the in-flight Transferring(0.4f)")
            assertEquals(TransferState.Role.RECEIVER, first.role)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `qrPayload re-attach — late collector replays the published QR and reset clears it`() = runTest {
        val payload = QrPayload(ip = "10.0.0.2", port = 53317, fp = "fp", pin = "654321", alias = "rx")
        MigrationTransferStateHolder.publishQr(payload)

        MigrationTransferStateHolder.qrPayload.test {
            assertEquals(payload, awaitItem(), "late collector replays the latest QR payload")
            cancelAndIgnoreRemainingEvents()
        }

        MigrationTransferStateHolder.reset()
        assertNull(MigrationTransferStateHolder.qrPayload.value, "reset clears the QR payload")
        assertEquals(TransferState.Idle, MigrationTransferStateHolder.state.value, "reset returns to Idle")
    }

    @Test
    fun `isTerminal classifies terminal vs in-flight states`() {
        assertEquals(true, TransferState.Promoted.isTerminal())
        assertEquals(true, TransferState.Sent.isTerminal())
        assertEquals(true, TransferState.Cancelled.isTerminal())
        assertEquals(true, TransferState.Failed("x").isTerminal())
        assertEquals(false, TransferState.Idle.isTerminal())
        assertEquals(false, TransferState.Starting.isTerminal())
        assertEquals(false, TransferState.WaitingForPeer("ip", 1, "p").isTerminal())
        assertEquals(false, TransferState.Transferring(TransferState.Role.SENDER, 0.1f).isTerminal())
        assertEquals(false, TransferState.Promoting.isTerminal())
        assertEquals(false, TransferState.AwaitingVerdict.isTerminal())
    }
}
