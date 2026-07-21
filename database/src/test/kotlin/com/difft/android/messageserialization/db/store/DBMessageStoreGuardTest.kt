package com.difft.android.messageserialization.db.store

import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.appScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.difft.app.database.wcdb
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DBMessageStore] fail-soft guards for the two user-initiated delete entry points. Both
 * `deleteMessage` and `removeRoomAndMessages` fast-skip on `wcdb.isDbInaccessible` before their
 * local `catch (Exception)` could otherwise swallow a `WCDBKeyUnavailableException`.
 *
 * Critical invariant for `removeRoomAndMessages`: the guard is the first statement inside the
 * `try` block, so `return@launch` still runs the `finally` that releases `deletingRoomIds` —
 * placed before `try {`, that room would be permanently un-deletable. This test asserts the release.
 *
 * The top-level `wcdb` and `appScope` accessors are replaced via `mockkStatic`; the relaxed [WCDB]
 * mock is only asked for `isDbInaccessible` (reaching a `Table` getter would load native `libWCDB`
 * and crash the host JVM), so green also proves no DB table was touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DBMessageStoreGuardTest {

    private lateinit var wcdbMock: WCDB
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var store: DBMessageStore

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        wcdbMock = mockk(relaxed = true)
        every { wcdb } returns wcdbMock
        every { wcdbMock.isDbInaccessible } returns true
        mockkObject(RoomChangeTracker)

        store = DBMessageStore(mockk<DBRoomStore>(relaxed = true))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `G4 deleteMessage skips DB work when DB inaccessible`() = runTest(testDispatcher) {
        store.deleteMessage(listOf("m1", "m2"))
        advanceUntilIdle()

        // Guard returned before wcdb.message + trackRoom; green == no WCDB Table touch.
        verify(exactly = 0) { RoomChangeTracker.trackRoom(any(), any()) }
    }

    @Test
    fun `G4 removeRoomAndMessages skips and releases deletingRoomIds guard when DB inaccessible`() =
        runTest(testDispatcher) {
            val roomId = "room-1"
            store.removeRoomAndMessages(roomId)
            advanceUntilIdle()

            // No DB write happened (green == no WCDB Table touch) ...
            verify(exactly = 0) { RoomChangeTracker.trackRoom(any(), any()) }
            // ... AND the finally ran (guard is inside `try`), so the room is not permanently blocked.
            assertFalse(
                "finally must release deletingRoomIds after the guarded skip",
                deletingRoomIdsContains(roomId)
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun deletingRoomIdsContains(roomId: String): Boolean {
        val field = DBMessageStore::class.java.getDeclaredField("deletingRoomIds")
        field.isAccessible = true
        val set = field.get(null) as Set<String>
        return set.contains(roomId)
    }
}
