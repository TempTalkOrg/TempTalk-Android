package com.difft.android.chat.group

import com.difft.android.base.user.UserManager
import com.difft.android.network.group.GroupRepo
import difft.android.messageserialization.MessageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.difft.app.database.WCDB
import io.mockk.every
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupModel
import kotlin.test.assertEquals
import org.junit.Test
import kotlin.test.assertFalse


class GroupUtilRefreshAllNowTest {
    private val repo = mockk<GroupRepo>()
    private val util = GroupUtil(repo, mockk<MessageStore>(), mockk<WCDB>(), mockk<UserManager>(relaxed = true), mockk(),
        mockk(), GroupExpiryTracker()).apply { listClock = { 0L } }

    @Test fun `a pull during a running full sync waits for it instead of skipping or restarting it`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { repo.getGroups() } coAnswers {
            entered.complete(Unit)
            release.await()
            error("offline")
        }
        coroutineScope {
            val running = async(Dispatchers.Default) { util.syncAllGroupAndAllGroupMembers(forceFetch = true) }
            withTimeout(5_000) { entered.await() }
            val pull = async(Dispatchers.Default) { util.refreshAllNow() }
            delay(200)
            assertFalse(pull.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) {
                running.await()
                pull.await()
            }
        }
        coVerify(exactly = 1) { repo.getGroups() }
    }

    @Test fun `a pull during a sync that will skip the server runs its own forced sync after it`(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val userManager = mockk<UserManager>(relaxed = true)
        every { userManager.getUserData() } answers {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            mockk(relaxed = true) { every { syncedGroupAndMembers } returns true }
        }
        val util = GroupUtil(repo, mockk<MessageStore>(), mockk<WCDB>(), userManager, mockk(), mockk(), GroupExpiryTracker())
            .apply { listClock = { 0L } }
        coEvery { repo.getGroups() } coAnswers { error("offline") }
        coroutineScope {
            val running = async(Dispatchers.Default) { util.syncAllGroupAndAllGroupMembers(forceFetch = false) }
            withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }
            val pull = async(Dispatchers.Default) { util.refreshAllNow() }
            delay(200)
            release.countDown()
            withTimeout(5_000) {
                running.await()
                pull.await()
            }
        }
        coVerify(exactly = 1) { repo.getGroups() }
    }

    @Test fun `the contacts tab list writes only deadlines, never the group table or the member flag`(): Unit = runBlocking {
        val userManager = mockk<UserManager>(relaxed = true)
        val wcdb = mockk<WCDB>(relaxed = true)
        val written = mutableListOf<List<String?>>()
        val announced = mutableListOf<String>()
        val util = GroupUtil(repo, mockk<MessageStore>(), wcdb, userManager, mockk(), mockk(), GroupExpiryTracker())
            .apply {
                listClock = { 0L }
                writeDeadlines = { groups -> written += groups.map { it.gid }; groups.mapNotNull { it.gid } }
                announceStoredGroup = { announced += it }
            }
        coEvery { repo.getGroups() } returns listOf(GroupModel().apply { gid = "a"; version = 11; expireTime = 1L })
        util.syncGroupListIfStale()
        assertEquals(listOf(listOf<String?>("a")), written)
        
        assertEquals(listOf("a"), announced)
        verify(exactly = 0) { wcdb.group }
        verify(exactly = 0) { userManager.update(any(), any()) }
    }

    @Test fun `the contacts tab fetches the group list once per ten minutes and retries after a failure`(): Unit =
        runBlocking {
            var now = 0L
            val util = GroupUtil(repo, mockk<MessageStore>(), mockk<WCDB>(relaxed = true), mockk<UserManager>(relaxed = true),
                mockk(), mockk(), GroupExpiryTracker()).apply { listClock = { now }; writeDeadlines = { emptyList() } }
            coEvery { repo.getGroups() } throws IllegalStateException("offline")
            util.syncGroupListIfStale()
            coEvery { repo.getGroups() } returns emptyList()
            util.syncGroupListIfStale()
            now += TimeUnit.MINUTES.toNanos(9)
            util.syncGroupListIfStale()
            coVerify(exactly = 2) { repo.getGroups() }
            now += TimeUnit.MINUTES.toNanos(2)
            util.syncGroupListIfStale()
            coVerify(exactly = 3) { repo.getGroups() }
        }

    @Test fun `an idle pull runs a forced full sync now`(): Unit = runBlocking {
        coEvery { repo.getGroups() } coAnswers { error("offline") }
        withTimeout(5_000) { util.refreshAllNow() }
        coVerify(exactly = 1) { repo.getGroups() }
    }
}
