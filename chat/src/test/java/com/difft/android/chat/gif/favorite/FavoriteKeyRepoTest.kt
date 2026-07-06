package com.difft.android.chat.gif.favorite

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the UserManager-backed [FavoriteKeyRepo] storage (favKey stored in the encrypted
 * secure_user store, not WCDB). v2: the repo simply caches the unwrapped favKey (keyId + raw key)
 * — no version gate, no distribution — so this covers the save/read round-trip and the
 * no-key-stored case.
 *
 * Runs under Robolectric because the favKey is stored Base64.NO_WRAP via android.util.Base64.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class FavoriteKeyRepoTest {

    /**
     * In-memory UserManager mirroring :base FakeUserManager but local to the chat module (the
     * testFixtures Kotlin compilation gap). Uses the real `UserManager.update {}` default so the
     * copy-then-set funnel is exercised.
     */
    private class InMemoryUserManager(
        private var data: UserData? = UserData()
    ) : UserManager {
        override fun setUserData(userData: UserData, commit: Boolean) {
            this.data = userData
        }

        override fun getUserData(): UserData? = data
    }

    private lateinit var userManager: InMemoryUserManager
    private lateinit var repo: FavoriteKeyRepo

    private fun key(byte: Byte) = ByteArray(FavoriteCrypto.FAV_KEY_SIZE) { byte }

    @Before
    fun setUp() {
        userManager = InMemoryUserManager()
        repo = FavoriteKeyRepo(userManager)
    }

    @Test
    fun `getFavKey null when no key stored`() = runTest {
        assertNull(repo.getFavKey())
        assertFalse(repo.hasKey())
    }

    @Test
    fun `save then getFavKey round-trips keyId and key`() = runTest {
        repo.save("kid-1", key(7))

        val entry = repo.getFavKey()
        assertEquals("kid-1", entry?.keyId)
        assertTrue(key(7).contentEquals(entry?.favKey))
        assertTrue(repo.hasKey())
    }

    @Test
    fun `save overwrites the cached key`() = runTest {
        repo.save("kid-1", key(1))
        repo.save("kid-2", key(2))
        val entry = repo.getFavKey()
        assertEquals("kid-2", entry?.keyId)
        assertTrue(key(2).contentEquals(entry?.favKey))
        assertEquals("kid-2", userManager.getUserData()?.favKeyId)
    }

    @Test
    fun `save rejects empty keyId or wrong key size`() = runTest {
        assertFailsWithIllegalArg { repo.save("", key(1)) }
        assertFailsWithIllegalArg { repo.save("kid", ByteArray(8)) }
        assertNull(userManager.getUserData()?.favKeyId)
    }

    private inline fun assertFailsWithIllegalArg(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
