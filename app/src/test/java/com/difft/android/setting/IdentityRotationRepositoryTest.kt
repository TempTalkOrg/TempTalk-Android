package com.difft.android.setting

import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserData
import com.difft.android.chat.crypto.IdentityKeyUtil
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.chat.gif.favorite.FavoriteWriteRepository
import com.difft.android.login.repo.LoginRepo
import com.difft.android.network.BaseResponse
import com.difft.android.setting.repo.AccountMutationResult
import com.difft.android.setting.repo.IdentityRotationRepository
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdentityRotationRepositoryTest {
    @get:Rule(order = 0) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 1) val globals = GlobalStaticMockRule()
    private val login = mockk<LoginRepo>()
    private val encryption = mockk<EncryptionDataManager>()
    private val users = mockk<StorageBoundUserManager>()
    private val favorites = mockk<FavoriteWriteRepository>(relaxed = true)
    private val old = mockk<IdentityKeyPair>(relaxed = true)
    private val next = mockk<IdentityKeyPair>(relaxed = true)

    private fun keys() {
        every { users.getUserData() } returns UserData(account = "test-user-001")
        mockkObject(IdentityKeyUtil)
        every { IdentityKeyUtil.generateIdentityKeyPair() } returns next
        every { encryption.getAciIdentityKey() } returns old
        every { next.publicKey.serialize() } returns byteArrayOf(1)
        every { next.privateKey.serialize() } returns byteArrayOf(2)
        every { old.publicKey.serialize() } returns byteArrayOf(3)
        every { old.privateKey.serialize() } returns byteArrayOf(4)
        every { next.privateKey.calculateSignature(any()) } returns byteArrayOf(5)
        every { old.privateKey.calculateSignature(any()) } returns byteArrayOf(6)
    }
    @After fun cleanup() { unmockkObject(IdentityKeyUtil) }

    @Test fun `accepted local failure retains candidate and retry completes locally in original order`() = runTest {
        keys()
        coEvery { login.renewIdentityKey(any()) } returns BaseResponse(1, 0, null, null)
        var current = UserData(account = "test-user-001")
        val committed = mutableListOf<UserData>()
        var attempts = 0
        coEvery { users.commitStrict(any(), any()) } coAnswers {
            attempts++
            if (attempts == 2) throw IOException("disk")
            current = current.copy().apply(secondArg<UserData.() -> Unit>())
            committed += current.copy()
        }
        val repository = IdentityRotationRepository(login, encryption, users, favorites, backgroundScope)
        assertIs<AccountMutationResult.AcceptedLocalCommitFailed>(repository.renewOrComplete())
        assertIs<AccountMutationResult.Applied>(repository.renewOrComplete())
        coVerify(exactly = 1) { login.renewIdentityKey(any()) }
        verify(exactly = 1) { IdentityKeyUtil.generateIdentityKeyPair() }
        assertEquals(null, committed[0].aciIdentityPrivateKey)
        assertEquals(true, committed[0].aciIdentityOldPrivateKey != null)
        assertEquals(0L, committed[1].aciIdentityKeyGenTime)
        assertEquals(true, committed.last().aciIdentityKeyGenTime > 0L)
        coVerify(exactly = 1) { favorites.rewrapOnMasterKeyRotation(byteArrayOf(4), byteArrayOf(2), "test-user-001", null) }
    }

    @Test fun `singleflight is acquired before any competing key generation`() = runTest {
        keys()
        val gate = CompletableDeferred<BaseResponse<Any>>()
        val entered = CompletableDeferred<Unit>()
        coEvery { login.renewIdentityKey(any()) } coAnswers { entered.complete(Unit); gate.await() }
        coEvery { users.commitStrict(any(), any()) } just Runs
        val repository = IdentityRotationRepository(login, encryption, users, favorites, backgroundScope)
        val first = async { repository.renewOrComplete() }
        entered.await()
        assertIs<AccountMutationResult.Busy>(repository.renewOrComplete())
        verify(exactly = 1) { IdentityKeyUtil.generateIdentityKeyPair() }
        gate.complete(BaseResponse(1, 0, null, null))
        assertIs<AccountMutationResult.Applied>(first.await())
    }
}
