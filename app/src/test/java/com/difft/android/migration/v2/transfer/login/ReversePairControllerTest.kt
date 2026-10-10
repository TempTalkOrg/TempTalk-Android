package com.difft.android.migration.v2.transfer.login

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.TransferState
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.ReversePairRequest
import com.difft.android.p2p.engine.model.ReversePairResponse
import com.difft.android.p2p.engine.session.PairingSessionRegistry
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T15–T22 — [ReversePairController]'s state machine, driven without a socket through its two seams
 * (address race + `/reverse-pair` call).
 *
 * The properties under test are the ones the whole design rests on: the pairing session is
 * registered BEFORE the request goes out, a response that fails after an upload was accepted is
 * ignored rather than aborting it, and `run()` always returns so the receiver role can tear down.
 *
 * The role runs on a scope of its own carrying the test's scheduler, exactly as it runs on the
 * service scope in production — and, unlike `backgroundScope`, that is work `runCurrent()` actually
 * drives. Time is advanced explicitly, never by `advanceUntilIdle()`, which would burn through the
 * connect deadline these tests exist to observe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReversePairControllerTest {

    private val macPin = "654321"
    private val registry = PairingSessionRegistry()

    /** Whatever the running controller sent, so a test can play the peer with the real pair token. */
    private val sentBody = CompletableDeferred<ReversePairRequest>()

    private val roleScopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() = MigrationTransferStateHolder.reset()

    @After
    fun tearDown() {
        roleScopes.forEach { it.cancel() }
        MigrationTransferStateHolder.reset()
    }

    private class FakeEnvironment : EnvironmentHelper {
        override val currentEnvironment: String = "prod"
        override val ENVIRONMENT_ONLINE: String = "prod"
        override val ENVIRONMENT_DEVELOPMENT: String = "dev"
        override fun isThatEnvironment(environment: String): Boolean = currentEnvironment == environment
        override fun isGoogleChannel(): Boolean = false
        override fun isInsiderChannel(): Boolean = false
        override fun getChannelName(): String = "official"
        override fun getScheme(): String = "quicall"
    }

    private fun macQr() = QrPayload(
        ip = "192.168.1.9",
        port = 53317,
        fp = "ab".repeat(32),
        pin = macPin,
        alias = "desk",
        transferKey = "bWFjLWtleQ==",
    )

    private fun listenQr() = QrPayload(
        ip = "192.168.1.5",
        ips = listOf("192.168.1.5", "10.0.0.2"),
        port = 53318,
        fp = "cd".repeat(32),
        pin = "123456",
        alias = "phone",
        transferKey = "bG9jYWwta2V5",
    )

    private fun controller(
        capture: CompletableDeferred<ReversePairRequest>,
        pair: suspend (QrPayload, ReversePairRequest) -> ReversePairResponse,
    ) = ReversePairController(
        appContext = ApplicationProvider.getApplicationContext<Application>(),
        registry = registry,
        environmentHelper = FakeEnvironment(),
        appVersion = "9.9.9",
        account = "+12025550100",
        resolve = { it },
        pair = { target, body ->
            capture.complete(body)
            pair(target, body)
        },
    )

    /** The role scope the service would own, plus the `run()` job on it. */
    private class Role(val scope: CoroutineScope, val job: Job)

    private fun TestScope.startPairing(
        capture: CompletableDeferred<ReversePairRequest> = sentBody,
        pair: suspend (QrPayload, ReversePairRequest) -> ReversePairResponse,
    ): Role {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        roleScopes += scope
        val job = scope.launch { controller(capture, pair).run(scope = scope, mac = macQr(), listen = listenQr()) }
        runCurrent()
        return Role(scope, job)
    }

    private fun holder() = MigrationTransferStateHolder.state.value

    private suspend fun hangForever(): ReversePairResponse = CompletableDeferred<ReversePairResponse>().await()

    // ── T15: registration precedes the request ────────────────────────────────────────────────

    @Test
    fun `the pairing session is registered before the request is sent`() = runTest {
        val phaseAtCallTime = CompletableDeferred<PairingSessionRegistry.Phase?>()
        startPairing { _, _ ->
            phaseAtCallTime.complete(registry.phase(FIRST_GENERATION))
            hangForever()
        }

        assertEquals(PairingSessionRegistry.Phase.PENDING, phaseAtCallTime.getCompleted())
        assertIs<TransferState.AwaitingDesktopConfirm>(holder())
        // The body carries this device's listener, not the peer's, and the token it must quote back.
        val body = sentBody.getCompleted()
        assertEquals(listOf("192.168.1.5", "10.0.0.2"), body.listen.addresses)
        assertEquals(53318, body.listen.port)
        assertEquals(listenQr().fp, body.device.fingerprint)
        assertTrue(body.pairToken.isNotBlank())
    }

    // ── T16 / T16b: a late failure never aborts an accepted upload, and run() still returns ────

    @Test
    fun `a reverse-pair failure after the upload was accepted is ignored and run completes`() = runTest {
        val release = CompletableDeferred<Unit>()
        val role = startPairing { _, _ ->
            release.await()
            throw SocketTimeoutException("the peer's response never arrived")
        }

        // The peer connected back before its own 200 could be written.
        assertEquals(
            PairingSessionRegistry.Decision.Accept,
            registry.authorizePrepareUpload(sentBody.getCompleted().pairToken, macPin),
        )
        runCurrent()
        assertTrue(role.job.isCompleted, "run() returns once the upload owns the outcome")

        release.complete(Unit)
        runCurrent()
        assertFalse(holder() is TransferState.Failed, "a late pairing failure must not fail the transfer")
        assertTrue(role.scope.isActive, "…nor cancel the scope the receiver role runs on")
    }

    // ── T17: a failure while nothing has connected is the terminal outcome ─────────────────────

    @Test
    fun `a reverse-pair failure while still pending fails the transfer with the peer's reason`() = runTest {
        val role = startPairing { _, _ -> throw SocketException("connection refused") }
        runCurrent()

        assertTrue(role.job.isCompleted)
        assertEquals(TransferState.Failed(PrepareUploadReasons.PEER_UNREACHABLE), holder())
        assertNull(registry.phase(FIRST_GENERATION), "the generation is invalidated on the way out")
    }

    @Test
    fun `a typed reject keeps the peer's own wire token`() = runTest {
        startPairing { _, _ -> throw PrepareUploadRejectedException(PrepareUploadReasons.DESKTOP_CANCELLED) }
        runCurrent()

        assertEquals(TransferState.Failed(PrepareUploadReasons.DESKTOP_CANCELLED), holder())
    }

    // ── T18: the connect deadline ──────────────────────────────────────────────────────────────

    @Test
    fun `a PIN-cap eviction while waiting for the computer fails the transfer instead of parking`() = runTest {
        val role = startPairing { _, _ -> awaitCancellation() }
        runCurrent()
        assertTrue(holder() is TransferState.AwaitingDesktopConfirm)

        val token = sentBody.getCompleted().pairToken
        repeat(PinPolicy.MAX_PIN_ATTEMPTS) { registry.authorizePrepareUpload(token, "000000") }
        runCurrent()

        assertEquals(TransferState.Failed(MigrationRejectReason.PAIR_TOKEN.wire), holder())
        assertTrue(role.job.isCompleted, "run() must return once the session is gone")
    }

    @Test
    fun `a confirmed pairing that is never connected to fails after the window`() = runTest {
        val role = startPairing { _, _ -> confirmed(connectWithinMs = 30_000L) }
        runCurrent()
        assertEquals(TransferState.AwaitingDesktopConnect("desk", 30_000L), holder())

        advanceTimeBy(30_001L)
        runCurrent()
        assertEquals(TransferState.Failed(PrepareUploadReasons.DESKTOP_NOT_CONNECTED), holder())
        assertNull(registry.phase(FIRST_GENERATION))
        assertTrue(role.job.isCompleted)
    }

    // ── T19: a prepare-upload that beats the 200 ───────────────────────────────────────────────

    @Test
    fun `a prepare-upload accepted before the 200 arms no deadline and fails nothing`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val role = startPairing { _, _ ->
            answer.await()
            confirmed(connectWithinMs = 30_000L)
        }
        registry.authorizePrepareUpload(sentBody.getCompleted().pairToken, macPin)
        runCurrent()
        answer.complete(Unit)
        runCurrent()

        // Well past any deadline the 200 could have armed.
        advanceTimeBy(ReversePairController.MAX_CONNECT_MS * 2)
        runCurrent()
        assertTrue(role.job.isCompleted)
        assertIs<TransferState.AwaitingDesktopConfirm>(holder())
    }

    // ── T20: the peer-supplied window is clamped ───────────────────────────────────────────────

    @Test
    fun `an absurd connect window is clamped down`() = runTest {
        startPairing { _, _ -> confirmed(connectWithinMs = 3_600_000L) }
        runCurrent()
        assertEquals(ReversePairController.MAX_CONNECT_MS, armedDeadline())
    }

    @Test
    fun `a zero connect window is clamped up`() = runTest {
        startPairing { _, _ -> confirmed(connectWithinMs = 0L) }
        runCurrent()
        assertEquals(ReversePairController.MIN_CONNECT_MS, armedDeadline())
    }

    @Test
    fun `a missing connect window falls back to the default`() = runTest {
        startPairing { _, _ -> confirmed(connectWithinMs = null) }
        runCurrent()
        assertEquals(ReversePairController.DEFAULT_CONNECT_MS, armedDeadline())
    }

    private fun armedDeadline(): Long = assertIs<TransferState.AwaitingDesktopConnect>(holder()).deadlineMs

    // ── T21: cancelling during the hang ────────────────────────────────────────────────────────

    @Test
    fun `cancelling the role during the hang cancels the call and drops the generation`() = runTest {
        var callCancelled = false
        val role = startPairing { _, _ ->
            try {
                hangForever()
            } catch (e: CancellationException) {
                callCancelled = true
                throw e
            }
        }
        assertEquals(PairingSessionRegistry.Phase.PENDING, registry.phase(FIRST_GENERATION))

        role.scope.cancel()
        runCurrent()
        assertTrue(callCancelled, "the hanging call is cancelled, which is what hangs up on the peer")
        assertNull(registry.phase(FIRST_GENERATION))
    }

    // ── T22: a re-scan supersedes the previous pairing ─────────────────────────────────────────

    @Test
    fun `a second pairing supersedes the first, whose token is then refused`() = runTest {
        val firstBody = CompletableDeferred<ReversePairRequest>()
        startPairing(capture = firstBody) { _, _ -> hangForever() }
        val firstToken = firstBody.getCompleted().pairToken

        startPairing { _, _ -> hangForever() }
        val secondToken = sentBody.getCompleted().pairToken

        assertEquals(PairingSessionRegistry.Decision.RejectPairToken, registry.authorizePrepareUpload(firstToken, macPin))
        assertEquals(PairingSessionRegistry.Decision.Accept, registry.authorizePrepareUpload(secondToken, macPin))
    }

    private fun confirmed(connectWithinMs: Long?) =
        ReversePairResponse(sessionId = "mac-session", connectWithinMs = connectWithinMs)

    private companion object {
        /** The registry hands out generations from 1; the first controller in a test owns it. */
        const val FIRST_GENERATION = 1L
    }
}
