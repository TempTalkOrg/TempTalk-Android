package com.difft.android

import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.test.TestDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.difft.app.database.DatabaseRecoveryState
import org.difft.app.database.DbHealth
import org.difft.app.database.WCDB
import org.difft.app.database.probeHealthy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Routing tests for [RecoveryFlowCoordinator] — the recovery/key-loss flow extracted out of
 * `MainActivity` so it can be driven without hitting `Runtime.exit` (the restart is behind the
 * [RecoveryFlowCoordinator.Host.restartApp] seam, recorded by [FakeHost]).
 *
 * Covers: DB-present KEY_UNAVAILABLE renders the fail-soft retry screen (no wipe, and leaves the
 * corruption circuit-breaker count untouched — neither reset nor increment); DB-present CORRUPT
 * routes to recovery and wipes; DB-absent proceeds to normal routing (→ login) without probing the
 * key; a mid-recovery key failure aborting without a wipe (renders the fail-soft screen).
 *
 * Coordinator coroutines run on the rule's [kotlinx.coroutines.test.UnconfinedTestDispatcher]
 * (as both `ioDispatcher` and `Dispatchers.Main`), so they execute eagerly and assertions are
 * deterministic with no `advanceUntilIdle`/sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryFlowCoordinatorTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule() // UnconfinedTestDispatcher + Dispatchers.setMain

    private lateinit var wcdb: WCDB
    private lateinit var recoveryState: DatabaseRecoveryState
    private lateinit var userManager: UserManager
    private lateinit var logoutManager: LogoutManager
    private lateinit var host: FakeHost
    private lateinit var coordinator: RecoveryFlowCoordinator

    /** Records the Activity-coupled seams — crucially, `restartApp` instead of killing the JVM. */
    private class FakeHost(
        override val scope: CoroutineScope,
    ) : RecoveryFlowCoordinator.Host {
        var dbExists = true
        var renderRecoveryCount = 0
        var renderKeyUnavailableCount = 0
        var restartCount = 0
        val toasts = mutableListOf<Int>()

        override fun databaseFileExists() = dbExists
        override fun renderRecoveryScreen() { renderRecoveryCount++ }
        override fun renderKeyUnavailableScreen() { renderKeyUnavailableCount++ }
        override fun showToast(messageResId: Int) { toasts += messageResId }
        override fun restartApp() { restartCount++ }
    }

    @Before
    fun setUp() {
        wcdb = mockk(relaxed = true)
        recoveryState = mockk(relaxed = true)
        userManager = mockk(relaxed = true)
        logoutManager = mockk(relaxed = true)

        // Extension function probeHealthy() lives in WCDBHealthProbe.kt.
        mockkStatic("org.difft.app.database.WCDBHealthProbeKt")

        host = FakeHost(CoroutineScope(dispatcherRule.testDispatcher))
        coordinator = buildCoordinator()
    }

    /**
     * Builds the coordinator with test-safe DB-handle seams (defaults are no-ops that never touch
     * the native WCDB `Database` class — mocking it would trigger `System.loadLibrary`, unavailable
     * on the host JVM). Tests that need a specific retrieve/close outcome override the relevant seam.
     */
    private fun buildCoordinator(
        backupRetrieve: () -> Double = { 0.0 },
        dbSmokeCheck: () -> Unit = {},
        dbClose: () -> Unit = {},
    ) = RecoveryFlowCoordinator(
        host, wcdb, recoveryState, userManager, logoutManager,
        ioDispatcher = dispatcherRule.testDispatcher,
        backupRetrieve = backupRetrieve,
        dbSmokeCheck = dbSmokeCheck,
        dbClose = dbClose,
    )

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `T2 DB present KEY_UNAVAILABLE renders fail-soft retry screen, never wipes, leaves the corruption count untouched`() {
        host.dbExists = true
        every { wcdb.probeHealthy() } returns DbHealth.KEY_UNAVAILABLE

        val proceed = runBlocking { coordinator.routeOnDatabaseHealth() }

        // Existing user, unreadable key: the local data is intact on disk but can't be opened, so
        // render the retry-only fail-soft screen instead of dropping into a broken IndexActivity.
        assertFalse(proceed, "KEY_UNAVAILABLE must NOT continue normal routing (screen took over)")
        assert(host.renderKeyUnavailableCount == 1) { "fail-soft retry screen must render" }
        assert(host.renderRecoveryCount == 0) { "corruption-recovery screen must NOT render" }
        assert(host.restartCount == 0) { "no restart on key-unavailable" }
        assertFalse(coordinator.recoveryInProgress, "key-unavailable must not set recoveryInProgress")
        // The corruption attempt count is the CORRUPT circuit-breaker's; a key-failure launch must
        // neither reset nor increment it (reset is HEALTHY-only, so resetting here could postpone
        // the give-up/logout escape under a compound corruption + intermittent-Keystore fault).
        verify(exactly = 0) { recoveryState.reset() }
        verify(exactly = 0) { recoveryState.incrementAndGet() } // never increment on key failure
        verify(exactly = 0) { wcdb.deleteDatabaseFile() }       // never wipe
    }

    @Test
    fun `T3 DB present CORRUPT routes to recovery and wipes (file-corruption regression guard)`() {
        host.dbExists = true
        every { wcdb.probeHealthy() } returns DbHealth.CORRUPT
        every { recoveryState.incrementAndGet() } returns 1
        // backupRetrieve default seam returns 0.0 → no backup material → destructive reset.

        val proceed = runBlocking { coordinator.routeOnDatabaseHealth() }

        assertFalse(proceed, "CORRUPT must NOT continue normal routing")
        assert(host.renderRecoveryCount == 1) { "recovery screen must render" }
        verify(exactly = 1) { recoveryState.incrementAndGet() }
        verify { wcdb.markCorrupted() }
        verify { wcdb.deleteDatabaseFile() }
        assert(host.restartCount == 1) { "wipe path must restart" }
        verify(exactly = 0) { recoveryState.reset() }
    }

    @Test
    fun `T10 DB absent proceeds to login without probing the key, never wipes, no screen`() {
        host.dbExists = false
        // The DB-absent path does not probe the key at all — a transient startup Keystore glitch
        // must not poison a fresh login (round-2 [3]). It is treated as HEALTHY and proceeds; the
        // WCDB-free verifyLocalToken()/getUserData() lands on login.

        val proceed = runBlocking { coordinator.routeOnDatabaseHealth() }

        assertTrue(proceed, "DB-absent must proceed to normal routing (→ login)")
        assert(host.renderRecoveryCount == 0) { "recovery screen must NOT render" }
        assert(host.renderKeyUnavailableCount == 0) { "fail-soft screen must NOT render on DB-absent" }
        verify(exactly = 1) { recoveryState.reset() } // HEALTHY resets the corruption count
        verify(exactly = 0) { wcdb.deleteDatabaseFile() }
        verify(exactly = 0) { wcdb.probeHealthy() }    // DB absent → no probe at all
    }

    @Test
    fun `T-KMR mid-recovery key failure aborts WITHOUT wipe and shows the fail-soft screen`() {
        every { recoveryState.incrementAndGet() } returns 1
        // backupRetrieve throws the cached key exception mid-recovery.
        coordinator = buildCoordinator(backupRetrieve = {
            throw WCDBKeyUnavailableException("mid-recovery", IllegalStateException("keystore down"))
        })

        // showRecoveryUI sets recoveryInProgress=true then launches performRecovery, whose
        // tryBackupRecovery() throws the key exception → abort without wipe, render fail-soft screen.
        coordinator.showRecoveryUI(DbHealth.CORRUPT)

        assert(host.renderRecoveryCount == 1) { "recovery screen rendered first" }
        verify(exactly = 0) { wcdb.deleteDatabaseFile() } // NEVER resetDatabaseAndResync on key failure
        assert(host.renderKeyUnavailableCount == 1) { "abort renders the fail-soft retry screen" }
        assert(host.restartCount == 0) { "abort does not restart (screen takes over)" }
        assertFalse(coordinator.recoveryInProgress, "abort must release the recovery guard")
    }
}
