package com.difft.android.base.storage.user

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.migration.pause.HeldSendJobs
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPauseLoginScope
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The credential funnel of the migration message pause (#1382): any credential written through
 * [StorageBoundUserManagerImpl] reaches the scope check, so a pause kept closed by a logout reopens
 * when another login is installed in the same process, by whatever path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CredentialChangeSignalPauseTest {

    private class MemoryStore<T>(initial: T) : DataStore<T> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<T> get() = state
        override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
    }

    private val owner = "44444444-aaaa-bbbb-cccc-000000000004"
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signal = CredentialChangeSignal()
    private val users = StorageBoundUserManagerImpl(
        MemoryStore(UserAuthData.EMPTY),
        RecordingPreferencesDataStore(),
        ApplicationProvider.getApplicationContext<Context>(),
        Gson(),
        signal,
    )
    private val gate = MessagingGate()
    private val pause = MigrationMessagePause(RecordingPreferencesDataStore(), gate)
    private val environment = mockk<EnvironmentHelper> { every { currentEnvironment } returns "online" }
    private val heldSends = HeldSendJobs { _, _ -> }
    private val loginScope = MigrationPauseLoginScope(users, environment, pause, signal, heldSends)

    @After
    fun tearDown() = watcherScope.cancel()

    @Test
    fun `every write path publishes the credential, as a hash`() = runBlocking<Unit> {
        users.update(commit = true) { baseAuth = "auth-1" }
        val first = assertNotNull(signal.credentialHash.value)
        assertNotEquals("auth-1", first)

        users.setUserData(UserData(account = "a", baseAuth = "auth-2"), commit = true)
        assertNotEquals(first, signal.credentialHash.value)

        users.updateAuth { copy(baseAuth = "auth-3") }
        assertNotEquals(first, assertNotNull(signal.credentialHash.value))

        users.clearAuthOnly()
        assertNull(signal.credentialHash.value)
    }

    // #1382 A04: a job added before the user data is read is tagged with the login read at that load.
    @Test
    fun `the first load captures the login read then and never changes`() = runBlocking<Unit> {
        assertFalse(signal.isLoaded)

        users.warmUp()

        assertTrue(signal.isLoaded)
        assertNull(signal.firstLoad.await())

        users.update(commit = true) { baseAuth = "auth-1" }
        assertNotNull(signal.credentialHash.value)
        assertNull(signal.firstLoad.await(), "a later sign-in must not rewrite the first load")
    }

    @Test
    fun `a credential installed after a logout reopens messaging without a restart`() = runBlocking<Unit> {
        users.update(commit = true) {
            account = "acc-1"
            baseAuth = "auth-1"
        }
        pause.load()
        val login = assertNotNull(loginScope.current())
        pause.begin(owner, login.accountScope, login.credentialHash)
        pause.committing(owner, "session")
        pause.finish(owner, null, MigrationPauseOutcome.SUCCESS)
        loginScope.watchCredentialChanges(watcherScope)

        // Kicked while awaiting the old login: the logout keeps messaging closed.
        pause.clearForLogout()
        users.clearAuthOnly()
        delay(200)
        assertIs<MessagingPauseState.Paused>(gate.state)

        // A credential arrives by a path other than the login screen (the migration credential install).
        users.update(commit = true) { baseAuth = "auth-2" }

        withTimeout(5_000) { while (gate.state != MessagingPauseState.Open) delay(10) }
        assertEquals(MessagingPauseState.Open, gate.state)
    }
}
