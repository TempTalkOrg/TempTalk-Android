package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.setting.sync.ConversationSettingsApplier
import com.difft.android.chat.setting.sync.FakeQueue
import com.difft.android.chat.setting.sync.FinalOutcome
import com.difft.android.chat.setting.sync.ROOM
import com.difft.android.chat.setting.sync.SettingSyncState
import com.difft.android.chat.setting.sync.SyncField
import com.difft.android.chat.setting.sync.pendingSpec
import com.difft.android.chat.setting.sync.room
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.NetworkException
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.network.responses.GetConversationSetResponseBody
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase machine of [SettingSyncJob] (design §3.2) including the fault-injection cases from the
 * cross-platform review: never SET again after acceptance, superseded jobs and the accepted-version
 * floor, bounded reconcile, and the error classification by HTTP code.
 */
class SettingSyncJobTest {

    private val field = SyncField.CONFIDENTIAL_MODE
    private val q = FakeQueue()
    private val dbRoomStore: DBRoomStore = mockk(relaxed = true)
    private val applier: ConversationSettingsApplier = mockk()
    private val httpClient: ChativeHttpClient = mockk()
    private val httpService: HttpService = mockk()

    @Before
    fun setUp() {
        every { httpClient.httpService } returns httpService
        every { dbRoomStore.getRoom(ROOM) } returns room(confidential = 1, version = 10)
        q.queue()
    }

    private fun job(
        target: Int = 1,
        ts: Long = 1_000,
        phase: SettingSyncJob.Phase = SettingSyncJob.Phase.SEND,
        acceptedVersion: Int = 0,
    ) = SettingSyncJob(
        parameters = null,
        roomId = ROOM,
        field = field,
        target = target,
        ts = ts,
        initialPhase = phase,
        initialAcceptedVersion = acceptedVersion,
        httpClient = httpClient,
        dbRoomStore = dbRoomStore,
        applier = applier,
        syncState = q.state,
    )

