package com.difft.android.chat.setting.translate.mvi

import com.difft.android.chat.message.LocalTranslateMessageCreator
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.translate.mvi.TranslateSettingsContract.Intent
import com.difft.android.chat.translate.ModelId
import com.difft.android.chat.translate.TranslateLang
import com.difft.android.chat.translate.model.DownloadTrigger
import com.difft.android.chat.translate.model.ModelState
import com.difft.android.chat.translate.model.TranslateModelStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.test.TestDispatcherRule
import difft.android.messageserialization.For
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * [TranslateSettingsViewModel] — the only writer of `RoomModel.translateSetting`, and therefore the
 * only place that decides whether the conversation gets a "translation turned on" system message.
 *
 * The write itself runs on the real `Dispatchers.IO`, so the row write is driven through a gate and
 * the assertions use MockK's `timeout =` rather than virtual time.
 *
 * What each case asserts:
 *  - a real change writes the row, broadcasts it, inserts exactly one system message and asks for
 *    the model
 *  - a second tap while the write is in flight is ignored — `selected` has not moved yet, so only
 *    the in-flight guard can catch it
 *  - a tap for a DIFFERENT value while the write is in flight is ignored too, and the screen stays
 *    on the value that was actually written
 *  - a row that already holds the value (another writer got there first) skips the system message
 *    while still applying the state and the broadcast
 */
class TranslateSettingsViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val dbRoomStore = mockk<DBRoomStore>(relaxed = true)
    private val settingsManager = mockk<ConversationSettingsManager>(relaxed = true)
    private val messageCreator = mockk<LocalTranslateMessageCreator>(relaxed = true)
    private val modelStore = mockk<TranslateModelStore>(relaxed = true)

    private val conversation = For.Account(ROOM)

    @Before
    fun setUp() {
        every { modelStore.state } returns MutableStateFlow<Map<ModelId, ModelState>>(emptyMap())
        every { modelStore.requiredModels(any()) } returns setOf(ModelId("zh"))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun viewModel() = TranslateSettingsViewModel(
        conversation = conversation,
        dbRoomStore = dbRoomStore,
        conversationSettingsManager = settingsManager,
        localTranslateMessageCreator = messageCreator,
        modelStore = modelStore,
    )

    /** The stored value is read on `Dispatchers.IO`; nothing may be dispatched before it lands. */
    private fun loadedViewModel(): TranslateSettingsViewModel {
        val viewModel = viewModel()
        runBlocking {
            withTimeout(BARRIER_MS) {
                while (!viewModel.state.value.loaded) delay(POLL_MS)
            }
        }
        return viewModel
    }

    @Test
    fun `a real change writes the row, broadcasts it and inserts one system message`() {
        every { dbRoomStore.getTranslateSetting(ROOM) } returns 0
        val viewModel = loadedViewModel()

        viewModel.dispatch(Intent.Select(2))

        coVerify(timeout = BARRIER_MS, exactly = 1) {
            messageCreator.createTranslateSettingChangedMessage(conversation, 2)
        }
        verify { dbRoomStore.updateTranslateSetting(ROOM, 2) }
        verify { settingsManager.emitTranslateSettingUpdate(ROOM, 2) }
        verify { modelStore.ensureDownloaded(TranslateLang.EN, DownloadTrigger.SETTINGS_SELECT) }
        assertEquals(2, viewModel.state.value.selected)
    }

    @Test
    fun `a second tap while the write is in flight is ignored`() {
        every { dbRoomStore.getTranslateSetting(ROOM) } returns 0
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        every { dbRoomStore.updateTranslateSetting(ROOM, 1) } answers {
            started.complete(Unit)
            runBlocking { gate.await() }
        }
        val viewModel = loadedViewModel()

        viewModel.dispatch(Intent.Select(1))
        runBlocking { withTimeout(BARRIER_MS) { started.await() } }
        // `selected` is still 0 at this point, so the idempotency guard on its own would let this
        // second tap through and the conversation would get two system messages.
        viewModel.dispatch(Intent.Select(1))
        gate.complete(Unit)

        coVerify(timeout = BARRIER_MS, exactly = 1) {
            messageCreator.createTranslateSettingChangedMessage(conversation, 1)
        }
        verify(exactly = 1) { dbRoomStore.updateTranslateSetting(ROOM, 1) }
    }

    @Test
    fun `a tap for a different value while the write is in flight is dropped`() {
        every { dbRoomStore.getTranslateSetting(ROOM) } returns 0
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        every { dbRoomStore.updateTranslateSetting(ROOM, 1) } answers {
            started.complete(Unit)
            runBlocking { gate.await() }
        }
        val viewModel = loadedViewModel()

        viewModel.dispatch(Intent.Select(1))
        runBlocking { withTimeout(BARRIER_MS) { started.await() } }
        assertTrue("the rows must be dimmed while the write is in flight", viewModel.state.value.pending)

        // A is still being written; B must not start a second write racing it.
        viewModel.dispatch(Intent.Select(2))
        gate.complete(Unit)

        verify(timeout = BARRIER_MS) { settingsManager.emitTranslateSettingUpdate(ROOM, 1) }
        runBlocking { delay(SETTLE_MS) }
        verify(exactly = 0) { dbRoomStore.updateTranslateSetting(ROOM, 2) }
        // The screen is left on the confirmed value, not on the tap that was dropped.
        assertEquals(1, viewModel.state.value.selected)
        assertFalse("the rows are live again once the write lands", viewModel.state.value.pending)
    }

    @Test
    fun `a value the row already holds skips the system message`() {
        // The init read says off; the pre-write read says another writer already stored 1.
        every { dbRoomStore.getTranslateSetting(ROOM) } returns 0 andThen 1
        val viewModel = loadedViewModel()

        viewModel.dispatch(Intent.Select(1))

        verify(timeout = BARRIER_MS) { settingsManager.emitTranslateSettingUpdate(ROOM, 1) }
        // The broadcast is the step right before the message would be created, so give that step a
        // moment to have happened before asserting that it did not.
        runBlocking { delay(SETTLE_MS) }
        coVerify(exactly = 0) { messageCreator.createTranslateSettingChangedMessage(any(), any()) }
        assertEquals(1, viewModel.state.value.selected)
    }

    private companion object {
        const val ROOM = "+10001"
        const val BARRIER_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_MS = 300L
    }
}
