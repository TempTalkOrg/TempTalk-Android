package com.difft.android.setting

import com.difft.android.base.migration.pause.HeldSendJobs
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPauseScope
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.chat.attachment.migration.ForwardAttachmentMigration
import com.difft.android.chat.util.AppIconBadgeManager
import com.difft.android.chat.translate.TranslateQueue
import com.difft.android.chat.translate.model.TranslateModelStore
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.websocket.WebSocketManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.test.TestDispatcherRule
import difft.android.messageserialization.MessageStore
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Unit tests for the exit decision in [LogoutManagerImpl.performLogout] (issue #1230).
 *
 * This is what the [AppExiter] seam buys: without it any test reaching `performLogout` would run
 * `Process.killProcess` and take down the Gradle test worker. `exit` never returns in production
 * either, so the fake throws.
 *
 * `appScope` is a real global `CoroutineScope(Dispatchers.IO)` and is not injectable here, so the
 * assertions use MockK's timeout verification rather than virtual time.
 */
class LogoutManagerImplExitTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private class TestExit : RuntimeException("appExiter.exit")

    private lateinit var userManager: UserManager
    private lateinit var storageBoundUserManager: StorageBoundUserManager
    private lateinit var messageStore: MessageStore
    private lateinit var messageNotificationUtil: MessageNotificationUtil
    private lateinit var appIconBadgeManager: AppIconBadgeManager
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var coordinator: DomainSpeedTestCoordinator
    private lateinit var wcdb: WCDB
    private lateinit var proxyConfigProvider: ProxyConfigProvider
    private lateinit var forwardAttachmentMigration: ForwardAttachmentMigration
    private lateinit var loggedOutNotifier: LoggedOutNotifier
    private lateinit var appVisibility: AppVisibility
    private lateinit var appExiter: AppExiter
    private lateinit var translateModelStore: TranslateModelStore
    private lateinit var translateQueue: TranslateQueue
    private val migrationMessagePause: MigrationMessagePause = mockk(relaxed = true)
    private val heldSendJobs: HeldSendJobs = mockk(relaxed = true)

    @Before
    fun setUp() {
        userManager = mockk(relaxed = true)
        storageBoundUserManager = mockk(relaxed = true)
        messageStore = mockk(relaxed = true)
        messageNotificationUtil = mockk(relaxed = true)
        appIconBadgeManager = mockk(relaxed = true)
        webSocketManager = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        wcdb = mockk(relaxed = true)
        proxyConfigProvider = mockk(relaxed = true)
        forwardAttachmentMigration = mockk(relaxed = true)
        loggedOutNotifier = mockk(relaxed = true)
        appVisibility = mockk(relaxed = true)
        appExiter = mockk()
        translateModelStore = mockk(relaxed = true)
        translateQueue = mockk(relaxed = true)

        // Both storage clears sit inside runBlocking { withTimeoutOrNull(3_000) { ... } }; an
        // unstubbed relaxed suspend mock would stall each test for the full timeout.
        coEvery { storageBoundUserManager.clearAuthOnly() } returns Unit
        coEvery { storageBoundUserManager.clearAll() } returns Unit
        every { appExiter.exit(any()) } throws TestExit()
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun build() = LogoutManagerImpl(
        userManager = userManager,
        storageBoundUserManager = storageBoundUserManager,
        messageStore = messageStore,
        messageNotificationUtil = messageNotificationUtil,
        appIconBadgeManager = appIconBadgeManager,
        webSocketManager = webSocketManager,
        coordinator = coordinator,
        wcdb = wcdb,
        proxyConfigProvider = proxyConfigProvider,
        forwardAttachmentMigration = forwardAttachmentMigration,
        loggedOutNotifier = loggedOutNotifier,
        appVisibility = appVisibility,
        appExiter = appExiter,
        translateModelStore = { translateModelStore },
        translateQueue = { translateQueue },
        migrationMessagePause = migrationMessagePause,
        heldSendJobs = heldSendJobs,
    )

    // T21
    @Test
    fun `with a resumed activity the app relaunches and posts no notification`() {
        every { appVisibility.hasResumedActivity() } returns true

        build().doLogoutWithoutRemoveData()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(relaunch = true) }
        verify(exactly = 0) { loggedOutNotifier.post() }
    }

    // T22 — the #1230 safety net: a kick confirmed with nothing on screen must explain itself.
    @Test
    fun `with no resumed activity the notification is posted before a non-relaunching exit`() {
        every { appVisibility.hasResumedActivity() } returns false

        build().doLogoutWithoutRemoveData()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(relaunch = false) }
        verifyOrder {
            loggedOutNotifier.post()
            appExiter.exit(relaunch = false)
        }
    }

    // T23
    @Test
    fun `a duplicate logout call is dropped by the latch`() {
        every { appVisibility.hasResumedActivity() } returns true
        val manager = build()

        manager.doLogoutWithoutRemoveData()
        manager.doLogoutWithoutRemoveData()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(any()) }
        coVerify(exactly = 1) { storageBoundUserManager.clearAuthOnly() }
        verify(exactly = 1) { forwardAttachmentMigration.cancel() }
        verify(exactly = 1) { webSocketManager.stop() }
    }

    // #1382 — every logout ends a migration message pause before the storage clears wipe app_state,
    // which they do on disk only.
    @Test
    fun `a passive logout clears the migration pause before the auth clear`() {
        every { appVisibility.hasResumedActivity() } returns true

        build().doLogoutWithoutRemoveData()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(any()) }
        coVerifyOrder {
            migrationMessagePause.clearForLogout()
            storageBoundUserManager.clearAuthOnly()
        }
    }

    @Test
    fun `a full logout clears the migration pause before the full clear`() {
        every { appVisibility.hasResumedActivity() } returns true

        build().doLogout()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(any()) }
        coVerifyOrder {
            migrationMessagePause.clearForLogout()
            storageBoundUserManager.clearAll()
        }
    }

    @Test
    fun `the handoff sign-out clears the migration pause before the auth clear`() {
        every { appVisibility.hasResumedActivity() } returns true

        build().doMigrationHandoffSignOut()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(any()) }
        coVerifyOrder {
            migrationMessagePause.clearForLogout()
            storageBoundUserManager.clearAuthOnly()
        }
    }

    // #1382 — a passive logout keeps the database, so the sends a migration pause holds are dropped
    // before the pause clears; a later login must never send them.
    @Test
    fun `a passive logout while paused drops the held sends before the pause clears`() {
        every { appVisibility.hasResumedActivity() } returns true
        every { migrationMessagePause.state } returns MessagingPauseState.Paused(null)

        build().doLogoutWithoutRemoveData()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(any()) }
        coVerifyOrder {
            heldSendJobs.dropAll("logout", null)
            migrationMessagePause.clearForLogout()
        }
    }

    // #1382 A04 — sends queued before the login loaded would otherwise be adopted by the next login.
    @Test
    fun `a passive logout with messaging open drops only the sends the ending login does not own`() {
        every { appVisibility.hasResumedActivity() } returns true
        every { migrationMessagePause.state } returns MessagingPauseState.Open
        every { userManager.getUserData() } returns UserData(account = "acc-1", baseAuth = "auth-1")

        build().doLogoutWithoutRemoveData()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(any()) }
        coVerifyOrder {
            heldSendJobs.dropAll("logout", MigrationPauseScope.credentialHash("auth-1"))
            migrationMessagePause.clearForLogout()
        }
    }

    @Test
    fun `a full logout leaves the held sends to the database wipe`() {
        every { appVisibility.hasResumedActivity() } returns true
        every { migrationMessagePause.state } returns MessagingPauseState.Paused(null)

        build().doLogout()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(any()) }
        coVerify(exactly = 0) { heldSendJobs.dropAll(any(), any()) }
        verify(exactly = 1) { messageStore.deleteDatabase() }
    }

    // T24 — a user-initiated "Log Out" that loses the latch to an in-flight passive logout must
    // still wipe the data: the latch escalates the running sequence instead of dropping the request.
    @Test
    fun `a full clear requested during a passive logout escalates instead of being dropped`() {
        every { appVisibility.hasResumedActivity() } returns true
        // Hold the passive sequence at its first storage clear so the full-wipe request can land
        // while it is genuinely in flight.
        val passiveClearReached = CountDownLatch(1)
        val releasePassiveClear = CountDownLatch(1)
        coEvery { storageBoundUserManager.clearAuthOnly() } coAnswers {
            passiveClearReached.countDown()
            releasePassiveClear.await(5, TimeUnit.SECONDS)
            Unit
        }
        val manager = build()

        manager.doLogoutWithoutRemoveData()
        assertTrue(passiveClearReached.await(5, TimeUnit.SECONDS), "passive logout never started")
        manager.doLogout()
        releasePassiveClear.countDown()

        verify(timeout = 5_000, exactly = 1) { appExiter.exit(any()) }
        verify(exactly = 1) { messageStore.deleteDatabase() }
        coVerify(exactly = 1) { storageBoundUserManager.clearAll() }
    }
}
