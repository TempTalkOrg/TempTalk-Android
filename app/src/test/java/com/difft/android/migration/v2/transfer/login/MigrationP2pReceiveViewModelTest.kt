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

            
            MigrationTransferStateHolder.publishQr(
                QrPayload(ip = "192.168.1.5", port = 53317, fp = "9f".repeat(32), pin = "123456", alias = "rx"),
            )
            MigrationTransferStateHolder.update(TransferState.WaitingForPeer("192.168.1.5", 53317, "123456"))
            assertTrue(awaitItem() is MigrationP2pReceiveState.WaitingForPeer)

            
            val detail = "403 forbidden: ${MigrationRejectReason.ENV_MISMATCH.wire}"
            MigrationTransferStateHolder.update(TransferState.Failed(detail))

            val errorState = awaitItem()
            assertTrue(errorState is MigrationP2pReceiveState.Error)
            assertEquals(detail, errorState.reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    
    @Test
    fun `the reverse-pairing waits map one to one onto the receive states`() = runTest {
        val vm = newVm()

        MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConfirm("desk"))
        assertEquals(MigrationP2pReceiveState.AwaitingDesktopConfirm("desk"), vm.state.value)

        MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConnect("desk", 30_000L))
        assertEquals(MigrationP2pReceiveState.AwaitingDesktopConnect("desk"), vm.state.value)
    }

    



    @Test
    fun `a committed but unconfirmed import is its own state, never Promoted`() = runTest {
        val vm = newVm()

        MigrationTransferStateHolder.update(TransferState.CommittedUnconfirmed)
        assertEquals(MigrationP2pReceiveState.CommittedUnconfirmed, vm.state.value)
    }

    
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
        
        assertEquals(MigrationFailureKind.DEVICE, classifyFailure("403 forbidden: ${MigrationRejectReason.ENV_MISMATCH.wire}"))
        assertEquals(MigrationFailureKind.DEVICE, classifyFailure("403 forbidden: ${MigrationRejectReason.ACCOUNT_MISMATCH.wire}"))
        assertEquals(MigrationFailureKind.UPDATE_NEEDED, classifyFailure("403 forbidden: ${MigrationRejectReason.VERSION_INCOMPATIBLE.wire}"))
        
        assertEquals(MigrationFailureKind.UNKNOWN, classifyFailure("failed: SOME_OTHER_CODE"))
        assertEquals(R.string.migration_p3_reason_unknown, MigrationFailureKind.UNKNOWN.reasonRes)
    }
}
