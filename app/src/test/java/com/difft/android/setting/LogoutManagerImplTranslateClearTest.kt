package com.difft.android.setting

import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserManager
import com.difft.android.chat.attachment.migration.ForwardAttachmentMigration
import com.difft.android.chat.translate.TranslateQueue
import com.difft.android.chat.translate.model.TranslateModelStore
import com.difft.android.chat.util.AppIconBadgeManager
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.websocket.WebSocketManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.test.TestDispatcherRule
import difft.android.messageserialization.MessageStore
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Translate cleanup on ACTIVE logout.
 *
 * The asymmetry is the point: a user-initiated wipe cancels every queued translation and clears the
 * model-download bookkeeping before the database goes away, while a passive logout (kicked from
 * another device) leaves both alone — the conversation translate setting and the failure records
 * survive a kick, exactly like `saveToPhotos`.
 *
 * The downloaded ML Kit models themselves are deliberately NOT deleted in either case: they are a
 * device-level resource with no account affinity.
 *
 * `appScope` is the real global scope here (as in [LogoutManagerImplExitTest]), so the assertions
 * use MockK's timeout verification rather than virtual time.
 *
 * What each case asserts:
 *  - T62 clearData() calls translateQueue.cancelAll() and translateModelStore.clearOnLogout() once
 */
class LogoutManagerImplTranslateClearTest {

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

        coEvery { storageBoundUserManager.clearAuthOnly() } returns Unit
        coEvery { storageBoundUserManager.clearAll() } returns Unit
        every { appExiter.exit(any()) } throws TestExit()
        every { appVisibility.hasResumedActivity() } returns true
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
    )

    // --- T62 ----------------------------------------------------------------

    @Test
    fun `T62 an active logout cancels the queue and clears the model bookkeeping`() {
        build().doLogout()

        verify(timeout = 5_000, exactly = 1) { translateQueue.cancelAll() }
        verify(timeout = 5_000, exactly = 1) { translateModelStore.clearOnLogout() }
    }

    @Test
    fun `T62 the cleanup runs before the database is deleted`() {
        build().doLogout()

        // Ordering matters: a translation still in flight must not write a translate row into a
        // store that is being wiped.
        verify(timeout = 5_000, exactly = 1) { messageStore.deleteDatabase() }
        io.mockk.verifyOrder {
            translateQueue.cancelAll()
            translateModelStore.clearOnLogout()
            messageStore.deleteDatabase()
        }
    }

    @Test
    fun `T62 a passive logout leaves the translate state untouched`() {
        build().doLogoutWithoutRemoveData()

        // The kick path reaches the exit, so waiting on that is what proves the sequence ran.
        verify(timeout = 5_000) { appExiter.exit(any()) }
        verify(exactly = 0) { translateQueue.cancelAll() }
        verify(exactly = 0) { translateModelStore.clearOnLogout() }
    }
}
