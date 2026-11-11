package com.difft.android.base.storage.user

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.user.UserData
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StrictUserCommitTest {
    @Test
    fun `accepted memory survives disk failure and unchanged retry persists`() = runBlocking {
        val auth = Store(UserAuthData.EMPTY)
        val manager = createManager(auth)
        manager.setUserData(UserData(account = "a", baseAuth = "session"), commit = true)
        auth.failure.set(IOException("disk"))
        assertFailsWith<IOException> { manager.commitStrict("a") { customUid = "accepted" } }
        assertEquals("accepted", manager.getUserData()?.customUid)
        assertEquals("", auth.value.value.customUid)
        auth.failure.set(null)
        manager.commitStrict("a") { customUid = "accepted" }
        assertEquals("accepted", auth.value.value.customUid)
    }

    @Test
    fun `legacy write after strict retains candidate and its own change`() = runBlocking {
        val auth = Store(UserAuthData.EMPTY)
        val manager = createManager(auth)
        manager.setUserData(UserData(account = "a", baseAuth = "session"), commit = true)
        val gate = Gate()
        auth.gate.set(gate)
        val strict = async(Dispatchers.Default) { manager.commitStrict("a") { customUid = "accepted" } }
        withTimeout(5_000) { gate.entered.await() }
        manager.update { email = "masked" }
        gate.release.complete(Unit)
        strict.await()
        
        manager.commitStrict("a") { }
        assertEquals("accepted", auth.value.value.customUid)
        assertEquals("masked", auth.value.value.email)
    }

    @Test
    fun `logout queued while legacy predecessor suspends prevents stale strict resurrection`() = runBlocking {
        val auth = Store(UserAuthData.EMPTY)
        val manager = createManager(auth)
        manager.setUserData(UserData(account = "a", baseAuth = "session"), commit = true)
        val gate = Gate()
        auth.gate.set(gate)
        manager.update { email = "prior" }
        withTimeout(5_000) { gate.entered.await() }
        val strict = async(Dispatchers.Default) { runCatching { manager.commitStrict("a") { customUid = "accepted" } } }
        withTimeout(5_000) { while (manager.getUserData()?.customUid != "accepted") yield() }
        val logout = async(Dispatchers.Default) { manager.clearAll() }
        withTimeout(5_000) { while (manager.getUserData()?.account == "a") yield() }
        gate.release.complete(Unit)
        assertEquals(true, strict.await().isFailure)
        logout.await()
        assertEquals("", auth.value.value.account)
        assertEquals("", auth.value.value.baseAuth)
    }

    @Test
    fun `strict snapshot persists default reset one to zero to real preference DataStore`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "strict-default-${System.nanoTime()}.preferences_pb")
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val state = PreferenceDataStoreFactory.create(scope = diskScope, produceFile = { file })
            val auth = Store(UserAuthData.EMPTY)
            val manager = createManager(auth, state)
            manager.setUserData(UserData(account = "a", baseAuth = "session", searchByCustomUid = 1), commit = true)
            assertEquals(1, state.data.first()[AppStateKeys.SEARCH_BY_CUSTOM_UID])
            manager.commitStrict("a") { searchByCustomUid = 0 }
            assertEquals(0, manager.getUserData()?.searchByCustomUid)
            assertEquals(0, state.data.first()[AppStateKeys.SEARCH_BY_CUSTOM_UID])
            val restarted = createManager(auth, state)
            restarted.warmUp()
            assertEquals(0, restarted.getUserData()?.searchByCustomUid)
        } finally { diskScope.cancel(); file.delete() }
    }

    private fun createManager(auth: DataStore<UserAuthData>, state: DataStore<Preferences> = Store(emptyPreferences())) = StorageBoundUserManagerImpl(
        auth, state, ApplicationProvider.getApplicationContext<Context>(), Gson(),
    )
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }
    private class Store<T>(initial: T) : DataStore<T> {
        val value = MutableStateFlow(initial)
        override val data = value
        val failure = AtomicReference<Exception?>(null)
        val gate = AtomicReference<Gate?>(null)
        override suspend fun updateData(transform: suspend (t: T) -> T): T {
            gate.getAndSet(null)?.let { it.entered.complete(Unit); it.release.await() }
            failure.get()?.let { throw it }
            return transform(value.value).also { value.value = it }
        }
    }
}
