package com.difft.android.chat.websocket

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.test.fakes.TestMessagingPause.Companion.OWNER
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [MigrationPauseAuthProbe]: the old login's end is detected while the pause waits for it (T1, T24, #1382 A09). */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationPauseAuthProbeTest {

    private val messaging = TestMessagingPause().open()
    private val deviceRepository = mockk<DeviceRepository>()
    private val logoutManager = mockk<LogoutManager>(relaxed = true)
    private val userManager = mockk<UserManager> { every { getUserData() } returns UserData(baseAuth = "auth") }
    private val foreground = MutableStateFlow(true)

    private fun TestScope.startProbe(gate: MessagingGate = messaging.gate): MigrationPauseAuthProbe {
        val probe = MigrationPauseAuthProbe(gate, deviceRepository, { logoutManager }, userManager, backgroundScope)
        backgroundScope.launch { probe.run(foreground, intervalMs = 10_000L) }
        return probe
    }

    private suspend fun probeCalls() = deviceRepository.checkDeviceAuthAs("auth", false, false)

    private suspend fun awaitingLogin() {
        messaging.pauseMessaging()
        messaging.pause.committing(OWNER, "session")
        messaging.pause.finish(OWNER, null, MigrationPauseOutcome.SUCCESS)
    }

    @Test
    fun `a revoked login while awaiting it runs the passive logout once`() = runTest {
        awaitingLogin()
        coEvery { probeCalls() } throws AuthorizationFailedException(401, "revoked")
        startProbe()

        runCurrent()
        advanceTimeBy(60_000L)

        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
        coVerify(exactly = 1) { probeCalls() }
    }

    @Test
    fun `the probe's own 403 is not a kick and keeps asking`() = runTest {
        awaitingLogin()
        coEvery { probeCalls() } throws AuthorizationFailedException(403, "edge")
        startProbe()

        runCurrent()
        advanceTimeBy(20_001L)

        coVerify(exactly = 3) { probeCalls() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    @Test
    fun `a 401 for a credential replaced while in flight is ignored`() = runTest {
        awaitingLogin()
        coEvery { probeCalls() } answers {
            // A re-login lands while the request is on the wire.
            every { userManager.getUserData() } returns UserData(baseAuth = "new-auth")
            throw AuthorizationFailedException(401, "revoked")
        }
        coEvery { deviceRepository.checkDeviceAuthAs("new-auth", false, false) } returns Unit
        val probe = MigrationPauseAuthProbe(messaging.gate, deviceRepository, { logoutManager }, userManager, backgroundScope)

        assertFalse(probe.probeOnce())

        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    @Test
    fun `a 401 that arrives after the pause ended is ignored`() = runTest {
        awaitingLogin()
        coEvery { probeCalls() } coAnswers {
            messaging.pause.clear(null, "signed_out")
            throw AuthorizationFailedException(401, "revoked")
        }
        val probe = MigrationPauseAuthProbe(messaging.gate, deviceRepository, { logoutManager }, userManager, backgroundScope)

        assertFalse(probe.probeOnce())

        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    @Test
    fun `a valid login is asked again every interval`() = runTest {
        awaitingLogin()
        coEvery { probeCalls() } returns Unit
        startProbe()

        runCurrent()
        advanceTimeBy(30_001L)

        coVerify(exactly = 4) { probeCalls() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    @Test
    fun `a network failure keeps waiting`() = runTest {
        awaitingLogin()
        coEvery { probeCalls() } throws PushNetworkException(IOException("offline"))
        startProbe()

        runCurrent()
        advanceTimeBy(20_001L)

        coVerify(exactly = 3) { probeCalls() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    @Test
    fun `nothing is asked while exporting, in the background or open`() = runTest {
        coEvery { probeCalls() } returns Unit
        startProbe()
        runCurrent()
        messaging.pauseMessaging() // EXPORTING
        runCurrent()
        advanceTimeBy(30_000L)
        coVerify(exactly = 0) { deviceRepository.checkDeviceAuthAs(any(), any(), any()) }

        foreground.value = false
        messaging.pause.committing(OWNER, "session")
        runCurrent()
        advanceTimeBy(30_000L)
        coVerify(exactly = 0) { deviceRepository.checkDeviceAuthAs(any(), any(), any()) }

        // Back in front: asked at once.
        foreground.value = true
        runCurrent()
        coVerify(exactly = 1) { probeCalls() }
    }

    @Test
    fun `a record awaiting a decision is probed and its 401 ends the pause`() = runTest {
        coEvery { probeCalls() } throws AuthorizationFailedException(401, "revoked")
        messaging.pauseMessaging()
        messaging.pause.committing(OWNER, "session")
        messaging.pause.finish(OWNER, null, MigrationPauseOutcome.UNKNOWN)
        startProbe()

        runCurrent()

        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    @Test
    fun `a corrupt record is probed too`() = runTest {
        val gate = MessagingGate()
        val corrupt = MigrationMessagePause(
            RecordingPreferencesDataStore(mutablePreferencesOf(AppStateKeys.MIGRATION_MESSAGE_PAUSE to "{garbage")),
            gate,
        )
        corrupt.load()
        coEvery { probeCalls() } throws AuthorizationFailedException(401, "revoked")
        startProbe(gate)

        runCurrent()

        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    @Test
    fun `a probe without a credential asks nothing`() = runTest {
        every { userManager.getUserData() } returns UserData(baseAuth = null)
        val probe = MigrationPauseAuthProbe(messaging.gate, deviceRepository, { logoutManager }, userManager, backgroundScope)

        assertTrue(!probe.probeOnce())
        coVerify(exactly = 0) { deviceRepository.checkDeviceAuthAs(any(), any(), any()) }
    }
}
