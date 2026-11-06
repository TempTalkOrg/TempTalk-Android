package com.difft.android.migration.v2.transfer.send

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.snapshot.BackupSnapshotter
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.container.TransferProgress
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.export.SnapshotReadSource
import com.difft.android.migration.v2.pause.MigrationPauseException
import com.difft.android.migration.v2.pause.SenderPauseSession
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.transfer.P2pContainerFusion
import com.difft.android.migration.v2.transfer.ReceiverCapabilityMissingException
import com.difft.android.migration.v2.transfer.SendCommitHooks
import com.difft.android.p2p.engine.model.ReceiverCaps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.difft.app.database.models.PendingMessageModelNew
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.util.Base64
import java.util.concurrent.TimeUnit
import io.mockk.coVerifyOrder
import kotlin.test.assertNull
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.qr.QrPayloadV2Codec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [MigrationP2pSendController] — the CORE new behavior vs the demo controller: it passes the LIVE account
 * + `environmentHelper.currentEnvironment` to `P2pContainerFusion.sendRealExport` with a NON-NULL
 * `sourceEnvironment`, which is what makes the fusion build + send the `MigrationHandshake` (the demo
 * controller passes `null` → no handshake). Plus the snapshot close+delete-on-every-exit safety + the
 * empty-account fail-fast guard.
 *
 * The fusion + the snapshot read-source opener are injected as default-valued seams so we assert the
 * call args without standing up native sockets / WCDB. The internally-built `NeutralExporterImpl` is pure
 * Kotlin over the relaxed-mock read source; its lazy `export()` flow is never collected here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationP2pSendControllerTest {

    private lateinit var snapshotter: BackupSnapshotter
    private lateinit var environmentHelper: EnvironmentHelper
    private lateinit var userManager: StorageBoundUserManager
    private lateinit var fusion: P2pContainerFusion
    private lateinit var snapshotFile: File
    private lateinit var readSource: SnapshotReadSource

    private val target = QrPayload(
        ip = "192.168.1.7", port = 53317, fp = "fp", pin = "123456", alias = "Pixel", transferKey = "tk",
    )

    @Before
    fun setUp() {
        snapshotter = mockk(relaxed = true)
        environmentHelper = mockk(relaxed = true)
        every { environmentHelper.ENVIRONMENT_ONLINE } returns "prod"
        every { environmentHelper.ENVIRONMENT_DEVELOPMENT } returns "test"
        userManager = mockk(relaxed = true)
        fusion = mockk(relaxed = true)

        snapshotFile = File.createTempFile("snap", ".db").apply { writeText("x") }
        coEvery { snapshotter.takeSnapshot() } returns snapshotFile

        // The opener seam returns a relaxed-mock read source (no native WCDB open). The NeutralExporterImpl
        // built over it stays pure-JVM (its export() flow is never collected here).
        readSource = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        snapshotFile.delete()
        clearAllMocks()
        unmockkAll()
    }

    private fun newController(
        counted: ContainerTotals = ContainerTotals(recordCount = 0, attachmentCount = 0, attachmentBytes = 0),
        pendingRows: List<PendingMessageModelNew> = emptyList(),
    ) = MigrationP2pSendController(
        context = ApplicationProvider.getApplicationContext<Application>(),
        writer = mockk<ContainerWriter>(relaxed = true),
        reader = mockk<ContainerReader>(relaxed = true),
        crypto = mockk<BackupCrypto>(relaxed = true),
        snapshotter = snapshotter,
        environmentHelper = environmentHelper,
        userManager = userManager,
        appVersion = "1.2.3",
        fusion = fusion,
        openReadSource = { _, _ -> readSource },
        // Stub the pre-count so the test avoids the native-WCDB `DBMessageModel`/winq query builders the
        // real `preCount()` walks (ExceptionInInitializerError on the JVM).
        preCount = { counted },
        readPendingRows = { pendingRows },
        clock = { NOW },
    )

    @Test
    fun `passes the LIVE account and REAL env (handshake-triggering) to the fusion`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"

        val accountSlot = slot<String>()
        val envSlot = slot<String?>()
        coEvery {
            fusion.sendRealExport(
                target = any(), scope = any(), records = any(), attachmentSource = any(),
                sourceAccount = capture(accountSlot), sourceAppVersion = any(),
                sourceEnvironment = captureNullable(envSlot), sourceFormatVersion = any(),
                onProgress = any(), onBodySent = any(), onReply = any(), onRejected = any(), estimatedTotalBytes = any(), totals = any(),
                senderAlias = any(), awaitPeerConfirm = any(), onAwaitingPeerConfirm = any(),
                onSessionPrepared = any(), verdictWatch = any(), onVerdictStall = any(),
            )
        } returns true

        val ok = newController().sendRealExport(scope = this, target = target, onProgress = {})

        assertTrue(ok)
        assertEquals("acc_real", accountSlot.captured)
        // The thing the demo does NOT do: a non-null env → the fusion builds + sends the handshake —
        // under its wire name, never the build-config spelling.
        assertEquals(QrPayloadV2Codec.Environment.PROD, envSlot.captured)
    }

    @Test
    fun `passes the pre-counted totals (plus the identity record) and a matching advisory size`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        val counted = ContainerTotals(recordCount = 7, attachmentCount = 2, attachmentBytes = 3_000)
        val totalsSlot = slot<ContainerTotals>()
        val sizeSlot = slot<Long>()
        coEvery {
            fusion.sendRealExport(
                target = any(), scope = any(), records = any(), attachmentSource = any(),
                sourceAccount = any(), sourceAppVersion = any(), sourceEnvironment = any(),
                sourceFormatVersion = any(), onProgress = any(), onBodySent = any(), onReply = any(), onRejected = any(),
                estimatedTotalBytes = capture(sizeSlot), totals = capture(totalsSlot), senderAlias = any(),
                awaitPeerConfirm = any(), onAwaitingPeerConfirm = any(),
                onSessionPrepared = any(), verdictWatch = any(), onVerdictStall = any(),
            )
        } returns true

        newController(counted).sendRealExport(scope = this, target = target, onProgress = {})

        val totals = totalsSlot.captured
        // The exporter counts DB rows; the controller may prepend one identity record on top.
        assertTrue(totals.recordCount == counted.recordCount || totals.recordCount == counted.recordCount + 1)
        assertEquals(counted.attachmentCount, totals.attachmentCount)
        assertEquals(counted.attachmentBytes, totals.attachmentBytes)
        // The advisory size is on the formula's own scale, not the snapshot file size.
        assertEquals(totals.recordCount * TransferProgress.RECORD_WEIGHT_BYTES + totals.attachmentBytes, sizeSlot.captured)
    }

    @Test
    fun `closes and deletes the snapshot when the fusion throws`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        coEvery {
            fusion.sendRealExport(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("boom")

        assertFailsWith<RuntimeException> {
            newController().sendRealExport(scope = this, target = target, onProgress = {})
        }
        verify { readSource.close() }
        assertTrue(!snapshotFile.exists(), "snapshot must be deleted in finally on failure")
    }

    @Test
    fun `closes and deletes the snapshot when the scope is cancelled mid-send`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        val scope = CoroutineScope(Dispatchers.Unconfined)
        coEvery {
            fusion.sendRealExport(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            scope.cancel() // cancel mid-send → withContext cancels → finally still runs
            throw kotlinx.coroutines.CancellationException("cancelled")
        }

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            newController().sendRealExport(scope = scope, target = target, onProgress = {})
        }
        verify { readSource.close() }
        assertTrue(!snapshotFile.exists(), "snapshot must be deleted in finally on cancel")
    }

    @Test
    fun `fails fast with no snapshot taken when the account is empty`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "")

        assertFailsWith<IllegalStateException> {
            newController().sendRealExport(scope = this, target = target, onProgress = {})
        }
        // Empty-account guard: NO snapshot, NO fusion call, NO network round-trip.
        coVerify(exactly = 0) { snapshotter.takeSnapshot() }
        coVerify(exactly = 0) {
            fusion.sendRealExport(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ── #1382: the device-replacement message pause around the snapshot ──

    private fun anyExport() = coEvery {
        fusion.sendRealExport(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a device replacement pauses before the snapshot and hands the commit hooks to the pipeline`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        val session = mockk<SenderPauseSession>(relaxed = true)
        val hooks = slot<SendCommitHooks?>()
        coEvery {
            fusion.sendRealExport(
                target = any(), scope = any(), records = any(), attachmentSource = any(),
                sourceAccount = any(), sourceAppVersion = any(), sourceEnvironment = any(),
                sourceFormatVersion = any(), onProgress = any(), onBodySent = any(), onReply = any(), onRejected = any(),
                estimatedTotalBytes = any(), totals = any(), senderAlias = any(),
                awaitPeerConfirm = any(), onAwaitingPeerConfirm = any(),
                onSessionPrepared = any(), verdictWatch = any(), onVerdictStall = any(),
                commitHooks = captureNullable(hooks),
            )
        } returns true

        newController().sendRealExport(scope = this, target = target, onProgress = {}, pauseSession = session)

        coVerifyOrder {
            session.enter()
            snapshotter.takeSnapshot()
        }
        assertEquals(session, hooks.captured)
    }

    @Test
    fun `a pause that cannot be entered takes no snapshot`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        val session = mockk<SenderPauseSession>()
        coEvery { session.enter() } throws MigrationPauseException(SenderPauseSession.REASON_QUIESCENCE_TIMEOUT)
        anyExport() returns true

        assertFailsWith<MigrationPauseException> {
            newController().sendRealExport(scope = this, target = target, onProgress = {}, pauseSession = session)
        }
        coVerify(exactly = 0) { snapshotter.takeSnapshot() }
    }

    @Test
    fun `a Desktop link export has no pause and no commit hooks`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        val hooks = slot<SendCommitHooks?>()
        coEvery {
            fusion.sendRealExport(
                target = any(), scope = any(), records = any(), attachmentSource = any(),
                sourceAccount = any(), sourceAppVersion = any(), sourceEnvironment = any(),
                sourceFormatVersion = any(), onProgress = any(), onBodySent = any(), onReply = any(), onRejected = any(),
                estimatedTotalBytes = any(), totals = any(), senderAlias = any(),
                awaitPeerConfirm = any(), onAwaitingPeerConfirm = any(),
                onSessionPrepared = any(), verdictWatch = any(), onVerdictStall = any(),
                commitHooks = captureNullable(hooks),
            )
        } returns true

        newController().sendRealExport(scope = this, target = target, onProgress = {})

        assertNull(hooks.captured)
    }

    // ── #1382: pending envelopes and the receiver capability they need ──

    private class Captured {
        val records = slot<Flow<NeutralRecord>>()
        val totals = slot<ContainerTotals>()
        val caps = slot<Set<String>>()
    }

    private fun captureExport(): Captured {
        val c = Captured()
        coEvery {
            fusion.sendRealExport(
                target = any(), scope = any(), records = capture(c.records), attachmentSource = any(),
                sourceAccount = any(), sourceAppVersion = any(), sourceEnvironment = any(),
                sourceFormatVersion = any(), onProgress = any(), onBodySent = any(), onReply = any(), onRejected = any(),
                estimatedTotalBytes = any(), totals = capture(c.totals), senderAlias = any(), credentialHandoff = any(),
                awaitPeerConfirm = any(), onAwaitingPeerConfirm = any(),
                onSessionPrepared = any(), verdictWatch = any(), onVerdictStall = any(), commitHooks = any(),
                requiredCaps = capture(c.caps),
            )
        } returns true
        return c
    }

    private fun pendingRow(id: String?, originalTs: Long, bytes: ByteArray = envelope(originalTs)) =
        PendingMessageModelNew().apply {
            messageId = id
            originalMessageTimeStamp = originalTs
            messageEnvelopBytes = bytes
        }

    private fun envelope(ts: Long): ByteArray = Envelope.newBuilder().setTimestamp(ts + 1).build().toByteArray()

    @Test
    fun `a device replacement carries live pending envelopes and requires the receiver capability`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        val fresh = NOW - TimeUnit.DAYS.toMillis(9)
        val rows = listOf(
            pendingRow("recall-1", fresh),
            pendingRow("expired", NOW - TimeUnit.DAYS.toMillis(11)),
            pendingRow("garbage", fresh, bytes = byteArrayOf(0, 0, 0)),
            pendingRow(null, fresh),
            pendingRow("reaction-2", NOW),
        )
        val captured = captureExport()

        newController(pendingRows = rows).sendRealExport(
            scope = this, target = target, onProgress = {}, pauseSession = mockk(relaxed = true),
        )

        assertEquals(setOf(ReceiverCaps.PENDING_ENVELOPE_V1), captured.caps.captured)
        assertEquals(2, captured.totals.captured.recordCount, "the two carried envelopes are counted")
        // They lead the stream; taking them never starts the (native) database walk behind them.
        val carried = captured.records.captured.take(2).toList().map { (it as NeutralRecord.PendingEnvelope).value }
        assertEquals(listOf("recall-1", "reaction-2"), carried.map { it.messageId })
        assertEquals(fresh, carried[0].originalMessageTimeStamp)
        assertTrue(envelope(fresh).contentEquals(Base64.getDecoder().decode(carried[0].messageEnvelopBytes)))
    }

    @Test
    fun `a device replacement with nothing pending needs no capability`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        val captured = captureExport()

        newController(pendingRows = listOf(pendingRow("expired", NOW - TimeUnit.DAYS.toMillis(11))))
            .sendRealExport(scope = this, target = target, onProgress = {}, pauseSession = mockk(relaxed = true))

        assertEquals(emptySet(), captured.caps.captured)
        assertEquals(0, captured.totals.captured.recordCount)
    }

    @Test
    fun `a Desktop link carries no pending envelopes and needs no capability`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        val captured = captureExport()

        newController(pendingRows = listOf(pendingRow("recall-1", NOW))).sendRealExport(scope = this, target = target, onProgress = {})

        assertEquals(emptySet(), captured.caps.captured)
        assertEquals(0, captured.totals.captured.recordCount)
    }

    @Test
    fun `a receiver missing the capability deletes the snapshot`() = runTest {
        every { userManager.getUserData() } returns UserData(account = "acc_real")
        every { environmentHelper.currentEnvironment } returns "prod"
        anyExport() throws ReceiverCapabilityMissingException(setOf(ReceiverCaps.PENDING_ENVELOPE_V1))

        val e = assertFailsWith<ReceiverCapabilityMissingException> {
            newController(pendingRows = listOf(pendingRow("recall-1", NOW)))
                .sendRealExport(scope = this, target = target, onProgress = {}, pauseSession = mockk(relaxed = true))
        }

        assertEquals(ReceiverCapabilityMissingException.RECEIVER_UPDATE_REQUIRED, e.message)
        verify { readSource.close() }
        assertTrue(!snapshotFile.exists(), "snapshot must be deleted when the receiver is refused")
    }

    @Test
    fun `fails fast when there is no logged-in account at all`() = runTest {
        every { userManager.getUserData() } returns null

        assertFailsWith<IllegalStateException> {
            newController().sendRealExport(scope = this, target = target, onProgress = {})
        }
        coVerify(exactly = 0) { snapshotter.takeSnapshot() }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
