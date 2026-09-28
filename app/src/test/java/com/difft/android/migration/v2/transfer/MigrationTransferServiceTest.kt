package com.difft.android.migration.v2.transfer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.snapshot.BackupSnapshotter
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.restore.RestoreOrchestrator
import com.difft.android.migration.v2.handoff.MigrationHandoffCompleter
import com.difft.android.migration.v2.handoff.MigrationHandoffReconciler
import com.difft.android.migration.v2.transfer.send.HANDOFF_NOT_ACKNOWLEDGED
import com.difft.android.migration.v2.transfer.send.MigrationP2pSendController
import com.difft.android.p2p.engine.upload.UploadReplyHeaders
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.mockk.slot

/**
 * I5 T-SM1c — [MigrationTransferService] cancel + action plumbing (design §7).
 *
 * Strategy: the service is `@AndroidEntryPoint` and the `:app`
 * module registers no Hilt test runner, so `Robolectric.buildService(...).create()` can't drive the
 * Hilt-generated `onCreate`. Instead we construct the service via reflection (bypassing Hilt onCreate),
 * attach a base context, inject relaxed mocks into the `lateinit` fields, and exercise the test seams:
 *   - `ACTION_CANCEL` → holder = [TransferState.Cancelled] (no exception; children cancelled, teardown).
 *   - unknown action → safe no-op (stopSelf), holder untouched.
 *   - companion-constant alignment with [MigrationTransferNotificationHelper].
 *   - [MigrationTransferCancelReceiver] forwards the cancel broadcast without throwing.
 *
 * The happy-path receive/send drive real sockets + native WCDB (constructed inside `runReceiver`/
 * `runSender`), covered end-to-end by `P2pContainerFusionTest` / `RestoreOrchestratorStreamingTest`
 * and the on-device matrix — not feasible at this method-level JVM seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationTransferServiceTest {

    private lateinit var service: MigrationTransferService
    private lateinit var notificationHelper: MigrationTransferNotificationHelper

    @Before
    fun setUp() {
        MigrationTransferStateHolder.reset()

        notificationHelper = mockk(relaxed = true)
        every { notificationHelper.buildRunning(any()) } returns mockk(relaxed = true)

        service = MigrationTransferService::class.java
            .getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()

        val ctx: Application = ApplicationProvider.getApplicationContext()
        ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(service, ctx)

        setField("containerWriter", mockk<ContainerWriter>(relaxed = true))
        setField("containerReader", mockk<ContainerReader>(relaxed = true))
        setField("backupCrypto", mockk<BackupCrypto>(relaxed = true))
        setField("restoreOrchestrator", mockk<RestoreOrchestrator>(relaxed = true))
        setField("environmentHelper", mockk<EnvironmentHelper>(relaxed = true))
        setField("snapshotter", mockk<BackupSnapshotter>(relaxed = true))
        setField("notificationHelper", notificationHelper)
        setField("userManager", mockk<StorageBoundUserManager>(relaxed = true))
        setField("pendingVerdictStore", PendingVerdictStore(RecordingPreferencesDataStore()))
    }

    @After
    fun tearDown() {
        MigrationTransferStateHolder.reset()
        clearAllMocks()
        unmockkAll()
    }

    private fun setField(name: String, value: Any) {
        MigrationTransferService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(service, value)
    }

    private fun onStart(action: String?) {
        val intent = action?.let { Intent(it) }
        service.onStartCommand(intent, 0, 1)
    }

    // ── terminal-notification reason localization ────────────────────────────────────────────
    //
    // `TransferState.Failed.reason` is a DIAGNOSTIC string (a handshake wire token embedded in a 403
    // message, the local pairing-timeout marker, or an arbitrary transport error), so the mapping is
    // substring/equality based with a raw-string fallback — an unrecognized reason must still reach
    // the user rather than being swallowed into a generic message.

    @Test
    fun `known reject wire tokens map to the existing localized strings`() {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        assertEquals(
            ctx.getString(com.difft.android.R.string.migration_p3_reason_device),
            service.localizedFailureReason("403 forbidden: ${MigrationRejectReason.ENV_MISMATCH.wire}"),
        )
        assertEquals(
            ctx.getString(com.difft.android.R.string.migration_p3_reason_device),
            service.localizedFailureReason("prepare-upload rejected: ${MigrationRejectReason.ACCOUNT_MISMATCH.wire}"),
        )
        assertEquals(
            ctx.getString(com.difft.android.R.string.migration_m3_body),
            service.localizedFailureReason("403 forbidden: ${MigrationRejectReason.VERSION_INCOMPATIBLE.wire}"),
        )
    }

    @Test
    fun `the pairing-timeout marker maps to its own localized string`() {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        assertEquals(
            ctx.getString(com.difft.android.R.string.migration_p2p_notification_text_pairing_timeout),
            service.localizedFailureReason(MigrationTransferService.REASON_PAIRING_TIMEOUT),
        )
    }

    @Test
    fun `an unrecognized reason falls back to the raw diagnostic string`() {
        val raw = "java.net.SocketTimeoutException: connect timed out"
        assertEquals(raw, service.localizedFailureReason(raw), "the only diagnostic must not be swallowed")
    }

    @Test
    fun `ACTION_CANCEL sets Cancelled and tears down without throwing`() {
        // Pre-set an in-flight state so the cancel transition is observable.
        MigrationTransferStateHolder.update(TransferState.Transferring(TransferState.Role.RECEIVER, -1f))

        onStart(MigrationTransferService.ACTION_CANCEL)

        assertEquals(
            TransferState.Cancelled,
            MigrationTransferStateHolder.state.value,
            "ACTION_CANCEL must write the terminal Cancelled state",
        )
        assertTornDown()
    }

    /**
     * T-SM1c: cancel-from-each-in-flight-state. The design wants ACTION_CANCEL to land the holder on the
     * terminal Cancelled state AND tear the FGS down (stopForeground + stopSelf) regardless of which
     * non-terminal state the transfer was in when the user/notification cancelled.
     */
    @Test
    fun `ACTION_CANCEL from Starting writes Cancelled and tears down`() {
        MigrationTransferStateHolder.update(TransferState.Starting)
        onStart(MigrationTransferService.ACTION_CANCEL)
        assertEquals(TransferState.Cancelled, MigrationTransferStateHolder.state.value)
        assertTornDown()
    }

    @Test
    fun `ACTION_CANCEL from WaitingForPeer writes Cancelled and tears down`() {
        MigrationTransferStateHolder.update(TransferState.WaitingForPeer("10.0.0.2", 53317, "123456"))
        onStart(MigrationTransferService.ACTION_CANCEL)
        assertEquals(TransferState.Cancelled, MigrationTransferStateHolder.state.value)
        assertTornDown()
    }

    /** Robolectric `ShadowService` records `stopForeground` + `stopSelf` — assert the FGS torn down. */
    private fun assertTornDown() {
        val shadow = Shadows.shadowOf(service)
        assertTrue(shadow.isForegroundStopped, "ACTION_CANCEL must call stopForeground(REMOVE)")
        assertTrue(shadow.isStoppedBySelf, "ACTION_CANCEL must call stopSelf()")
    }

    @Test
    fun `unknown action is a safe no-op and leaves the holder untouched`() {
        MigrationTransferStateHolder.update(TransferState.Starting)
        onStart("com.difft.migration.p2p.SOME_UNKNOWN_ACTION")
        assertEquals(
            TransferState.Starting,
            MigrationTransferStateHolder.state.value,
            "unknown action must not mutate transfer state",
        )
    }

    @Test
    fun `sender start with an undecodable QR fails fast`() {
        val intent = Intent(MigrationTransferService.ACTION_START_SENDER).apply {
            putExtra(MigrationTransferService.EXTRA_QR_PAYLOAD, "not-a-qr-json")
        }
        service.onStartCommand(intent, 0, 1)

        val state = MigrationTransferStateHolder.state.value
        assertIs<TransferState.Failed>(state)
        assertEquals("invalid QR payload", state.reason)
    }

    @Test
    fun `companion constants stay aligned with the notification helper`() {
        assertEquals(
            MigrationTransferNotificationHelper.ACTION_CANCEL,
            MigrationTransferService.ACTION_CANCEL,
            "Service.ACTION_CANCEL must match the notification helper's cancel action",
        )
        assertEquals("com.difft.migration.p2p.ACTION_CANCEL", MigrationTransferService.ACTION_CANCEL)
        assertEquals("pairing timeout", MigrationTransferService.REASON_PAIRING_TIMEOUT)
        assertEquals(5 * 60 * 1000L, MigrationTransferService.PAIRING_TIMEOUT_MS)
    }

    @Test
    fun `MigrationTransferCancelReceiver forwards the cancel broadcast without throwing`() {
        val receiver = MigrationTransferCancelReceiver()
        val ctx: Application = ApplicationProvider.getApplicationContext()
        receiver.onReceive(ctx, Intent(MigrationTransferNotificationHelper.ACTION_CANCEL))
        // No Hilt root → startService is recorded but the service never starts; pass = no exception.
    }

    @Test
    fun `MigrationTransferCancelReceiver ignores unrelated actions`() {
        val receiver = MigrationTransferCancelReceiver()
        val ctx: Application = ApplicationProvider.getApplicationContext()
        receiver.onReceive(ctx, Intent("some.other.action"))
    }

    private val sendTarget = QrPayload(
        ip = "192.168.1.7", port = 53317, fp = "fp", pin = "123456", alias = "Pixel", transferKey = "tk",
    )

    /**
     * G3 / T-IT1 — `runSender(PRODUCTION)` selects the PRODUCTION controller
     * ([MigrationP2pSendController], real account/env) and writes [TransferState.Sent] on success. The
     * controller is intercepted with `mockkConstructor` so no native socket / WCDB is touched — the test
     * asserts the SELECTOR routes PRODUCTION to the production controller's `sendRealExport`.
     */
    @Test
    fun `runSender PRODUCTION selects the production controller and writes Sent on success`() = runTest {
        mockkConstructor(MigrationP2pSendController::class)
        coEvery {
            anyConstructed<MigrationP2pSendController>().sendRealExport(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns true
        try {
            service.runSender(sendTarget)

            assertEquals(
                TransferState.Sent,
                MigrationTransferStateHolder.state.value,
                "PRODUCTION send success must write Sent",
            )
            coVerify(exactly = 1) {
                anyConstructed<MigrationP2pSendController>().sendRealExport(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
            // The old device stays signed in: whether its session ends is the receiver's registration
            // and the app-wide kick-out detection's business (a desktop receiver links as a secondary
            // device and this one remains the primary). The terminal notification is posted as usual.
            verify(exactly = 1) { notificationHelper.showTerminal(any(), any()) }
        } finally {
            unmockkConstructor(MigrationP2pSendController::class)
        }
    }

    @Test
    fun `runSender passes the attachments choice through to the production controller`() = runTest {
        mockkConstructor(MigrationP2pSendController::class)
        val includeSlot = slot<Boolean>()
        coEvery {
            anyConstructed<MigrationP2pSendController>().sendRealExport(any(), any(), any(), any(), capture(includeSlot), any(), any(), any(), any(), any(), any(), any(), any())
        } returns true
        try {
            service.runSender(sendTarget, includeAttachments = false)

            assertEquals(false, includeSlot.captured, "the user's choice must reach the exporter unchanged")
        } finally {
            unmockkConstructor(MigrationP2pSendController::class)
        }
    }

    // ── same-device handoff: the sender wipes only on an explicit "installed" acknowledgment ──

    private fun handoffCollaborators(): Pair<MigrationHandoffCompleter, MigrationHandoffReconciler> {
        val completer = mockk<MigrationHandoffCompleter>(relaxed = true)
        val reconciler = mockk<MigrationHandoffReconciler>(relaxed = true)
        setField("handoffCompleter", completer)
        setField("handoffReconciler", reconciler)
        return completer to reconciler
    }

    @Test
    fun `handoff completes only when the 200 says the credential was installed`() = runTest {
        val (completer, reconciler) = handoffCollaborators()
        mockkConstructor(MigrationP2pSendController::class)
        coEvery {
            anyConstructed<MigrationP2pSendController>().sendRealExport(any(), any(), any(), any(), any(), any(), any(), any(), any(), captureLambda(), any(), any(), any())
        } answers {
            lambda<(Map<String, String>) -> Unit>().captured.invoke(mapOf(UploadReplyHeaders.HANDOFF_INSTALLED to "true"))
            true
        }
        try {
            service.runSender(sendTarget, credentialHandoff = true)

            assertEquals(TransferState.Sent, MigrationTransferStateHolder.state.value)
            coVerify(exactly = 1) { completer.complete(any()) }
            coVerify(exactly = 0) { reconciler.onSendFailure() }
        } finally {
            unmockkConstructor(MigrationP2pSendController::class)
        }
    }

    /** A receiver that never understood the handoff answers a plain 200: the sender must keep its session. */
    @Test
    fun `handoff answered 200 without the acknowledgment keeps this session and reconciles`() = runTest {
        val (completer, reconciler) = handoffCollaborators()
        mockkConstructor(MigrationP2pSendController::class)
        coEvery {
            anyConstructed<MigrationP2pSendController>().sendRealExport(any(), any(), any(), any(), any(), any(), any(), any(), any(), captureLambda(), any(), any(), any())
        } answers {
            lambda<(Map<String, String>) -> Unit>().captured.invoke(emptyMap<String, String>())
            true
        }
        try {
            service.runSender(sendTarget, credentialHandoff = true)

            val state = MigrationTransferStateHolder.state.value
            assertIs<TransferState.Failed>(state)
            assertEquals(HANDOFF_NOT_ACKNOWLEDGED, state.reason)
            coVerify(exactly = 0) { completer.complete(any()) }
            coVerify(exactly = 1) { reconciler.onSendFailure() }
        } finally {
            unmockkConstructor(MigrationP2pSendController::class)
        }
    }
}
