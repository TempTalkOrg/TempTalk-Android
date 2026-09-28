package com.difft.android.migration.v2.transfer.send

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.difft.android.migration.v2.transfer.MigrationTransferService
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.TransferState
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import com.difft.android.test.TestDispatcherRule
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [MigrationP2pSendViewModel] — the production OLD-device SEND VM state machine + re-attach. Mirrors
 * `MigrationP2pReceiveViewModelTest`: the holder is the single writer's surface; the test plays the
 * writer (the FGS) to prove the VM's read/map side. No socket / cert / gate / FGS is stood up — the VM
 * only collects the holder + emits effects.
 *
 * R3 invariant asserted in [scanned valid QR maps to Ready without surfacing transferKey].
 *
 * DEFERRED (T-SS1/T-SS2 screenshot tier): Roborazzi baselines for `MigrationP2pSendScreen` (all states,
 * light+dark) and `MigrationMainScreen` (p2pSendOffered true/false) are NOT recorded in this pass. RISK:
 * a visual regression on the debug-only P2P send screen would not be caught by CI until baselines are
 * recorded via `recordRoborazzi*`. Deferred because the screen is debug-only + flag-gated (no release
 * surface) and the baselines must be recorded on the agreed reference device before they can gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationP2pSendViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @Before
    fun setUp() = MigrationTransferStateHolder.reset()

    @After
    fun tearDown() = MigrationTransferStateHolder.reset()

    private fun newVm() =
        MigrationP2pSendViewModel(ApplicationProvider.getApplicationContext<Application>())

    /**
     * The scanned code's text, built by the real codec. The wire carries a 32-byte key, so any
     * non-null [transferKey] stands for "a key is present" and the fixed key below is what is encoded;
     * null is the no-key reject case.
     */
    private fun rawQr(transferKey: String? = "present"): String =
        com.difft.android.p2p.android.qr.QrPayloadCodec.encodeText(
            com.difft.android.p2p.engine.model.QrPayload(
                ip = "192.168.1.7",
                port = 53317,
                fp = "9f".repeat(32),
                pin = "123456",
                alias = "Pixel",
                transferKey = transferKey?.let { "WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo=" },
            ),
        )

    @Test
    fun `LaunchScan moves to Scanning and emits LaunchScanActivity`() = runTest {
        val vm = newVm()
        vm.effects.test {
            vm.onIntent(MigrationP2pSendIntent.LaunchScan)
            assertEquals(MigrationP2pSendEffect.LaunchScanActivity, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(MigrationP2pSendState.Scanning, vm.state.value)
    }

    /** A Desktop-link failure is restarted from the computer's new code, never by re-sending the old payload. */
    @Test
    fun `RescanLink leaves for the scanner without touching the transfer`() = runTest {
        val vm = newVm()
        vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr()))
        vm.effects.test {
            vm.onIntent(MigrationP2pSendIntent.RescanLink)
            assertEquals(MigrationP2pSendEffect.RescanLink, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(vm.sendStarted)
    }

    @Test
    fun `scanned valid QR maps to Ready without surfacing transferKey`() = runTest {
        val vm = newVm()
        vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = "tk-secret")))

        val state = vm.state.value
        assertIs<MigrationP2pSendState.Ready>(state)
        // The peer's device name is the only thing the user needs to confirm before sending; the
        // address and pairing pin stay in the engine and are deliberately absent from the UI state.
        assertEquals("Pixel", state.alias)
        // R3: the transferKey must NEVER appear in any rendered state field — and neither should the
        // transport details, now that the screen does not show them.
        assertTrue("tk-secret" !in state.toString())
        assertTrue("192.168.1.7" !in state.toString())
        assertTrue("53317" !in state.toString())
    }

    @Test
    fun `scanned QR with no transferKey stays Idle and toasts`() = runTest {
        val vm = newVm()
        vm.effects.test {
            vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = null)))
            assertIs<MigrationP2pSendEffect.Toast>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(MigrationP2pSendState.Idle, vm.state.value)
    }

    @Test
    fun `undecodable QR stays Idle and toasts`() = runTest {
        val vm = newVm()
        vm.effects.test {
            vm.onIntent(MigrationP2pSendIntent.QrScanned("not-a-qr-json"))
            assertIs<MigrationP2pSendEffect.Toast>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(MigrationP2pSendState.Idle, vm.state.value)
    }

    @Test
    fun `Send before scanning toasts and does not start a transfer`() = runTest {
        val vm = newVm()
        vm.effects.test {
            vm.onIntent(MigrationP2pSendIntent.Send)
            assertIs<MigrationP2pSendEffect.Toast>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // No FGS start → the holder is untouched (still Idle).
        assertEquals(TransferState.Idle, MigrationTransferStateHolder.state.value)
    }

    @Test
    fun `holder Transferring(indeterminate) maps to Transferring state`() = runTest {
        val vm = newVm()
        vm.state.test {
            assertEquals(MigrationP2pSendState.Idle, awaitItem())
            MigrationTransferStateHolder.update(
                TransferState.Transferring(TransferState.Role.SENDER, -1f),
            )
            assertEquals(MigrationP2pSendState.Transferring(-1f), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holder AwaitingVerdict maps to AwaitingVerdict`() = runTest {
        val vm = newVm()
        vm.state.test {
            assertEquals(MigrationP2pSendState.Idle, awaitItem())
            MigrationTransferStateHolder.update(TransferState.AwaitingVerdict)
            assertEquals(MigrationP2pSendState.AwaitingVerdict, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holder Sent maps to Done`() = runTest {
        val vm = newVm()
        vm.state.test {
            assertEquals(MigrationP2pSendState.Idle, awaitItem())
            MigrationTransferStateHolder.update(TransferState.Sent)
            assertEquals(MigrationP2pSendState.Done, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holder Failed (gate reject) maps to Failed carrying the reason`() = runTest {
        val vm = newVm()
        val reason = "prepare-upload rejected: ${MigrationRejectReason.ACCOUNT_MISMATCH.wire}"
        vm.state.test {
            assertEquals(MigrationP2pSendState.Idle, awaitItem())
            MigrationTransferStateHolder.update(TransferState.Failed(reason))
            val failed = awaitItem()
            assertIs<MigrationP2pSendState.Failed>(failed)
            assertEquals(reason, failed.reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holder Cancelled maps to Cancelled`() = runTest {
        val vm = newVm()
        vm.state.test {
            assertEquals(MigrationP2pSendState.Idle, awaitItem())
            MigrationTransferStateHolder.update(TransferState.Cancelled)
            assertEquals(MigrationP2pSendState.Cancelled, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `late collector re-attaches to in-flight Transferring (holder replay)`() = runTest {
        // Simulate an Activity recreated mid-send: the FGS already wrote Transferring before the VM's
        // collector starts. StateFlow replays the latest value → the freshly-created VM re-binds.
        MigrationTransferStateHolder.update(
            TransferState.Transferring(TransferState.Role.SENDER, 0.5f),
        )
        val vm = newVm()
        vm.state.test {
            assertEquals(MigrationP2pSendState.Transferring(0.5f), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unticking attachments sends the FGS the choice to leave them behind`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val vm = newVm()
            vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = "tk-secret")))
            val ready = assertIs<MigrationP2pSendState.Ready>(vm.state.value)
            assertEquals(true, ready.includeAttachments, "attachments travel unless the user says otherwise")

            vm.onIntent(MigrationP2pSendIntent.SetIncludeAttachments(false))
            assertEquals(false, assertIs<MigrationP2pSendState.Ready>(vm.state.value).includeAttachments)

            vm.onIntent(MigrationP2pSendIntent.Send)

            verify(exactly = 1) {
                MigrationTransferService.startSender(any(), any(), false)
            }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /**
     * G1 / T6 — the POSITIVE Send-after-scan path: a valid scan (Ready) then `Send` must start the FGS
     * with the SCANNED raw QR. The service
     * companion is mocked so the real `startForegroundService` is never dispatched.
     */
    @Test
    fun `Send after a valid scan starts the FGS with the raw QR and PRODUCTION mode`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val vm = newVm()
            val raw = rawQr(transferKey = "tk-secret")
            vm.onIntent(MigrationP2pSendIntent.QrScanned(raw))
            assertIs<MigrationP2pSendState.Ready>(vm.state.value)

            vm.onIntent(MigrationP2pSendIntent.Send)

            verify(exactly = 1) {
                MigrationTransferService.startSender(any(), raw, any())
            }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /** G2 / T10 — `Cancel` tears down the in-flight FGS via [MigrationTransferService.cancel]. */
    @Test
    fun `Cancel calls MigrationTransferService cancel`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.cancel(any()) } returns Unit
        try {
            newVm().onIntent(MigrationP2pSendIntent.Cancel)
            verify(exactly = 1) { MigrationTransferService.cancel(any()) }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /**
     * M1 — `Send` must NOT clobber a CONCURRENT in-flight RECEIVE's holder state, and must not start
     * a send that would shadow it either: the service would ignore the start (F2), leaving this page
     * to mirror the receive's progress as if it were its own. The receive's state is preserved and no
     * start is dispatched.
     */
    @Test
    fun `Send neither resets nor shadows a concurrent in-flight receive`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val vm = newVm()
            vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = "tk-secret")))
            // A receive is in flight on the SAME shared holder (non-terminal, receiver-owned).
            val receiving = TransferState.Transferring(TransferState.Role.RECEIVER, 0.7f)
            MigrationTransferStateHolder.update(receiving)

            vm.onIntent(MigrationP2pSendIntent.Send)

            // The pre-reset was skipped → the receiver's in-flight state survives (NOT wiped to Idle).
            assertEquals(receiving, MigrationTransferStateHolder.state.value)
            verify(exactly = 0) {
                MigrationTransferService.startSender(any(), any(), any(), any())
            }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /**
     * An idle listener (a QR page backed out of, never scanned) is the service's to evict for a
     * deliberate new start: the start goes out and the holder is left for the service to reset.
     */
    @Test
    fun `Send lets the service evict an idle listener`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val vm = newVm()
            vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = "tk-secret")))
            val listening = TransferState.WaitingForPeer("10.0.0.2", 53317, "123456")
            MigrationTransferStateHolder.update(listening)

            vm.onIntent(MigrationP2pSendIntent.Send)

            assertEquals(listening, MigrationTransferStateHolder.state.value)
            verify(exactly = 1) {
                MigrationTransferService.startSender(any(), any(), any())
            }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /**
     * M1 / F3 — when the holder holds a STALE TERMINAL from a previous send, `Send` DOES reset it first
     * (closing the replay window) before dispatching the FGS start.
     */
    @Test
    fun `Send resets a stale terminal holder before starting`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val vm = newVm()
            vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = "tk-secret")))
            MigrationTransferStateHolder.update(TransferState.Failed("prev send failed"))

            vm.onIntent(MigrationP2pSendIntent.Send)

            // Stale terminal → pre-reset fired → holder back to Idle before the (mocked) FGS start.
            assertEquals(TransferState.Idle, MigrationTransferStateHolder.state.value)
            verify(exactly = 1) {
                MigrationTransferService.startSender(any(), any(), any())
            }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /** The claim got no answer: the two phones are not on one network, and the page says so before start. */
    @Test
    fun `a code whose claim goes unanswered fails as unreachable instead of offering start`() = runTest {
        val raw = com.difft.android.p2p.android.qr.QrPayloadCodec.encodeText(multiAddressPayload().copy(ips = emptyList()))
        val vm = MigrationP2pSendViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            claimPairing = { com.difft.android.p2p.android.client.SenderHttpClient.PairingClaim.UNREACHABLE },
        )

        vm.onIntent(MigrationP2pSendIntent.QrScanned(raw))

        val failed = assertIs<MigrationP2pSendState.Failed>(vm.state.value)
        assertEquals(MigrationFailureKind.CONNECTION, classifyFailure(failed.reason))
    }

    /** The receiver took the claim: the confirmation page opens as before. */
    @Test
    fun `an accepted claim opens the confirmation page`() = runTest {
        val raw = com.difft.android.p2p.android.qr.QrPayloadCodec.encodeText(multiAddressPayload().copy(ips = emptyList()))
        val vm = MigrationP2pSendViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            claimPairing = { com.difft.android.p2p.android.client.SenderHttpClient.PairingClaim.ACCEPTED },
        )

        vm.onIntent(MigrationP2pSendIntent.QrScanned(raw))

        assertIs<MigrationP2pSendState.Ready>(vm.state.value)
    }

    /** A code with several addresses, built by the real codec; the fixed key stands in for the receiver's. */
    private fun multiAddressPayload() = com.difft.android.p2p.engine.model.QrPayload(
        ip = "192.168.1.7",
        ips = listOf("192.168.1.7", "192.168.43.1"),
        port = 53317,
        fp = "9f".repeat(32),
        pin = "123456",
        alias = "Pixel",
        transferKey = "WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo=",
    )

    /**
     * Several addresses advertised: the resolver picks the one that answered, and the FGS is
     * started with a code that names only that address — the claim, the upload and the session
     * record then agree on it without racing again.
     */
    @Test
    fun `a multi-address code is settled by the resolver and the FGS gets the settled code`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val payload = multiAddressPayload()
            val settled = payload.copy(ip = "192.168.43.1", ips = listOf("192.168.43.1"))
            val vm = MigrationP2pSendViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                resolveEndpoint = { candidate ->
                    assertEquals(payload.ips, candidate.ips)
                    settled
                },
            )
            vm.onIntent(MigrationP2pSendIntent.QrScanned(com.difft.android.p2p.android.qr.QrPayloadCodec.encodeText(payload)))
            assertIs<MigrationP2pSendState.Ready>(vm.state.value)

            vm.onIntent(MigrationP2pSendIntent.Send)

            verify(exactly = 1) {
                MigrationTransferService.startSender(any(), com.difft.android.p2p.android.qr.QrPayloadCodec.encodeText(settled), any())
            }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /** No address answered: the code is kept as scanned and the ordinary connection reports the failure. */
    @Test
    fun `a multi-address code whose resolver finds nothing keeps the scanned code`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val raw = com.difft.android.p2p.android.qr.QrPayloadCodec.encodeText(multiAddressPayload())
            val vm = MigrationP2pSendViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                resolveEndpoint = { null },
            )
            vm.onIntent(MigrationP2pSendIntent.QrScanned(raw))
            assertIs<MigrationP2pSendState.Ready>(vm.state.value)

            vm.onIntent(MigrationP2pSendIntent.Send)

            verify(exactly = 1) { MigrationTransferService.startSender(any(), raw, any()) }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /**
     * The same-device pairing payload is session-scoped: once the new app's receiver went away, the
     * retry has to ask for a new one instead of re-sending the dead code.
     */
    @Test
    fun `handoff retry asks the new app for a fresh pairing instead of re-sending`() = runTest {
        val vm = handoffVm()
        vm.onIntent(MigrationP2pSendIntent.QrScanned(loopbackQr()))
        MigrationTransferStateHolder.update(TransferState.Failed("prev send failed"))
        assertIs<MigrationP2pSendState.Failed>(vm.state.value)

        vm.effects.test {
            vm.onIntent(MigrationP2pSendIntent.RefreshPairing)
            assertEquals(MigrationP2pSendEffect.RequestFreshPairing, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The fresh payload re-pairs and restarts from the failure screen without a second tap. */
    @Test
    fun `a fresh pairing payload restarts the send from a failed state`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val vm = handoffVm()
            vm.onIntent(MigrationP2pSendIntent.QrScanned(loopbackQr(pin = "111111")))
            MigrationTransferStateHolder.update(TransferState.Failed("prev send failed"))
            assertIs<MigrationP2pSendState.Failed>(vm.state.value)

            val fresh = loopbackQr(pin = "222222")
            vm.onFreshPairing(fresh)

            assertTrue(vm.sendStarted)
            verify(exactly = 1) { MigrationTransferService.startSender(any(), fresh, any(), any()) }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    /** Cross-device retries re-scan a code, so there is no peer to ask and the intent does nothing. */
    @Test
    fun `RefreshPairing is a no-op outside the credential handoff`() = runTest {
        val vm = newVm()
        vm.effects.test {
            vm.onIntent(MigrationP2pSendIntent.RefreshPairing)
            // Close is queued on the same channel right after, so its effect arriving first proves
            // RefreshPairing emitted nothing.
            vm.onIntent(MigrationP2pSendIntent.Close)
            assertEquals(MigrationP2pSendEffect.Finish, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The same-device hand-over sender: no claim, no auth to prove, as the trampoline path builds it. */
    private fun handoffVm() = MigrationP2pSendViewModel(
        ApplicationProvider.getApplicationContext<Application>(),
        credentialHandoff = true,
    )

    /** A pairing payload as the new app hands it over on this device: loopback, with a key. */
    private fun loopbackQr(pin: String = "123456"): String =
        com.difft.android.p2p.android.qr.QrPayloadCodec.encodeText(
            com.difft.android.p2p.engine.model.QrPayload(
                ip = com.difft.android.base.migration.PeerApps.LOOPBACK_IP,
                port = 53317,
                fp = "9f".repeat(32),
                pin = pin,
                alias = "Quicall",
                transferKey = "WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo=",
            ),
        )
}
