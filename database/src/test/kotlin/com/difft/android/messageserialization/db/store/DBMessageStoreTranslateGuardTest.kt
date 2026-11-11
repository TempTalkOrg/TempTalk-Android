package com.difft.android.messageserialization.db.store

import com.difft.android.base.utils.appScope
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.WCDB
import org.difft.app.database.wcdb
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config









@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DBMessageStoreTranslateGuardTest {

    private lateinit var wcdbMock: WCDB
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var store: DBMessageStore

    private val transientStatuses = listOf(
        TranslateStatus.Translating,
        TranslateStatus.SameLanguage,
        TranslateStatus.Failed,
        TranslateStatus.FailedTooLong,
        TranslateStatus.FailedModel,
    )

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        wcdbMock = mockk(relaxed = true)
        every { wcdb } returns wcdbMock

        store = DBMessageStore(mockk<DBRoomStore>(relaxed = true))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun data(status: TranslateStatus) = TranslateData(status, "你好", "hello")


    @Test
    fun `T34 the single path writes nothing for any transient status`() = runTest(testDispatcher) {
        transientStatuses.forEach { status ->
            store.updateMessageTranslateData("room-1", "m1", data(status))
        }

        
        verify(exactly = 0) { wcdbMock.message }
        verify(exactly = 0) { wcdbMock.translate }
    }

    @Test
    fun `T34 every persistable status is admitted`() = runTest(testDispatcher) {
        val persistable = TranslateStatus.entries.filter { it.isPersistable }

        assertTrue(
            "isPersistable must stay exactly {Invisible, ShowCN, ShowEN}",
            persistable.toSet() == setOf(
                TranslateStatus.Invisible,
                TranslateStatus.ShowCN,
                TranslateStatus.ShowEN,
            ),
        )
    }


    @Test
    fun `T34b the batch path writes nothing when every entry is transient`() = runTest(testDispatcher) {
        val batch = transientStatuses.withIndex().associate { (index, status) -> "m$index" to data(status) }

        store.updateMessageTranslateDataBatch("room-1", batch)

        verify(exactly = 0) { wcdbMock.message }
        verify(exactly = 0) { wcdbMock.translate }
    }

    @Test
    fun `T34b an empty batch is a no-op`() = runTest(testDispatcher) {
        store.updateMessageTranslateDataBatch("room-1", emptyMap())

        verify(exactly = 0) { wcdbMock.message }
    }

    

    








    @Test
    fun `both translate write paths wait on the same lock before touching the database`() =
        runTest(testDispatcher) {
            val mutex = translateWriteMutex()
            mutex.lock()

            val single = async {
                withTimeoutOrNull(BLOCKED_MS) {
                    store.updateMessageTranslateData("room-1", "m1", data(TranslateStatus.ShowCN))
                }
            }
            val batch = async {
                withTimeoutOrNull(BLOCKED_MS) {
                    store.updateMessageTranslateDataBatch("room-1", mapOf("m1" to data(TranslateStatus.ShowCN)))
                }
            }

            assertNull("the single path must wait for the lock", single.await())
            assertNull("the batch path must wait for the same lock", batch.await())
            verify(exactly = 0) { wcdbMock.message }
            verify(exactly = 0) { wcdbMock.translate }

            mutex.unlock()
        }

    private fun translateWriteMutex(): Mutex =
        DBMessageStore::class.java.getDeclaredField("translateWriteMutex")
            .apply { isAccessible = true }
            .get(store) as Mutex

    private companion object {
        
        const val BLOCKED_MS = 10_000L
    }
}