    private fun acceptSet(version: Int) {
        coEvery { httpService.fetchConversationSet(any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = ConversationSetResponseBody(conversation = ROOM, version = version))
    }

    private fun getReturns(vararg versions: Int) {
        val responses = versions.map { v ->
            BaseResponse(ver = 1, status = 0, reason = null, data = GetConversationSetResponseBody(listOf(ConversationSetResponseBody(conversation = ROOM, version = v))))
        }
        coEvery { httpService.fetchGetConversationSet(any()) } returnsMany responses
    }

    /** A phase persist: a retry with the minimum positive backoff `JobController.onRetry` accepts. */
    private fun Job.Result.isImmediateRetry() = isRetry() && getBackoffInterval() == SettingSyncJob.PHASE_PERSIST_BACKOFF_MS

    // --- parameters & serialisation ---

    @Test
    fun `parameters are per room-and-field queue, immortal, unlimited, network constrained`() {
        val p = job().parameters
        assertEquals(SettingSyncState.queueKey(ROOM, field), p.queue)
        assertEquals(Job.Parameters.IMMORTAL, p.lifespan)
        assertEquals(Job.Parameters.UNLIMITED, p.maxAttempts)
        assertTrue(p.constraintKeys.contains(NetworkConstraint.KEY))
    }

    @Test
    fun `phase persist uses a positive backoff, as JobController onRetry requires`() {
        assertTrue(SettingSyncJob.PHASE_PERSIST_BACKOFF_MS > 0)
    }

    @Test
    fun `phase and accepted version survive serialisation`() = runTest {
        acceptSet(version = 11)
        val j = job()
        assertTrue(j.run().isImmediateRetry())

        val data = j.serialize()
        assertEquals(SettingSyncJob.Phase.RECONCILE.name, data.getString(SettingSyncJob.KEY_PHASE))
        assertEquals(11, data.getInt(SettingSyncJob.KEY_ACCEPTED_VERSION))
        assertEquals(1_000L, data.getLong(SettingSyncJob.KEY_TS))
    }

    // --- SEND ---

    @Test
    fun `accepted SET persists RECONCILE and the echoed version without writing anything locally`() = runTest {
        acceptSet(version = 11)
        val j = job()

        assertTrue(j.run().isImmediateRetry())

        assertEquals(SettingSyncJob.Phase.RECONCILE, j.phase)
        assertEquals(11, j.acceptedVersion)
        coVerify(exactly = 0) { applier.applyFinal(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dbRoomStore.updateConversationSettings(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rejection by body status or plain 4xx moves to ROLLBACK, auth and server errors retry in SEND`() = runTest {
        coEvery { httpService.fetchConversationSet(any()) } returns BaseResponse(ver = 1, status = 10001, reason = "no", data = null)
        job().let { assertTrue(it.run().isImmediateRetry()); assertEquals(SettingSyncJob.Phase.ROLLBACK, it.phase) }

        coEvery { httpService.fetchConversationSet(any()) } throws NetworkException(400, "bad")
        job().let { assertTrue(it.run().isImmediateRetry()); assertEquals(SettingSyncJob.Phase.ROLLBACK, it.phase) }

        for (code in listOf(401, 403, 429, 500, 0, -1)) {
            coEvery { httpService.fetchConversationSet(any()) } throws NetworkException(code, "transient")
            job().let { r -> val result = r.run(); assertTrue(result.isRetry() && result.getBackoffInterval() > 0, "code $code"); assertEquals(SettingSyncJob.Phase.SEND, r.phase) }
        }
        coEvery { httpService.fetchConversationSet(any()) } throws IOException("offline")
        job().let { assertTrue(it.run().getBackoffInterval() > 0); assertEquals(SettingSyncJob.Phase.SEND, it.phase) }
    }

    @Test
    fun `server already won moves to RECONCILE without a SET`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns room(confidential = 0, version = 10)
        val j = job(target = 1)
        assertTrue(j.run().isImmediateRetry())
        assertEquals(SettingSyncJob.Phase.RECONCILE, j.phase)
        coVerify(exactly = 0) { httpService.fetchConversationSet(any()) }
    }

    @Test
    fun `expired job rolls back instead of sending`() = runTest {
        val j = job()
        j.now = { j.parameters.createTime + SettingSyncJob.EXPIRY_MS + 1 }
        assertTrue(j.run().isImmediateRetry())
        assertEquals(SettingSyncJob.Phase.ROLLBACK, j.phase)
        coVerify(exactly = 0) { httpService.fetchConversationSet(any()) }
    }

    // --- superseded ---

    @Test
    fun `superseded job without an accepted write completes at once in any phase`() = runTest {
        for (phase in SettingSyncJob.Phase.entries) {
            val j = job(phase = phase)
            q.queue(pendingSpec(j.id, ts = 1_000), pendingSpec("b", ts = 2_000))
            assertTrue(j.run().isSuccess(), "phase $phase")
        }
        coVerify(exactly = 0) { httpService.fetchConversationSet(any()) }
        coVerify(exactly = 0) { httpService.fetchGetConversationSet(any()) }
    }

    @Test
    fun `superseded job with an accepted write completes without GET once the local version reached its floor`() = runTest {
        // v10 review: the floor is already persisted; GET being unavailable must not block the newer job.
        every { dbRoomStore.getRoom(ROOM) } returns room(confidential = 0, version = 11)
        val j = job(phase = SettingSyncJob.Phase.RECONCILE, acceptedVersion = 11)
        q.queue(pendingSpec(j.id, ts = 1_000), pendingSpec("b", ts = 2_000))
        coEvery { httpService.fetchGetConversationSet(any()) } throws IOException("down")

        assertTrue(j.run().isSuccess())
    }

    @Test
    fun `superseded job with an accepted write first raises the local version to its floor`() = runTest {
        // v9 review: A accepted V11, B replaced it; A must not vanish while the row still says V10.
        val j = job(phase = SettingSyncJob.Phase.RECONCILE, acceptedVersion = 11)
        q.queue(pendingSpec(j.id, ts = 1_000), pendingSpec("b", ts = 2_000))

        coEvery { httpService.fetchGetConversationSet(any()) } throws IOException("down")
        assertTrue(j.run().isRetry(), "blocked while GET is down (accepted trade-off)")

        getReturns(11)
        coEvery { applier.applyFinal(match { it.version == 11 }, j.id, 1_000, 11) } returns FinalOutcome.SUPERSEDED
        assertTrue(j.run().isSuccess())
        coVerify(exactly = 0) { httpService.fetchConversationSet(any()) }
    }

    // --- RECONCILE / ROLLBACK ---

    @Test
    fun `RECONCILE never SETs again and keeps retrying while GET lags behind the accepted version`() = runTest {
        // v8 review: SET returned V11, GET keeps returning V10 → neither complete nor roll back.
        val j = job(phase = SettingSyncJob.Phase.RECONCILE, acceptedVersion = 11)
        getReturns(10, 10, 11)
        coEvery { applier.applyFinal(match { it.version == 10 }, j.id, 1_000, 11) } returns FinalOutcome.NEEDS_RECONCILE
        coEvery { applier.applyFinal(match { it.version == 11 }, j.id, 1_000, 11) } returns FinalOutcome.COMPLETED

        val first = j.run()
        assertTrue(first.isRetry() && first.getBackoffInterval() > 0)
        assertEquals(SettingSyncJob.Phase.RECONCILE, j.phase)
        assertEquals(11, j.acceptedVersion)
        coVerify(exactly = 2) { httpService.fetchGetConversationSet(any()) }

        assertTrue(j.run().isSuccess())
        coVerify(exactly = 0) { httpService.fetchConversationSet(any()) }
    }

    @Test
    fun `RECONCILE GET failure retries without a SET and keeps the phase`() = runTest {
        val j = job(phase = SettingSyncJob.Phase.RECONCILE, acceptedVersion = 11)
        coEvery { httpService.fetchGetConversationSet(any()) } throws IOException("down")

        assertTrue(j.run().getBackoffInterval() > 0)
        assertEquals(SettingSyncJob.Phase.RECONCILE, j.phase)
        coVerify(exactly = 0) { httpService.fetchConversationSet(any()) }
    }

    @Test
    fun `ROLLBACK fetches and lets the applier roll the field back`() = runTest {
        val j = job(phase = SettingSyncJob.Phase.ROLLBACK)
        getReturns(10)
        coEvery { applier.applyFinal(match { it.version == 10 }, j.id, 1_000, 0) } returns FinalOutcome.COMPLETED

        assertTrue(j.run().isSuccess())
        coVerify(exactly = 0) { httpService.fetchConversationSet(any()) }
    }

    @Test
    fun `GET body error is transient`() = runTest {
        val j = job(phase = SettingSyncJob.Phase.RECONCILE, acceptedVersion = 11)
        coEvery { httpService.fetchGetConversationSet(any()) } returns BaseResponse(ver = 1, status = 10001, reason = "x", data = null)
        assertTrue(j.run().getBackoffInterval() > 0)
        assertFalse(j.run().isSuccess())
    }

    @Test
    fun `room gone completes in every phase`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns null
        for (phase in SettingSyncJob.Phase.entries) assertTrue(job(phase = phase).run().isSuccess())
    }
}
