package com.difft.android.base.migration.pause

import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.test.fakes.FakeUserManager
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


class MigrationPauseLoginScopeTest {

    private val owner = "11111111-aaaa-bbbb-cccc-000000000001"
    private val users = FakeUserManager(UserData(account = "acc-1", baseAuth = "auth-1"))
    private val environment = mockk<EnvironmentHelper> { every { currentEnvironment } returns "online" }
    private val gate = MessagingGate()
    private val pause = MigrationMessagePause(RecordingPreferencesDataStore(), gate)

    
    private val drops = mutableListOf<MessagingPauseState>()
    private val keptLogins = mutableListOf<String?>()
    private val heldSends = HeldSendJobs { _, keepLogin ->
        drops += gate.state
        keptLogins += keepLogin
    }
    private val loginScope = MigrationPauseLoginScope(users, environment, pause, CredentialChangeSignal(), heldSends)

    private suspend fun pausedByCurrentLogin() {
        pause.load()
        val scope = assertNotNull(loginScope.current())
        pause.begin(owner, scope.accountScope, scope.credentialHash)
    }

    @Test
    fun `the login that started the pause keeps it`() = runTest {
        pausedByCurrentLogin()

        loginScope.discardIfOutOfScope("test")

        assertEquals(owner, pause.record?.owner)
    }

    @Test
    fun `a new credential for the same account clears it`() = runTest {
        pausedByCurrentLogin()
        users.setUserData(UserData(account = "acc-1", baseAuth = "auth-2"))

        loginScope.discardIfOutOfScope("login")

        assertEquals(MessagingPauseState.Open, gate.state)
    }

    @Test
    fun `another account clears it`() = runTest {
        pausedByCurrentLogin()
        users.setUserData(UserData(account = "acc-2", baseAuth = "auth-1"))

        loginScope.discardIfOutOfScope("login")

        assertEquals(MessagingPauseState.Open, gate.state)
    }

    @Test
    fun `the same account on another environment clears it`() = runTest {
        pausedByCurrentLogin()
        every { environment.currentEnvironment } returns "development"

        loginScope.discardIfOutOfScope("cold_start")

        assertEquals(MessagingPauseState.Open, gate.state)
    }

    @Test
    fun `signed out clears it`() = runTest {
        pausedByCurrentLogin()
        users.setUserData(UserData(account = "acc-1", baseAuth = null))

        loginScope.discardIfOutOfScope("cold_start")

        assertEquals(MessagingPauseState.Open, gate.state)
    }

    @Test
    fun `the held sends of an ended login are dropped before messaging opens`() = runTest {
        pausedByCurrentLogin()
        users.setUserData(UserData(account = "acc-1", baseAuth = "auth-2"))

        loginScope.discardIfOutOfScope("login")

        assertEquals(1, drops.size)
        assertIs<MessagingPauseState.Paused>(drops.single())
        assertEquals(MessagingPauseState.Open, gate.state)
        
        assertEquals(listOf<String?>(MigrationPauseScope.credentialHash("auth-2")), keptLogins)
    }

    @Test
    fun `signed out drops the held sends too`() = runTest {
        pausedByCurrentLogin()
        users.setUserData(UserData(account = "acc-1", baseAuth = null))

        loginScope.discardIfOutOfScope("cold_start")

        assertEquals(1, drops.size)
        assertEquals(listOf<String?>(null), keptLogins)
    }

    @Test
    fun `the login that started the pause keeps its held sends`() = runTest {
        pausedByCurrentLogin()

        loginScope.discardIfOutOfScope("login")

        assertTrue(drops.isEmpty())
    }

    @Test
    fun `a record awaiting a decision is paused and cleared for another login`() = runTest {
        pausedByCurrentLogin()
        pause.committing(owner, "session")
        pause.finish(owner, null, MigrationPauseOutcome.UNKNOWN)
        assertIs<MessagingPauseState.Paused>(gate.state)
        users.setUserData(UserData(account = "acc-2", baseAuth = "auth-1"))

        loginScope.discardIfOutOfScope("login")

        assertNull(pause.record)
        assertEquals(1, drops.size, "the sends it held go before messaging opens")
        assertEquals(MessagingPauseState.Open, gate.state)
    }

    @Test
    fun `a corrupt record is kept while signed in and cleared when signed out`() = runTest {
        val disk = RecordingPreferencesDataStore(
            androidx.datastore.preferences.core.mutablePreferencesOf(
                com.difft.android.base.storage.AppStateKeys.MIGRATION_MESSAGE_PAUSE to "{garbage",
            ),
        )
        val corruptGate = MessagingGate()
        val corruptPause = MigrationMessagePause(disk, corruptGate)
        val scope = MigrationPauseLoginScope(users, environment, corruptPause, CredentialChangeSignal(), heldSends)
        corruptPause.load()

        scope.discardIfOutOfScope("cold_start")
        users.setUserData(UserData(account = "acc-2", baseAuth = "auth-2"))
        scope.discardIfOutOfScope("credential_installed")

        assertEquals(MessagingPauseState.Paused(null), corruptGate.state)
        assertTrue(drops.isEmpty())

        users.setUserData(UserData(account = "acc-2", baseAuth = null))
        scope.discardIfOutOfScope("cold_start")

        assertEquals(MessagingPauseState.Open, corruptGate.state)
    }

    @Test
    fun `user data not yet loaded keeps it`() = runTest {
        pausedByCurrentLogin()
        users.clearUserData()

        loginScope.discardIfOutOfScope("cold_start")

        assertEquals(owner, pause.record?.owner)
        assertNull(loginScope.current())
    }
}
