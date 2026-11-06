package com.difft.android.chat.gif.favorite

import com.difft.android.base.user.UserData
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.network.responses.FavoritesResponse
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.fakes.FakeUserManager
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FavoriteRotationSessionTest {
    @get:Rule val dispatchers = TestDispatcherRule()

    @Test fun `old account deferred favorites response never mutates replacement metadata or key`() = runTest {
        val users = FakeUserManager(UserData(account = "old", baseAuth = "old-session"))
        val keys = FavoriteKeyRepo(users)
        val sync = mockk<FavoriteSyncRepository>(relaxed = true)
        val write = mockk<FavoriteWriteRepository>(relaxed = true)
        val gate = CompletableDeferred<FavoritesResponse?>()
        coEvery { sync.getFavorites() } coAnswers { gate.await() }
        val lifecycle = FavoriteKeyLifecycle(sync, keys, mockk<EncryptionDataManager>(), dagger.Lazy { write })
        val rotation = async { lifecycle.rewrapOnMasterKeyRotation(byteArrayOf(1), byteArrayOf(2), "old", "old-session") }
        kotlinx.coroutines.yield()
        users.setUserData(UserData(account = "new", baseAuth = "new-session"))
        gate.complete(FavoritesResponse(listVersion = 4, wrappedFavKey = "old-response"))
        rotation.await()
        verify(exactly = 0) { sync.applyServerMeta(any()) }
        coVerify(exactly = 0) { write.putRewrap(any(), any(), any()) }
        assertNull(users.getUserData()?.favKey)
    }

    @Test fun `same account replacement session rejects stale guarded key save`() {
        val users = FakeUserManager(UserData(account = "same", baseAuth = "new-session"))
        val keys = FavoriteKeyRepo(users)
        assertFalse(keys.saveForSession("same", "old-session", "id", ByteArray(FavoriteCrypto.FAV_KEY_SIZE)))
        assertNull(keys.getFavKey())
        assertTrue(keys.saveForSession("same", "new-session", "id", ByteArray(FavoriteCrypto.FAV_KEY_SIZE)))
        assertTrue(keys.hasKey())
    }
}
