package com.difft.android.migration.v2.transfer.login

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.difft.android.R
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.TransferState
import com.difft.android.migration.v2.transfer.send.MigrationFailureKind
import com.difft.android.migration.v2.transfer.send.classifyFailure
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import com.difft.android.test.TestDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GAP 4 (re-checked for I5) — [MigrationP2pReceiveViewModel] surfaces a handshake-gate reject to the UI.
 *
 * I5 moved the transfer scope onto the FGS, so the VM no longer drives a controller directly: it
 * RE-ATTACHES by collecting [MigrationTransferStateHolder] and mapping the role-agnostic
 * [TransferState] into the UI-shaped [MigrationP2pReceiveState]. The GAP-1 invariant is preserved
 * through that mapping: a `ServerEvent.Error` reaches the holder as [TransferState.Failed] (written by
 * the FGS's `onReceiverServerEvent`), and the VM maps it to [MigrationP2pReceiveState.Error] carrying
 * the typed detail — exactly what the screen's reason→string mapping resolves.
 *
 * The holder is the single writer's surface; here the test plays the writer (the FGS) to prove the
 * VM's read/map side. No socket / cert / gate is stood up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationP2pReceiveViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val account = "+12025550100"

    @Before
    fun setUp() = MigrationTransferStateHolder.reset()

    @After
    fun tearDown() = MigrationTransferStateHolder.reset()

    private fun newVm() =
        MigrationP2pReceiveViewModel(ApplicationProvider.getApplicationContext<Application>(), account)

    /** The code page is up from the first frame; the code fills in once the listener binds — no preparing page in between. */
    @Test
    fun `device-to-device opens on the code page, same device and Desktop scan on preparing`() = runTest {
        assertEquals(MigrationP2pReceiveState.WaitingForPeer(qrBitmap = null), newVm().state.value)
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(MigrationP2pReceiveState.Starting, MigrationP2pReceiveViewModel(context, account, sameDevice = true).state.value)
        assertEquals(MigrationP2pReceiveState.Starting, MigrationP2pReceiveViewModel(context, account, scanDesktop = true).state.value)
    }

    @Test
    fun `holder Failed (gate reject) maps to Error state carrying the reject reason`() = runTest {
        val vm = newVm()

        vm.state.test {
            assertEquals(MigrationP2pReceiveState.WaitingForPeer(qrBitmap = null), awaitItem())

            // QR up → WaitingForPeer (the FGS publishes the payload + state; here we play that writer).
            MigrationTransferStateHolder.publishQr(
                QrPayload(ip = "192.168.1.5", port = 53317, fp = "9f".repeat(32), pin = "123456", alias = "rx"),
            )
            MigrationTransferStateHolder.update(TransferState.WaitingForPeer("192.168.1.5", 53317, "123456"))
            assertTrue(awaitItem() is MigrationP2pReceiveState.WaitingForPeer)

            // The gate reject arrives as a ServerEvent.Error → FGS writes TransferState.Failed(detail).
            val detail = "403 forbidden: ${MigrationRejectReason.ENV_MISMATCH.wire}"
            MigrationTransferStateHolder.update(TransferState.Failed(detail))

            val errorState = awaitItem()
            assertTrue(errorState is MigrationP2pReceiveState.Error)
            assertEquals(detail, errorState.reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** T34 — the two reverse-pairing waits reach the screen as their own pages. */
    @Test
    fun `the reverse-pairing waits map one to one onto the receive states`() = runTest {
        val vm = newVm()

        MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConfirm("desk"))
        assertEquals(MigrationP2pReceiveState.AwaitingDesktopConfirm("desk"), vm.state.value)

        MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConnect("desk", 30_000L))
        assertEquals(MigrationP2pReceiveState.AwaitingDesktopConnect("desk"), vm.state.value)
    }

    /**
     * T45 — a committed-but-unconfirmed import must NOT look promoted: the Activity's in-process
     * sign-in hangs off [MigrationP2pReceiveState.Promoted], and the promote tail is not proven done.
     */
    @Test
    fun `a committed but unconfirmed import is its own state, never Promoted`() = runTest {
        val vm = newVm()

        MigrationTransferStateHolder.update(TransferState.CommittedUnconfirmed)
        assertEquals(MigrationP2pReceiveState.CommittedUnconfirmed, vm.state.value)
    }

    /** T34 — a code that is not a computer export code never reaches the service. */
    @Test
    fun `a scan that is not an export code is refused with a toast`() = runTest {
        val vm = newVm()
        vm.effects.test {
            vm.onIntent(MigrationP2pReceiveIntent.PeerQrScanned("not-a-migration-code"))
            val effect = awaitItem()
            assertTrue(effect is MigrationP2pReceiveEffect.Toast)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Error reason mapping resolves the gate rejects to the shared failure kinds`() {
        // Directly assert the screen's deterministic reason→kind mapping (the second half of GAP 1).
        assertEquals(MigrationFailureKind.DEVICE, classifyFailure("403 forbidden: ${MigrationRejectReason.ENV_MISMATCH.wire}"))
        assertEquals(MigrationFailureKind.DEVICE, classifyFailure("403 forbidden: ${MigrationRejectReason.ACCOUNT_MISMATCH.wire}"))
        assertEquals(MigrationFailureKind.UPDATE_NEEDED, classifyFailure("403 forbidden: ${MigrationRejectReason.VERSION_INCOMPATIBLE.wire}"))
        // Any non-typed reason (controller reject code / exception) falls back to the unknown sentence.
        assertEquals(MigrationFailureKind.UNKNOWN, classifyFailure("failed: SOME_OTHER_CODE"))
        assertEquals(R.string.migration_p3_reason_unknown, MigrationFailureKind.UNKNOWN.reasonRes)
    }
}
