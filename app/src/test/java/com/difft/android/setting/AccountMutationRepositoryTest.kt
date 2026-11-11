package com.difft.android.setting

import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.LogoutManager
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.setting.repo.AccountMutationRepository
import com.difft.android.setting.repo.AccountMutationResult
import com.difft.android.setting.repo.SettingRepo
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import java.io.IOException

class AccountMutationRepositoryTest {
    @get:Rule(order = 0) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 1) val globals = GlobalStaticMockRule()
    private val settings = mockk<SettingRepo>()
    private val users = mockk<StorageBoundUserManager>(relaxed = true)
    private val service = mockk<HttpService>()
    private val client = mockk<ChativeHttpClient> { every { httpService } returns service }
    private val logout = mockk<LogoutManager>(relaxed = true)
    private fun success() = BaseResponse<Any>(1, 0, null, null)

    @Test fun `accepted delete releases original wait synchronously before logout after page cancellation`() = runTest {
        val gate = CompletableDeferred<BaseResponse<Any>>()
        val ended = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        coEvery { service.fetchDeleteAccount() } coAnswers { gate.await() }
        every { logout.doLogout() } answers { events += "logout"; ended.complete(Unit); Unit }
        val repository = AccountMutationRepository(settings, users, client, logout, backgroundScope)
        val caller = async { repository.deleteAccount { events += "dismiss-original" } }
        
        while (!com.difft.android.base.utils.pane.PageOperations.running("delete-account:test-user-001").value) kotlinx.coroutines.yield()
        caller.cancelAndJoin()
        gate.complete(success())
        ended.await()
        assertEquals(listOf("dismiss-original", "logout"), events)
        coVerify(exactly = 1) { service.fetchDeleteAccount() }
    }

    @Test fun `rejected delete does not logout and remote logout failure still logs out locally`() = runTest {
        coEvery { service.fetchDeleteAccount() } returns BaseResponse(1, 12, "rejected", null)
        coEvery { service.fetchLogout() } throws IOException("offline")
        val events = mutableListOf<String>()
        every { logout.doLogout() } answers { events += "logout" }
        val repository = AccountMutationRepository(settings, users, client, logout, backgroundScope)
        assertIs<AccountMutationResult.Rejected>(repository.deleteAccount { events += "delete-dismiss" })
        assertEquals(emptyList(), events)
        assertIs<AccountMutationResult.Applied>(repository.logout { events += "logout-dismiss" })
        assertEquals(listOf("logout-dismiss", "logout"), events)
    }

    @Test fun `late accepted delete cannot logout a replacement account`() = runTest {
        val gate = CompletableDeferred<BaseResponse<Any>>()
        coEvery { service.fetchDeleteAccount() } coAnswers { gate.await() }
        val repository = AccountMutationRepository(settings, users, client, logout, backgroundScope)
        val result = async { repository.deleteAccount { error("must not dismiss new account") } }
        while (!com.difft.android.base.utils.pane.PageOperations.running("delete-account:test-user-001").value) kotlinx.coroutines.yield()
        every { globals.mockGlobalServices.myId } returns "replacement"
        gate.complete(success())
        assertIs<AccountMutationResult.StaleAccount>(result.await())
        io.mockk.verify(exactly = 0) { logout.doLogout() }
    }
}
