package com.difft.android.login.entry

import androidx.datastore.core.DataStore
import com.difft.android.base.storage.SecureConfigStore
import com.difft.android.base.storage.schema.GlobalConfigData
import com.difft.android.login.viewmodel.LoginViewModel.PendingSignIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The held sign-in is read back once, exactly as saved, and never when it has gone stale. */
class PendingLoginStoreTest {

    private class MemoryConfig : DataStore<GlobalConfigData> {
        val state = MutableStateFlow(GlobalConfigData.EMPTY)
        override val data: Flow<GlobalConfigData> get() = state
        override suspend fun updateData(transform: suspend (t: GlobalConfigData) -> GlobalConfigData): GlobalConfigData {
            state.value = transform(state.value)
            return state.value
        }
    }

    private val config = MemoryConfig()
    private var now = 1_000_000L
    private val store = PendingLoginStore(SecureConfigStore(config)).apply { clock = { now } }
    private val pending = PendingSignIn("", "+12025550100", email = null, phone = "+12025550100", renewToken = "tok-1")

    @Test
    fun `a saved sign-in comes back once, intact, and is gone afterwards`() = runTest {
        store.save(pending)
        assertEquals(pending, store.consume())
        assertNull(store.consume())
        assertEquals("", config.state.value.pendingLogin)
    }

    @Test
    fun `a sign-in older than the lifetime is dropped, not returned`() = runTest {
        store.save(pending)
        now += PendingLoginStore.TTL_MS + 1
        assertNull(store.consume())
        assertEquals("", config.state.value.pendingLogin)
    }

    @Test
    fun `a clock that went backwards past the save is treated as stale`() = runTest {
        store.save(pending)
        now -= 1
        assertNull(store.consume())
    }

    @Test
    fun `unreadable content is dropped and cleared`() = runTest {
        config.updateData { it.copy(pendingLogin = "{ not json") }
        assertNull(store.consume())
        assertEquals("", config.state.value.pendingLogin)
    }

    @Test
    fun `clear forgets a held sign-in`() = runTest {
        store.save(pending)
        store.clear()
        assertNull(store.consume())
    }
}
