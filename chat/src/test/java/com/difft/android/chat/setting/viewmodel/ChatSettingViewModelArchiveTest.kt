package com.difft.android.chat.setting.viewmodel

import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.sync.ConfidentialModeSyncCoordinator
import com.difft.android.chat.setting.sync.ConversationSettingsApplier
import com.difft.android.chat.translate.FakeTranslateBackend
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import difft.android.messageserialization.For
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The archive-time update shows no wait itself (design §3.10 W3): it exposes [ChatSettingViewModel.archiveBusy]
 * for the page's wait and one error message per failure, and runs one update at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatSettingViewModelArchiveTest {

    private val messageArchiveManager: MessageArchiveManager = mockk(relaxed = true)
    private val conversationSettingsManager: ConversationSettingsManager = mockk(relaxed = true)
    private val globalServicesMock: GlobalHiltEntryPoint = mockk(relaxed = true)
    private val userManager: UserManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.userManager } returns userManager
        every { userManager.getUserData() } returns null
        every { conversationSettingsManager.conversationSettingUpdate } returns MutableSharedFlow<ConversationSettingUpdate>()
        every { messageArchiveManager.getDefaultMessageArchiveTime() } returns 0L
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        Dispatchers.resetMain()
    }

    private fun viewModel() = ChatSettingViewModel(
        conversation = For.Account("u1"),
        messageArchiveManager = messageArchiveManager,
        conversationSettingsManager = conversationSettingsManager,
        httpClient = mockk<ChativeHttpClient>(relaxed = true),
        dbRoomStore = mockk<DBRoomStore>(relaxed = true),
        settingsApplier = mockk<ConversationSettingsApplier>(relaxed = true),
        confidentialSync = mockk<ConfidentialModeSyncCoordinator>(relaxed = true),
        translateBackend = FakeTranslateBackend(),
    )

    private fun awaitIdle(vm: ChatSettingViewModel) = runBlocking {
        withTimeout(TIMEOUT_MS) { vm.archiveBusy.first { !it } }
    }

    @Test
    fun `busy while the update runs, one update at a time`() {
        val server = CompletableDeferred<Unit>()
        coEvery { messageArchiveManager.updateMessageArchiveTime(any(), any()) } coAnswers { server.await() }
        val vm = viewModel()

        vm.updateArchiveTime(60)
        runBlocking { withTimeout(TIMEOUT_MS) { vm.archiveBusy.first { it } } }
        vm.updateArchiveTime(120)
        server.complete(Unit)
        awaitIdle(vm)

        assertFalse(vm.archiveBusy.value)
        coVerify(exactly = 1) { messageArchiveManager.updateMessageArchiveTime(any(), any()) }
    }

    @Test
    fun `a failed update reports its message once`() {
        coEvery { messageArchiveManager.updateMessageArchiveTime(any(), any()) } throws IllegalStateException("net down")
        val vm = viewModel()

        vm.updateArchiveTime(60)
        awaitIdle(vm)

        val first = runBlocking { withTimeout(TIMEOUT_MS) { vm.archiveErrors.first() } }
        val second = runBlocking { withTimeoutOrNull(SHORT_MS) { vm.archiveErrors.first() } }
        assertEquals("net down", first)
        assertNull(second)
        assertFalse(vm.archiveBusy.value, "a new update may start")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SHORT_MS = 200L
    }
}
