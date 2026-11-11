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

    




    private fun rawQr(transferKey: String? = "present", dir: String? = null): String =
        com.difft.android.p2p.android.qr.QrPayloadCodec.encodeText(
            com.difft.android.p2p.engine.model.QrPayload(
                ip = "192.168.1.7",
                port = 53317,
                fp = "9f".repeat(32),
                pin = "123456",
                alias = "Pixel",
                transferKey = transferKey?.let { "WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo=" },
                dir = dir,
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
        
        
        assertEquals("Pixel", state.alias)
        
        
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
    fun `a computer export code stays Idle and points to the sign-in page, keyed or not`() = runTest {
        val hint = ApplicationProvider.getApplicationContext<Application>()
            .getString(com.difft.android.chat.R.string.scan_computer_export_code_hint)
        for (key in listOf<String?>("present", null)) {
            val vm = newVm()
            vm.effects.test {
                vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = key, dir = "download")))
                assertEquals(MigrationP2pSendEffect.Toast(hint), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(MigrationP2pSendState.Idle, vm.state.value)
        }
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
    fun `the receiver-only reverse-pairing and committed states leave the send state alone`() = runTest {
        val vm = newVm()
        vm.state.test {
            assertEquals(MigrationP2pSendState.Idle, awaitItem())
            MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConfirm("desk"))
            MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConnect("desk", 30_000L))
            MigrationTransferStateHolder.update(TransferState.CommittedUnconfirmed)
            expectNoEvents()
            assertEquals(MigrationP2pSendState.Idle, vm.state.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `late collector re-attaches to in-flight Transferring (holder replay)`() = runTest {
        
        
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

    





    @Test
    fun `Send neither resets nor shadows a concurrent in-flight receive`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val vm = newVm()
            vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = "tk-secret")))
            
            val receiving = TransferState.Transferring(TransferState.Role.RECEIVER, 0.7f)
            MigrationTransferStateHolder.update(receiving)

            vm.onIntent(MigrationP2pSendIntent.Send)

            
            assertEquals(receiving, MigrationTransferStateHolder.state.value)
            verify(exactly = 0) {
                MigrationTransferService.startSender(any(), any(), any(), any())
            }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    



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

    



    @Test
    fun `Send resets a stale terminal holder before starting`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any()) } returns Unit
        try {
            val vm = newVm()
            vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr(transferKey = "tk-secret")))
            MigrationTransferStateHolder.update(TransferState.Failed("prev send failed"))

            vm.onIntent(MigrationP2pSendIntent.Send)

            
            assertEquals(TransferState.Idle, MigrationTransferStateHolder.state.value)
            verify(exactly = 1) {
                MigrationTransferService.startSender(any(), any(), any())
            }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }

    
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

    
    private fun multiAddressPayload() = com.difft.android.p2p.engine.model.QrPayload(
        ip = "192.168.1.7",
        ips = listOf("192.168.1.7", "192.168.43.1"),
        port = 53317,
        fp = "9f".repeat(32),
        pin = "123456",
        alias = "Pixel",
        transferKey = "WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo=",
    )

    




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

    
    @Test
    fun `RefreshPairing is a no-op outside the credential handoff`() = runTest {
        val vm = newVm()
        vm.effects.test {
            vm.onIntent(MigrationP2pSendIntent.RefreshPairing)
            
            
            vm.onIntent(MigrationP2pSendIntent.Close)
            assertEquals(MigrationP2pSendEffect.Finish, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    
    private fun handoffVm() = MigrationP2pSendViewModel(
        ApplicationProvider.getApplicationContext<Application>(),
        credentialHandoff = true,
    )

    
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

    

    
    @Test
    fun `a re-checked verdict is settled, not just dropped`() = runTest {
        val session = com.difft.android.migration.v2.transfer.SenderSessionRef("10.0.0.2", 53317, "fp", "s-1", "t", listOf("status-v1"))
        val settled = mutableListOf<Pair<String, com.difft.android.migration.v2.transfer.Verdict>>()
        var receiptCleared = false
        val vm = MigrationP2pSendViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            loadPendingVerdict = { com.difft.android.migration.v2.transfer.PendingVerdict(session, 1L) },
            clearPendingVerdict = { receiptCleared = true },
            probeVerdict = { com.difft.android.migration.v2.transfer.Verdict.Ended("failed") },
            settleVerdict = { s, verdict ->
                settled += s.sessionId to verdict
                true
            },
        )

        assertIs<MigrationP2pSendState.Failed>(vm.state.value)
        assertEquals(listOf("s-1" to com.difft.android.migration.v2.transfer.Verdict.Ended("failed")), settled.toList())
        assertFalse(receiptCleared, "the settlement clears the receipt after the pause")
    }

    @Test
    fun `a re-checked verdict the pause did not take stays unconfirmed`() = runTest {
        val session = com.difft.android.migration.v2.transfer.SenderSessionRef("10.0.0.2", 53317, "fp", "s-1", "t", listOf("status-v1"))
        val vm = MigrationP2pSendViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            loadPendingVerdict = { com.difft.android.migration.v2.transfer.PendingVerdict(session, 1L) },
            probeVerdict = { com.difft.android.migration.v2.transfer.Verdict.Succeeded },
            settleVerdict = { _, _ -> false },
        )

        assertEquals(
            MigrationP2pSendState.Unconfirmed(MigrationP2pSendState.Unconfirmed.Phase.UNREACHABLE),
            vm.state.value,
        )
    }

    @Test
    fun `a phone-to-phone send tells the FGS to pause messaging`() = runTest {
        mockkObject(MigrationTransferService.Companion)
        every { MigrationTransferService.startSender(any(), any(), any(), any(), any(), any()) } returns Unit
        try {
            val vm = MigrationP2pSendViewModel(ApplicationProvider.getApplicationContext<Application>(), deviceReplacement = true)
            vm.onIntent(MigrationP2pSendIntent.QrScanned(rawQr()))
            vm.onIntent(MigrationP2pSendIntent.Send)

            verify(exactly = 1) { MigrationTransferService.startSender(any(), any(), any(), any(), any(), true) }
        } finally {
            unmockkObject(MigrationTransferService.Companion)
        }
    }
}
