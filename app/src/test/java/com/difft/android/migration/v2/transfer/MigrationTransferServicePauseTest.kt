package com.difft.android.migration.v2.transfer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationPauseLoginScope
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.chat.messages.MigrationRetryQueueDrain
import com.difft.android.chat.messages.RetryQueueResidual
import com.difft.android.migration.v2.pause.MigrationPauseException
import com.difft.android.migration.v2.pause.SenderMessagePause
import com.difft.android.migration.v2.pause.SenderPauseSession
import com.difft.android.migration.v2.pause.SenderVerdictSettlement
import com.difft.android.migration.v2.transfer.send.MigrationP2pSendController
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.snapshot.BackupSnapshotter
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerWriter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue





@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationTransferServicePauseTest {

    private lateinit var service: MigrationTransferService
    private val messaging = TestMessagingPause().open()
    private val pendingStore = PendingVerdictStore(messaging.store)
    private val statusRef = SenderSessionRef("10.0.0.2", 53317, "fp", "session-1", "token", listOf("status-v1"))

    
    private val retainRef = statusRef.copy(caps = listOf(ReceiverCaps.STATUS_V1, ReceiverCaps.VERDICT_RETAIN_V1))
    private val target = QrPayload(ip = "192.168.1.7", port = 53317, fp = "fp", pin = "123456", alias = "Pixel", transferKey = "tk")
    private val retryDrain = mockk<MigrationRetryQueueDrain>()
    private var residual = RetryQueueResidual(failed = 0, pending = 0)

    @Before
    fun setUp() {
        MigrationTransferStateHolder.reset()
        service = MigrationTransferService::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(service, ApplicationProvider.getApplicationContext<Application>())
        val notificationHelper = mockk<MigrationTransferNotificationHelper>(relaxed = true)
        every { notificationHelper.buildRunning(any(), any()) } returns mockk(relaxed = true)
        setField("notificationHelper", notificationHelper)
        setField("userManager", mockk<StorageBoundUserManager>(relaxed = true))
        setField("containerWriter", mockk<ContainerWriter>(relaxed = true))
        setField("containerReader", mockk<ContainerReader>(relaxed = true))
        setField("backupCrypto", mockk<BackupCrypto>(relaxed = true))
        setField("environmentHelper", mockk<EnvironmentHelper>(relaxed = true))
        setField("snapshotter", mockk<BackupSnapshotter>(relaxed = true))
        setField("pendingVerdictStore", pendingStore)
        val loginScope = mockk<MigrationPauseLoginScope> { every { current() } returns MigrationPauseLoginScope.Scope("scope", "cred") }
        setField(
            "senderMessagePause",
            SenderMessagePause(messaging.pause, messaging.gate, loginScope, retryDrain, pendingStore),
        )
        coEvery { retryDrain.drainAsOwner(any()) } answers { residual }
        setField("verdictSettlement", SenderVerdictSettlement(messaging.pause, pendingStore))
        mockkConstructor(MigrationP2pSendController::class)
    }

    @After
    fun tearDown() {
        MigrationTransferStateHolder.reset()
        unmockkAll()
    }

    private fun setField(name: String, value: Any) {
        MigrationTransferService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    
    private fun export(
        ref: SenderSessionRef = statusRef,
        script: suspend (SenderPauseSession?, (String) -> Unit) -> Boolean,
    ) {
        coEvery {
            anyConstructed<MigrationP2pSendController>().sendRealExport(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onSessionPrepared = arg<(SenderSessionRef) -> Unit>(11)
            onSessionPrepared(ref)
            script(arg(14), arg(10))
        }
    }

    
    private suspend fun SenderPauseSession.throughCommitMarker(endMarker: Boolean = true) {
        enter()
        beforeFinalize()
        if (endMarker) onEndMarker()
    }

    private suspend fun receiptSession(): String? = pendingStore.load()?.session?.sessionId

    private fun phase() = messaging.pause.record?.phase

    @Test
    fun `a Desktop link never pauses`() = runBlocking<Unit> {
        var exported = false
        var session: SenderPauseSession? = null
        export { s, _ ->
            exported = true
            session = s
            true
        }

        service.runSender(target, deviceReplacement = false)

        assertTrue(exported)
        assertNull(session)
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertEquals(TransferState.Sent, MigrationTransferStateHolder.state.value)
    }

    @Test
    fun `a sent device replacement awaits the old login`() = runBlocking<Unit> {
        export { s, _ -> s!!.throughCommitMarker(); true }

        service.runSender(target, deviceReplacement = true)

        assertEquals(MigrationPausePhase.AWAITING_LOGIN, phase())
        assertEquals(TransferState.Sent, MigrationTransferStateHolder.state.value)
    }

    @Test
    fun `a reject before the commit marker resumes messaging`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.enter()
            throw PrepareUploadRejectedException("account_mismatch")
        }

        service.runSender(target, deviceReplacement = true)

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
    }

    @Test
    fun `a quiescence timeout fails the send and resumes messaging`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.enter()
            throw MigrationPauseException(SenderPauseSession.REASON_QUIESCENCE_TIMEOUT)
        }

        service.runSender(target, deviceReplacement = true)

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertEquals(TransferState.Failed(SenderPauseSession.REASON_QUIESCENCE_TIMEOUT), MigrationTransferStateHolder.state.value)
    }

    @Test
    fun `failed rows left after the drain fail the send before the snapshot and resume messaging`() = runBlocking<Unit> {
        residual = RetryQueueResidual(failed = 2, pending = 1)
        var reachedSnapshot = false
        export { s, _ ->
            s!!.enter()
            reachedSnapshot = true
            true
        }

        service.runSender(target, deviceReplacement = true)

        assertFalse(reachedSnapshot)
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertNull(messaging.pause.record)
        assertEquals(TransferState.Failed(SenderPauseSession.REASON_RESIDUAL), MigrationTransferStateHolder.state.value)
    }

    @Test
    fun `a receiver without the pending-envelope capability fails the send and resumes messaging`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.enter()
            
            s.onAbortAnswer(CancelState.STOPPED)
            throw ReceiverCapabilityMissingException(setOf(ReceiverCaps.PENDING_ENVELOPE_V1))
        }

        service.runSender(target, deviceReplacement = true)

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertNull(messaging.pause.record)
        assertEquals(
            TransferState.Failed(ReceiverCapabilityMissingException.RECEIVER_UPDATE_REQUIRED),
            MigrationTransferStateHolder.state.value,
        )
    }

    @Test
    fun `a cancel after the marker that the receiver stopped resumes messaging and drops the receipt`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.throughCommitMarker()
            s.onAbortAnswer(CancelState.STOPPED)
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> { service.runSender(target, deviceReplacement = true) }

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertNull(receiptSession())
    }

    @Test
    fun `an unanswered cancel after the end marker awaits a decision, paused`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.throughCommitMarker()
            s.onAbortAnswer(null)
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> { service.runSender(target, deviceReplacement = true) }

        assertEquals(MigrationPausePhase.AWAITING_DECISION, phase())
        assertIs<MessagingPauseState.Paused>(messaging.gate.state)
        assertEquals("session-1", receiptSession())
    }

    @Test
    fun `an unanswered cancel before the end marker left proves nothing committed and resumes`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.throughCommitMarker(endMarker = false)
            s.onAbortAnswer(null)
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> { service.runSender(target, deviceReplacement = true) }

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertNull(receiptSession())
    }

    @Test
    fun `a failure after the body with no answer awaits a decision, shows unknown and keeps the receipt`() = runBlocking<Unit> {
        for (ref in listOf(statusRef, retainRef)) {
            messaging.resumeMessaging()
            export(ref) { s, _ ->
                s!!.throughCommitMarker()
                s.onAbortAnswer(null)
                throw java.io.IOException("connection reset")
            }

            service.runSender(target, deviceReplacement = true)

            assertEquals(MigrationPausePhase.AWAITING_DECISION, phase(), "caps=${ref.caps}")
            assertIs<MessagingPauseState.Paused>(messaging.gate.state)
            assertEquals(TransferState.Unknown, MigrationTransferStateHolder.state.value)
            assertEquals("session-1", receiptSession(), "the re-check still needs the receipt")
        }
    }

    @Test
    fun `a non-200 without a receiver reason awaits a decision when the receiver retains its verdict`() = runBlocking<Unit> {
        export(retainRef) { s, _ ->
            s!!.throughCommitMarker()
            false
        }

        service.runSender(target, deviceReplacement = true)

        assertEquals(MigrationPausePhase.AWAITING_DECISION, phase())
        assertEquals(TransferState.Unknown, MigrationTransferStateHolder.state.value)
        assertEquals("session-1", receiptSession())
    }

    @Test
    fun `a non-200 without a receiver reason from an older receiver is a failure and resumes`() = runBlocking<Unit> {
        export(statusRef) { s, _ ->
            s!!.throughCommitMarker()
            false
        }

        service.runSender(target, deviceReplacement = true)

        assertNull(messaging.pause.record, "an older receiver stops answering after a failure: nothing to wait on")
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertIs<TransferState.Failed>(MigrationTransferStateHolder.state.value)
        assertNull(receiptSession())
    }

    @Test
    fun `the receiver's typed refusal after the end marker is a definite failure`() = runBlocking<Unit> {
        for (ref in listOf(statusRef, retainRef)) {
            messaging.resumeMessaging()
            export(ref) { s, onRejected ->
                s!!.throughCommitMarker()
                onRejected("insufficient-storage")
                false
            }

            service.runSender(target, deviceReplacement = true)

            assertEquals(MessagingPauseState.Open, messaging.gate.state, "caps=${ref.caps}")
            assertIs<TransferState.Failed>(MigrationTransferStateHolder.state.value)
            assertNull(receiptSession())
        }
    }

    @Test
    fun `a wait for the verdict that ran out awaits a decision whatever the receiver advertised`() = runBlocking<Unit> {
        for (ref in listOf(statusRef, retainRef)) {
            messaging.resumeMessaging()
            export(ref) { s, _ ->
                s!!.throughCommitMarker()
                throw SendVerdictException(Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT))
            }

            service.runSender(target, deviceReplacement = true)

            assertEquals(MigrationPausePhase.AWAITING_DECISION, phase(), "caps=${ref.caps}")
            assertEquals(TransferState.Unknown, MigrationTransferStateHolder.state.value)
        }
    }

    @Test
    fun `a new export is refused while one is unresolved, and the receipt stays`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.throughCommitMarker()
            throw SendVerdictException(Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT))
        }
        service.runSender(target, deviceReplacement = true)
        assertEquals(MigrationPausePhase.AWAITING_DECISION, phase())
        val earlierOwner = messaging.pause.record?.owner

        export { s, _ ->
            s!!.enter()
            true
        }
        service.runSender(target, deviceReplacement = true)

        assertEquals(TransferState.Failed(SenderPauseSession.REASON_UNAVAILABLE), MigrationTransferStateHolder.state.value)
        assertEquals(earlierOwner, messaging.pause.record?.owner)
        assertEquals(MigrationPausePhase.AWAITING_DECISION, phase())
        assertEquals("session-1", receiptSession())
    }

    @Test
    fun `an unknown verdict awaits a decision and an ended one resumes`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.throughCommitMarker()
            throw SendVerdictException(Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT))
        }
        service.runSender(target, deviceReplacement = true)
        assertEquals(MigrationPausePhase.AWAITING_DECISION, phase())
        assertIs<MessagingPauseState.Paused>(messaging.gate.state)

        messaging.resumeMessaging()
        export { s, _ ->
            s!!.throughCommitMarker()
            throw SendVerdictException(Verdict.Ended("failed"))
        }
        service.runSender(target, deviceReplacement = true)
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertIs<TransferState.Failed>(MigrationTransferStateHolder.state.value)
    }

    @Test
    fun `a success the pause did not take shows no definite result and cleans nothing`() = runBlocking<Unit> {
        export { s, _ ->
            s!!.throughCommitMarker()
            
            messaging.pause.clear(s.owner, "test")
            true
        }

        service.runSender(target, deviceReplacement = true)

        assertEquals(TransferState.Unknown, MigrationTransferStateHolder.state.value)
        assertEquals("session-1", receiptSession())
    }

    @Test
    fun `a user cancel stops the service only after the pause settled`() = runBlocking<Unit> {
        val atMarker = CompletableDeferred<Unit>()
        export { s, _ ->
            s!!.throughCommitMarker()
            atMarker.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                
                withContext(NonCancellable) {
                    delay(300)
                    s.onAbortAnswer(CancelState.STOPPED)
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        val serviceScope = MigrationTransferService::class.java.getDeclaredField("serviceScope")
            .apply { isAccessible = true }.get(service) as CoroutineScope
        serviceScope.launch { service.runSender(target, deviceReplacement = true) }
        withTimeout(5_000) { atMarker.await() }

        service.onStartCommand(Intent(MigrationTransferService.ACTION_CANCEL), 0, 1)

        val shadow = Shadows.shadowOf(service)
        assertFalse(shadow.isStoppedBySelf, "the service must wait for the cancelled send to settle")
        withTimeout(5_000) { while (!shadow.isStoppedBySelf) delay(10) }
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
    }
}
