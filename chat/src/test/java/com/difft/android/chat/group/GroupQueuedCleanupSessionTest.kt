package com.difft.android.chat.group

import com.difft.android.base.migration.pause.MigrationPauseScope
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.appScope
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.difft.app.database.WCDB
import org.difft.app.database.wcdb
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real deferred message cleanup uses the authorizing session again when its queued work runs. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupQueuedCleanupSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val database = mockk<WCDB>(relaxed = true)
    private val userManager = mockk<UserManager>()
    private var user = UserData(account = "owner", baseAuth = "fixture-a")
    private lateinit var store: DBMessageStore

    @Before fun setup() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns scope
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        every { wcdb } returns database
        // Host tests cannot load WCDB's native Table. An authorized deletion reaches this storage
        // guard; a stale one must return before any access, including this property.
        every { database.isDbInaccessible } returns true
        every { userManager.getUserData() } answers { user }
        mockkObject(RoomChangeTracker)
        store = DBMessageStore(mockk<DBRoomStore>(relaxed = true))
    }

    @After fun teardown() {
        scope.runCurrent()
        unmockkAll()
    }

    @Test fun `switching account after enqueue prevents deferred database work and side effects`() {
        val original = session("owner", "fixture-a")
        store.removeRoomAndMessages("queued-account") { original.isCurrent(userManager) }
        user = UserData(account = "other", baseAuth = "fixture-b")
        scope.runCurrent()
        confirmVerified(database)
        verify(exactly = 0) { RoomChangeTracker.trackRoom(any(), any()) }
    }

    @Test fun `same account credential change after enqueue also prevents deferred cleanup`() {
        val original = session("owner", "fixture-a")
        store.removeRoomAndMessages("queued-credential") { original.isCurrent(userManager) }
        user = UserData(account = "owner", baseAuth = "fixture-b")
        scope.runCurrent()
        confirmVerified(database)
        verify(exactly = 0) { RoomChangeTracker.trackRoom(any(), any()) }
    }

    @Test fun `unchanged session still runs queued deletion and a stale skip releases its room guard`() {
        val original = session("owner", "fixture-a")
        store.removeRoomAndMessages("released-room") { original.isCurrent(userManager) }
        user = UserData(account = "owner", baseAuth = "fixture-b")
        scope.runCurrent()
        val current = session("owner", "fixture-b")
        store.removeRoomAndMessages("released-room") { current.isCurrent(userManager) }
        scope.runCurrent()
        verify(exactly = 1) { database.isDbInaccessible }
    }

    @Test fun `queued redelete keeps its own session guard instead of restarting without authorization`() {
        val original = session("owner", "fixture-a")
        val second = session("owner", "fixture-b")
        store.removeRoomAndMessages("queued-redrive") {
            val current = original.isCurrent(userManager)
            if (!current) user = UserData(account = "owner", baseAuth = "fixture-c")
            current
        }
        user = UserData(account = "owner", baseAuth = "fixture-b")
        store.removeRoomAndMessages("queued-redrive") { second.isCurrent(userManager) }
        scope.runCurrent()
        confirmVerified(database)
        verify(exactly = 0) { RoomChangeTracker.trackRoom(any(), any()) }
    }

    @Test fun `queued unguarded redelete still runs when a later guarded request's session has ended by then`() {
        val original = session("owner", "fixture-a")
        store.removeRoomAndMessages("legacy-then-guarded") { original.isCurrent(userManager) }
        store.removeRoomAndMessages("legacy-then-guarded", null)
        store.removeRoomAndMessages("legacy-then-guarded") { original.isCurrent(userManager) }
        user = UserData(account = "owner", baseAuth = "fixture-b")
        scope.runCurrent()
        // The stale in-flight loop returns before storage; only the re-driven legacy redelete reaches it.
        verify(exactly = 1) { database.isDbInaccessible }
    }

    private fun session(account: String, credential: String) =
        GroupWriteSession(account, MigrationPauseScope.credentialHash(credential))
}
