package com.difft.android.chat.crypto

import com.difft.android.PushGroupKeySendJobFactory
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.JobManager
import com.difft.android.chat.jobmanager.QueueAdmission
import com.difft.android.chat.jobs.PushGroupKeySendJob
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class GroupKeyAdmissionTest {
    @get:Rule val globals = GlobalStaticMockRule("self")
    private val crypto = mockk<GroupCryptoRepo>()
    private val factory = mockk<PushGroupKeySendJobFactory>()
    private val manager = mockk<JobManager>()
    private val distributor = GroupKeyDistributor(crypto, factory)

    @Before fun setup() {
        mockkObject(ApplicationDependencies)
        io.mockk.mockkStatic(ApplicationDependencies::class)
        every { ApplicationDependencies.getJobManager() } returns manager
    }
    @After fun cleanup() { io.mockk.unmockkStatic(ApplicationDependencies::class); unmockkObject(ApplicationDependencies) }

    @Test fun `plain and encrypted missing key have distinct preparation outcomes`() {
        every { crypto.getRGroupBytes("group") } returns null
        assertEquals(GroupKeyDistributor.Preparation.NotRequired, distributor.prepareMembers("group", listOf("other"), false, "login-a"))
        assertEquals(GroupKeyDistributor.Preparation.KeyUnavailable, distributor.prepareMembers("group", listOf("other"), true, "login-a"))
        verify(exactly = 0) { factory.create(any(), any(), any(), any()) }
    }

    @Test fun `partial retry skips persisted recipient and keeps failed job instance and ID`(): Unit = runBlocking {
        every { crypto.getRGroupBytes("group") } returns ByteArray(32)
        val first = mockk<PushGroupKeySendJob> { every { id } returns "job-first" }
        val second = mockk<PushGroupKeySendJob> { every { id } returns "job-second" }
        every { factory.create(null, "group", "first", false) } returns first
        every { factory.create(null, "group", "second", false) } returns second
        val pending = assertIs<GroupKeyDistributor.Preparation.Ready>(
            distributor.prepareMembers("group", listOf("self", "first", "second", "first"), true, "login-a")).pending
        coEvery { manager.enqueuePersisted(first, "login-a") } returns QueueAdmission.Persisted("job-first")
        coEvery { manager.enqueuePersisted(second, "login-a") } returnsMany listOf(
            QueueAdmission.Unconfirmed("job-second", "io"), QueueAdmission.Persisted("job-second"))
        assertIs<QueueAdmission.Unconfirmed>(distributor.enqueueMembersPersisted(pending)["second"])
        assertEquals(mapOf("first" to QueueAdmission.Persisted("job-first"),
            "second" to QueueAdmission.Persisted("job-second")), distributor.enqueueMembersPersisted(pending))
        assertSame(second, pending.jobs["second"])
        coVerify(exactly = 1) { manager.enqueuePersisted(first, "login-a") }
        coVerify(exactly = 2) { manager.enqueuePersisted(second, "login-a") }
        verify(exactly = 2) { factory.create(any(), any(), any(), any()) }
    }

    @Test fun `key loss after preparation leaves unadmitted recipients available for local retry`(): Unit = runBlocking {
        every { crypto.getRGroupBytes("group") } returns null
        val job = mockk<PushGroupKeySendJob> { every { id } returns "waiting-key" }
        val pending = GroupKeyDistributor.PendingMembers("group", "login-a", mapOf("other" to job))
        assertEquals(QueueAdmission.FailedBeforePersist("key_unavailable"), distributor.enqueueMembersPersisted(pending)["other"])
        coVerify(exactly = 0) { manager.enqueuePersisted(any(), "login-a") }
        every { crypto.getRGroupBytes("group") } returns ByteArray(32)
        coEvery { manager.enqueuePersisted(job, "login-a") } returns QueueAdmission.Persisted("waiting-key")
        assertIs<QueueAdmission.Persisted>(distributor.enqueueMembersPersisted(pending)["other"])
    }

    @Test fun `switch during first awaited recipient never rebinds later jobs to the new login`(): Unit = runBlocking {
        every { crypto.getRGroupBytes("group") } returns ByteArray(32)
        val first = mockk<PushGroupKeySendJob> { every { id } returns "first" }
        val second = mockk<PushGroupKeySendJob> { every { id } returns "second" }
        val pending = GroupKeyDistributor.PendingMembers("group", "login-a", linkedMapOf("one" to first, "two" to second))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { manager.enqueuePersisted(first, "login-a") } coAnswers {
            entered.complete(Unit)
            release.await()
            QueueAdmission.Persisted("first")
        }
        coEvery { manager.enqueuePersisted(second, "login-a") } returns QueueAdmission.Persisted("second")
        val operation = async { distributor.enqueueMembersPersisted(pending) }
        entered.await()
        every { globals.mockGlobalServices.myId } returns "new-account"
        release.complete(Unit)
        operation.await()
        coVerify(exactly = 1) { manager.enqueuePersisted(second, "login-a") }
        coVerify(exactly = 0) { manager.enqueuePersisted(any()) }
    }

    @Test fun `recipient exception does not prevent later admission attempts`(): Unit = runBlocking {
        every { crypto.getRGroupBytes("group") } returns ByteArray(32)
        val first = mockk<PushGroupKeySendJob> { every { id } returns "first" }
        val second = mockk<PushGroupKeySendJob> { every { id } returns "second" }
        val pending = GroupKeyDistributor.PendingMembers("group", "login-a", linkedMapOf("one" to first, "two" to second))
        coEvery { manager.enqueuePersisted(first, "login-a") } throws IllegalStateException("manager")
        coEvery { manager.enqueuePersisted(second, "login-a") } returns QueueAdmission.Persisted("second")
        val result = distributor.enqueueMembersPersisted(pending)
        assertIs<QueueAdmission.Unconfirmed>(result["one"])
        assertIs<QueueAdmission.Persisted>(result["two"])
    }
}
