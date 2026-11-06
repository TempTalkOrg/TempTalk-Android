package com.difft.android.base.storage.user

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.user.UserData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedUserWritesTest {
    @Test
    fun `legacy predecessor completes both stores before strict snapshot starts`() = runTest {
        val auth = MemoryStore(UserAuthData.EMPTY)
        val state = MemoryStore(emptyPreferences())
        val writes = OrderedUserWrites(auth, state, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        writes.enqueue { events += "legacy-start"; gate.await(); events += "legacy-end" }
        val strict = writes.enqueueStrict(UserData(account = "a", customUid = "accepted")) { true }
        runCurrent()
        assertEquals(listOf("legacy-start"), events)
        assertFalse(strict.isCompleted)
        assertEquals(UserAuthData.EMPTY, auth.value.value)
        gate.complete(Unit)
        runCurrent()
        strict.await()
        assertEquals(listOf("legacy-start", "legacy-end"), events)
        assertEquals("accepted", auth.value.value.customUid)
    }

    @Test
    fun `disk error propagates and unchanged retry really writes again`() = runTest {
        val auth = MemoryStore(UserAuthData.EMPTY)
        val state = MemoryStore(emptyPreferences())
        val writes = OrderedUserWrites(auth, state, StandardTestDispatcher(testScheduler))
        val snapshot = UserData(account = "a", customUid = "accepted")
        auth.failure = IOException("disk")
        val failed = writes.enqueueStrict(snapshot) { true }
        runCurrent()
        assertFailsWith<IOException> { failed.await() }
        auth.failure = null
        val retry = writes.enqueueStrict(snapshot) { true }
        runCurrent()
        retry.await()
        assertEquals("accepted", auth.value.value.customUid)
        assertEquals(2, auth.attempts)
    }

    @Test
    fun `canceling caller does not cancel accepted queued commit or following logout`() = runTest {
        val auth = MemoryStore(UserAuthData.EMPTY)
        val writes = OrderedUserWrites(auth, MemoryStore(emptyPreferences()), StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        writes.enqueue { gate.await() }
        val accepted = writes.enqueueStrict(UserData(account = "a", customUid = "accepted")) { true }
        val caller = async { accepted.await() }
        runCurrent()
        caller.cancelAndJoin()
        writes.enqueue { auth.updateData { UserAuthData.EMPTY } }
        gate.complete(Unit)
        runCurrent()
        assertTrue(accepted.isCompleted)
        assertEquals(UserAuthData.EMPTY, auth.value.value)
    }

    @Test
    fun `session invalidation rejects stale queued strict snapshot before it touches disk`() = runTest {
        val auth = MemoryStore(UserAuthData.EMPTY)
        val writes = OrderedUserWrites(auth, MemoryStore(emptyPreferences()), StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        writes.enqueue { gate.await() }
        var sessionValid = true
        val stale = writes.enqueueStrict(UserData(account = "old", customUid = "accepted")) { sessionValid }
        sessionValid = false
        writes.enqueue { auth.updateData { UserAuthData(account = "new") } }
        gate.complete(Unit)
        runCurrent()
        assertFailsWith<IllegalStateException> { stale.await() }
        assertEquals("new", auth.value.value.account)
        assertEquals(1, auth.attempts)
    }

    private class MemoryStore<T>(initial: T) : DataStore<T> {
        val value = MutableStateFlow(initial)
        override val data = value
        var failure: Exception? = null
        var attempts = 0
        override suspend fun updateData(transform: suspend (t: T) -> T): T {
            attempts++
            failure?.let { throw it }
            return transform(value.value).also { value.value = it }
        }
    }
}
