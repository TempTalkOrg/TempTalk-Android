package com.difft.android.chat.group

import com.difft.android.base.migration.pause.MigrationPauseScope
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.network.BaseResponse
import com.difft.android.network.group.GetGroupInfoResp
import com.difft.android.network.group.GroupRepo
import difft.android.messageserialization.MessageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.confirmVerified
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.difft.app.database.WCDB
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull


class GroupUtilSessionGuardTest {
    @Test fun `response from ended account is discarded before any local database access`(): Unit = runBlocking {
        assertDiscardedAfterChange("other-account", "fixture-b")
    }

    @Test fun `same account with another credential is also discarded before local writes`(): Unit = runBlocking {
        assertDiscardedAfterChange("owner", "fixture-b")
    }

    private suspend fun assertDiscardedAfterChange(nextAccount: String, nextCredential: String) {
        val repo = mockk<GroupRepo>()
        val database = mockk<WCDB>()
        val userManager = mockk<UserManager>()
        val memberWriter = mockk<GroupMemberWriter>()
        var user = UserData(account = "owner", baseAuth = "fixture-a")
        every { userManager.getUserData() } answers { user }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { repo.getGroupInfo("group") } coAnswers {
            entered.complete(Unit)
            release.await()
            BaseResponse<GetGroupInfoResp>(1, 0, null, null)
        }
        val util = GroupUtil(repo, mockk<MessageStore>(), database, userManager, mockk(), memberWriter, GroupExpiryTracker())
        val session = GroupWriteSession("owner", MigrationPauseScope.credentialHash("fixture-a"))
        kotlinx.coroutines.coroutineScope {
            val result = async(Dispatchers.Default) { util.fetchAndSaveSingleGroupInfo("group", true, session) }
            withTimeout(5_000) { entered.await() }
            user = UserData(account = nextAccount, baseAuth = nextCredential)
            release.complete(Unit)
            assertNull(withTimeout(5_000) { result.await() })
        }
        confirmVerified(database)
        coVerify(exactly = 0) { memberWriter.replaceAllForGroup(any(), any(), any()) }
    }

    @Test fun `member writer checks authorization inside actual IO context before global database access`(): Unit = runBlocking {
        val caller = Thread.currentThread()
        assertFailsWith<IllegalStateException> {
            GroupMemberWriter().replaceAllForGroup("group", emptyList()) {
                assertNotEquals(caller, Thread.currentThread())
                false
            }
        }
    }

    @Test fun `detached verification rejects an ended session before reading or changing any member`(): Unit = runBlocking {
        val caller = Thread.currentThread()
        assertFailsWith<IllegalStateException> {
            GroupCryptoRepo().verifyAllPendingForGroup("group", mockk()) {
                assertNotEquals(caller, Thread.currentThread())
                false
            }
        }
    }
}
