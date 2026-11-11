package com.difft.android.chat.contacts

import app.cash.turbine.test
import com.difft.android.base.utils.RoomChange
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.ContactsRepository
import com.difft.android.chat.group.GroupUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.roomMessageExpiries
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactsRepositoryChangesTest {
    private val roomChanges = MutableSharedFlow<List<RoomChange>>(extraBufferCapacity = 4)
    private val wcdb = mockk<WCDB>()
    private var storedTimer = DAY
    private lateinit var repository: ContactsRepository

    @Before fun setUp() {
        mockkObject(ContactorUtil, OfficialAccountCache, RoomChangeTracker)
        mockkStatic("org.difft.app.database.ContactsDirectoryQueriesKt")
        every { ContactorUtil.contactsUpdate } returns MutableSharedFlow()
        every { OfficialAccountCache.state } returns MutableStateFlow(emptySet())
        every { RoomChangeTracker.roomChanges } returns roomChanges
        every { wcdb.roomMessageExpiries(any()) } answers { secondArg<Collection<String>>().associateWith { storedTimer } }
        val groupUtil = mockk<GroupUtil> {
            every { getGroupsStatusUpdate } returns MutableSharedFlow()
            every { singleGroupsUpdate } returns MutableSharedFlow()
        }
        repository = ContactsRepository(mockk(), wcdb, mockk(), groupUtil)
        repository.rememberShownTimers(listOf("friend"), mapOf("friend" to DAY))
    }

    @After fun tearDown() { unmockkAll() }

    @Test fun `only a committed timer change of a shown row reloads the directory`() = runTest {
        repository.timerReadDispatcher = StandardTestDispatcher(testScheduler)
        repository.changes().test {
            
            roomChanges.emit(listOf(RoomChange("friend", RoomChangeType.REFRESH), RoomChange("other", RoomChangeType.REFRESH)))
            roomChanges.emit(listOf(RoomChange("friend", RoomChangeType.MESSAGE)))
            advanceUntilIdle()
            expectNoEvents()
            storedTimer = 0L
            roomChanges.emit(listOf(RoomChange("friend", RoomChangeType.REFRESH)))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 0) { wcdb.roomMessageExpiries(match { "other" in it }) }
    }

    @Test fun `a weak lookup reports only rows that landed and never runs twice at once`() = runTest {
        val answer = CompletableDeferred<List<ContactorModel>>()
        coEvery { ContactorUtil.fetchContactors(any(), any()) } coAnswers { answer.await() }
        val first = async { repository.backfillWeakContacts(listOf("w")) }
        runCurrent()
        assertFalse(repository.backfillWeakContacts(listOf("w")))
        answer.complete(listOf(ContactorModel().apply { id = "w" }))
        assertTrue(first.await())
        coEvery { ContactorUtil.fetchContactors(any(), any()) } returns emptyList()
        assertFalse(repository.backfillWeakContacts(listOf("w")))
        assertFalse(repository.backfillWeakContacts(emptyList()))
        coVerify(exactly = 2) { ContactorUtil.fetchContactors(any(), any()) }
    }

    @Test fun `the directory snapshot never waits on the server`() {
        
        val source = File(SOURCE).readText()
        val snapshot = source.substringAfter("suspend fun snapshot(").substringBefore("suspend fun backfillWeakContacts(")
        assertFalse("fetchContactors" in snapshot)
    }

    private companion object {
        const val DAY = 86_400L
        val SOURCE = listOf(
            "src/main/java/com/difft/android/chat/contacts/data/ContactsRepository.kt",
            "chat/src/main/java/com/difft/android/chat/contacts/data/ContactsRepository.kt",
        ).first { File(it).exists() }
    }
}
