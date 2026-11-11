package com.difft.android.chat.contacts.contactsdetail.mvi

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.difft.android.base.utils.RoomChange
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.chat.contacts.CardPreloads
import com.difft.android.chat.contacts.contactsdetail.ContactDetailRepository
import com.difft.android.chat.contacts.contactsdetail.ContactDetailUiState
import com.difft.android.chat.contacts.contactsdetail.contactCardPresentation
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Effect
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Input
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Intent
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.test.TestDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.models.ContactorModel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val rule = TestDispatcherRule(dispatcher)
    private val repository = mockk<ContactDetailRepository>()
    private val settings = mockk<ConversationSettingsManager>()
    private val contacts = MutableSharedFlow<List<String>>(extraBufferCapacity = 1)
    private val roomChanges = MutableSharedFlow<List<RoomChange>>(extraBufferCapacity = 1)
    private val preloads = CardPreloads()
    private lateinit var model: ContactDetailViewModel

    @Before fun setup() {
        mockkObject(ContactorUtil)
        mockkObject(OfficialAccountCache)
        mockkObject(RoomChangeTracker)
        every { RoomChangeTracker.roomChanges } returns roomChanges
        every { ContactorUtil.contactsUpdate } returns contacts
        every { OfficialAccountCache.state } returns MutableStateFlow(emptySet())
        every { settings.conversationSettingUpdate } returns MutableSharedFlow()
        every { repository.isSelf(any()) } returns false
        coEvery { repository.refresh(any()) } returns null
        model = ContactDetailViewModel(repository, settings, mockk { every { dayChanges } returns emptyFlow() }, preloads)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        unmockkAll()
    }

    @Test fun `returning to the card re-reads locally without asking the server again`() = runTest(dispatcher) {
        coEvery { repository.load(any(), any(), any()) } returns snapshot(friend = true)
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        model.dispatch(Intent.Refresh)
        runCurrent()
        coVerify(exactly = 1) { repository.refresh(any()) }
        coVerify(atLeast = 2) { repository.load(any(), any(), any()) }
    }

    @Test fun `a state read before the sheet showed is the first state and is taken once`() = runTest(dispatcher) {
        val local = CompletableDeferred<ContactDetailUiState>()
        coEvery { repository.load(any(), any(), any()) } coAnswers { local.await() }
        val preloaded = snapshot(pending = true).copy(displayName = "Preloaded", pendingRemovalDays = 3)
        val key = preloads.put(preloaded)
        model.dispatch(Intent.Load(Input("contact"), key))
        
        assertEquals(preloaded, model.state.value.ui)
        assertEquals(null, preloads.take<ContactDetailUiState>(key))
        val fresh = preloaded.copy(displayName = "Fresh")
        local.complete(fresh)
        runCurrent()
        assertEquals(fresh, model.state.value.ui)
    }

    @Test fun `unreadable relation falls back to the friend card rather than offering add`() = runTest(dispatcher) {
        coEvery { repository.load(any(), any(), any()) } throws IllegalStateException("Fixture database failure")
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        val ui = model.state.value.ui
        assertTrue(ui.isRelationResolved)
        assertTrue(ui.isFriend)
        assertEquals(contactCardPresentation(true, true, false, false, false), ui.presentation)
    }

    @Test fun `friend and pending snapshot metadata are the state source`() = runTest(dispatcher) {
        val pending = snapshot(pending = true).copy(pendingRemovalDays = 2, messageExpirySeconds = 3600)
        coEvery { repository.load(any(), any(), any()) } returns pending
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        assertEquals(pending, model.state.value.ui)
        val friend = snapshot(friend = true)
        coEvery { repository.load(any(), any(), any()) } returns friend
        contacts.emit(listOf("contact"))
        runCurrent()
        assertEquals(friend, model.state.value.ui)
    }

    @Test fun `official pending state rejects removal with no effects or state mutation`() = runTest(dispatcher) {
        coEvery { repository.load(any(), any(), any()) } returns snapshot(pending = true, official = true)
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        val before = model.state.value
        model.effect.test {
            model.dispatch(Intent.RemoveNow)
            runCurrent()
            expectNoEvents()
            assertEquals(before, model.state.value)
            coVerify(exactly = 0) { repository.removeNow(any()) }
        }
    }

    @Test fun `repeated add while submitting does not duplicate request`() = runTest(dispatcher) {
        coEvery { repository.load(any(), any(), any()) } returns snapshot()
        val response = CompletableDeferred<ContactDetailRepository.AddResult>()
        coEvery { repository.addFriend(any(), any()) } coAnswers { response.await() }
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        model.effect.test {
            model.dispatch(Intent.AddFriend)
            runCurrent()
            assertEquals(Effect.ShowWait, awaitItem())
            assertTrue(model.state.value.submitting)
            model.dispatch(Intent.AddFriend)
            runCurrent()
            coVerify(exactly = 1) { repository.addFriend(any(), any()) }
            response.complete(ContactDetailRepository.AddResult(0, null))
            runCurrent()
            assertEquals(Effect.FriendAdded, awaitItem())
            assertEquals(Effect.DismissWait, awaitItem())
            assertFalse(model.state.value.submitting)
        }
    }

    @Test fun `pending removal failure retains card and successful removal closes through an effect`() = runTest(dispatcher) {
        coEvery { repository.load(any(), any(), any()) } returns snapshot(pending = true)
        coEvery { repository.removeNow("contact") } returns Result.failure(IllegalStateException("offline"))
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        model.effect.test {
            model.dispatch(Intent.RemoveNow)
            runCurrent()
            assertEquals(Effect.RemoveFailed, awaitItem())
            coEvery { repository.removeNow("contact") } returns Result.success(Unit)
            model.dispatch(Intent.RemoveNow)
            runCurrent()
            assertEquals(Effect.Removed, awaitItem())
        }
    }

    @Test fun `local reloads keep the profile fetched on open for a non-friend`() = runTest(dispatcher) {
        val server = ContactorModel().apply { id = "contact"; avatar = "server-avatar"; customUid = "custom" }
        val sparse = snapshot()
        val fetched = snapshot().copy(contactor = server)
        coEvery { repository.load(any(), any(), any()) } answers {
            if (secondArg<ContactorModel?>() != null || thirdArg<ContactorModel?>() != null) fetched else sparse
        }
        coEvery { repository.refresh(any()) } returns server
        every { repository.notifyContactChanged(any()) } answers { contacts.tryEmit(listOf(firstArg())) }
        coEvery { repository.ensureOriginalAvatarCached(any()) } returns Unit
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        
        assertEquals(server, model.state.value.ui.contactor)
        model.dispatch(Intent.Refresh)
        runCurrent()
        assertEquals(server, model.state.value.ui.contactor)
        coVerify { repository.load(any(), null, server) }
    }

    @Test fun `a reload during the open request keeps the profile that request fetched`() = runTest(dispatcher) {
        val server = ContactorModel().apply { id = "contact"; avatar = "server-avatar"; customUid = "custom" }
        val sparse = snapshot()
        val fetched = snapshot().copy(contactor = server)
        val response = CompletableDeferred<ContactorModel?>()
        coEvery { repository.load(any(), any(), any()) } answers {
            if (secondArg<ContactorModel?>() != null || thirdArg<ContactorModel?>() != null) fetched else sparse
        }
        coEvery { repository.refresh(any()) } coAnswers { response.await() }
        every { repository.notifyContactChanged(any()) } returns Unit
        coEvery { repository.ensureOriginalAvatarCached(any()) } returns Unit
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        
        model.dispatch(Intent.Refresh)
        runCurrent()
        response.complete(server)
        runCurrent()
        assertEquals(server, model.state.value.ui.contactor)
        model.dispatch(Intent.Refresh)
        runCurrent()
        assertEquals(server, model.state.value.ui.contactor)
    }

    @Test fun `a committed room refresh of this conversation re-reads the card`() = runTest(dispatcher) {
        coEvery { repository.load(any(), any(), any()) } returns snapshot(friend = true)
        model.dispatch(Intent.Load(Input("contact")))
        runCurrent()
        roomChanges.emit(listOf(RoomChange("someone-else", RoomChangeType.REFRESH)))
        runCurrent()
        coVerify(exactly = 1) { repository.load(any(), any(), any()) }
        roomChanges.emit(listOf(RoomChange("contact", RoomChangeType.REFRESH)))
        runCurrent()
        coVerify(exactly = 2) { repository.load(any(), any(), any()) }
    }

    private fun snapshot(friend: Boolean = false, pending: Boolean = false, official: Boolean = false) =
        ContactDetailUiState(isFriend = friend, isWeakPending = pending, isOfficialAccount = official,
            isRelationResolved = true, presentation = contactCardPresentation(true, friend, pending, false, official))
}
