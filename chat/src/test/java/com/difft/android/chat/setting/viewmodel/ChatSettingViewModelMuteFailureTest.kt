package com.difft.android.chat.setting.viewmodel

import android.app.Activity
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.translate.FakeTranslateBackend
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.sync.ConfidentialModeSyncCoordinator
import com.difft.android.chat.setting.sync.ConversationSettingsApplier
import com.difft.android.chat.setting.sync.ServerConfigSnapshot
import com.difft.android.chat.setting.sync.SnapshotSource
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.network.responses.GetConversationSetResponseBody
import difft.android.messageserialization.For
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.difft.app.database.models.RoomModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull










@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatSettingViewModelMuteFailureTest {

    private val activity: Activity = mockk(relaxed = true)
    private val messageArchiveManager: MessageArchiveManager = mockk(relaxed = true)
    private val conversationSettingsManager: ConversationSettingsManager = mockk(relaxed = true)
    private val httpClient: ChativeHttpClient = mockk(relaxed = true)
    private val httpService: HttpService = mockk(relaxed = true)
    private val dbRoomStore: DBRoomStore = mockk(relaxed = true)
    private val settingsApplier: ConversationSettingsApplier = mockk(relaxed = true)
    private val confidentialSync: ConfidentialModeSyncCoordinator = mockk(relaxed = true)
    private val globalServicesMock: GlobalHiltEntryPoint = mockk(relaxed = true)
    private val userManager: UserManager = mockk(relaxed = true)
    private val settingUpdates = MutableSharedFlow<ConversationSettingUpdate>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        mockkObject(ToastUtil)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns MY_ID
        every { globalServicesMock.userManager } returns userManager
        every { userManager.getUserData() } returns null
        every { org.difft.app.database.wcdb } throws IllegalStateException("no database in unit tests")
        every { httpClient.httpService } returns httpService
        every { conversationSettingsManager.conversationSettingUpdate } returns settingUpdates
        every { messageArchiveManager.getDefaultMessageArchiveTime() } returns DEFAULT_EXPIRY
        every { activity.getString(any()) } returns "failed"
        every { ToastUtil.show(any<String>()) } returns Unit
        coEvery { settingsApplier.applyServerSnapshot(any(), any(), any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkObject(ToastUtil)
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        unmockkStatic("org.difft.app.database.WCDBExtensionsKt")
        Dispatchers.resetMain()
    }

    private fun viewModel() = ChatSettingViewModel(
        conversation = For.Account(CONVERSATION_ID),
        messageArchiveManager = messageArchiveManager,
        conversationSettingsManager = conversationSettingsManager,
        httpClient = httpClient,
        dbRoomStore = dbRoomStore,
        settingsApplier = settingsApplier,
        confidentialSync = confidentialSync,
        translateBackend = FakeTranslateBackend(),
    )

    
    private fun ChatSettingViewModel.mute() = runBlocking {
        withTimeout(JOIN_TIMEOUT_MS) {
            setConversationConfigs(
                activity = activity,
                conversation = CONVERSATION_ID,
                muteStatus = 1,
            ).join()
        }
    }

    




    private fun ChatSettingViewModel.seedUnmutedConfig() = runBlocking {
        settingUpdates.emit(ConversationSettingUpdate(conversationId = CONVERSATION_ID, muteStatus = 0))
        assertEquals(0, conversationSet.value?.muteStatus)
    }

    @Test
    fun `a rejected mute request leaves conversationSet untouched`() {
        coEvery { httpService.fetchConversationSet(any()) } returns
            BaseResponse(ver = 1, status = 10001, reason = "rejected", data = null)
        val vm = viewModel()
        assertNull(vm.conversationSet.value)

        vm.mute()

        
        assertNull(vm.conversationSet.value)
        verify { ToastUtil.show("rejected") }
    }

    @Test
    fun `a rejected mute request with no reason still toasts`() {
        coEvery { httpService.fetchConversationSet(any()) } returns
            BaseResponse(ver = 1, status = 10001, reason = null, data = null)
        val vm = viewModel()

        vm.mute()

        assertNull(vm.conversationSet.value)
        verify { ToastUtil.show("failed") }
    }

    




    @Test
    fun `an accepted mute request hands the echo to the applier and merges what it wrote`() {
        val echo = ConversationSetResponseBody(conversation = CONVERSATION_ID, muteStatus = 1, version = 7)
        coEvery { httpService.fetchConversationSet(any()) } returns BaseResponse(ver = 1, status = 0, reason = null, data = echo)
        coEvery { settingsApplier.applyServerSnapshot(any(), any(), any()) } returns
            ConversationSettingUpdate(conversationId = CONVERSATION_ID, muteStatus = 1)
        val vm = viewModel()

        vm.mute()

        assertEquals(1, vm.conversationSet.value?.muteStatus)
        coVerify(exactly = 1) { settingsApplier.applyServerSnapshot(ServerConfigSnapshot.fromEcho(echo), SnapshotSource.Echo, any()) }
        verify(exactly = 0) { dbRoomStore.updateConversationSettings(any(), any(), any(), any(), any(), any(), any()) }
    }

    





    @Test
    fun `an accepted mute request with no data merges the requested value`() {
        coEvery { httpService.fetchConversationSet(any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = null)
        val vm = viewModel()
        vm.seedUnmutedConfig()
        
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.fetchGetConversationSet(any()) }

        vm.mute()

        assertEquals(1, vm.conversationSet.value?.muteStatus)
        coVerify(exactly = 1) { httpService.fetchGetConversationSet(any()) }
        
        
        verify(exactly = 1) {
            dbRoomStore.updateConversationSettings(
                roomId = CONVERSATION_ID,
                muteStatus = 1,
                blockStatus = null,
                confidentialMode = null,
                messageExpiry = null,
                messageClearAnchor = null
            )
        }
    }

    



    @Test
    fun `an accepted mute request whose echo the applier drops leaves conversationSet untouched`() {
        coEvery { httpService.fetchConversationSet(any()) } returns BaseResponse(
            ver = 1,
            status = 0,
            reason = null,
            data = ConversationSetResponseBody(conversation = CONVERSATION_ID, muteStatus = 1),
        )
        every { dbRoomStore.getRoom(CONVERSATION_ID) } returns RoomModel().apply { roomId = CONVERSATION_ID }
        val vm = viewModel()
        vm.seedUnmutedConfig()

        vm.mute()

        assertEquals(0, vm.conversationSet.value?.muteStatus)
        coVerify(exactly = 1) { settingsApplier.applyServerSnapshot(any(), SnapshotSource.Echo, any()) }
    }

    




    @Test
    fun `the init pull creates the room row before the applier runs`() {
        val pulled = ConversationSetResponseBody(conversation = CONVERSATION_ID, muteStatus = 1, version = 6, messageExpiry = 3_600L)
        coEvery { httpService.fetchGetConversationSet(any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = GetConversationSetResponseBody(listOf(pulled)))

        viewModel()

        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) {
            settingsApplier.applyServerSnapshot(ServerConfigSnapshot.fromPull(pulled), SnapshotSource.Pull, any())
        }
        coVerifyOrder {
            dbRoomStore.createRoomIfNotExist(For.Account(CONVERSATION_ID))
            settingsApplier.applyServerSnapshot(ServerConfigSnapshot.fromPull(pulled), SnapshotSource.Pull, any())
        }
    }

    @Test
    fun `an echo creates the room row before the applier runs`() {
        val echo = ConversationSetResponseBody(conversation = CONVERSATION_ID, muteStatus = 1, version = 6)
        coEvery { httpService.fetchConversationSet(any()) } returns BaseResponse(ver = 1, status = 0, reason = null, data = echo)
        val vm = viewModel()
        
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.fetchGetConversationSet(any()) }
        verify(exactly = 0) { dbRoomStore.createRoomIfNotExist(any()) }

        vm.mute()

        coVerifyOrder {
            dbRoomStore.createRoomIfNotExist(For.Account(CONVERSATION_ID))
            settingsApplier.applyServerSnapshot(ServerConfigSnapshot.fromEcho(echo), SnapshotSource.Echo, any())
        }
    }

    
    @Test
    fun `an unchanged pull seeds an empty flow`() {
        val pulled = ConversationSetResponseBody(conversation = CONVERSATION_ID, muteStatus = 0, version = 6, messageExpiry = 3_600L)
        coEvery { httpService.fetchGetConversationSet(any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = GetConversationSetResponseBody(listOf(pulled)))
        val vm = viewModel()

        runBlocking { withTimeout(JOIN_TIMEOUT_MS) { while (vm.conversationSet.value == null) kotlinx.coroutines.delay(10) } }
        assertEquals(3_600L, vm.conversationSet.value?.messageExpiry)
    }

    







    @Test
    fun `an accepted mute request with no data and no config re-fetches it`() {
        coEvery { httpService.fetchConversationSet(any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = null)
        val vm = viewModel()
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.fetchGetConversationSet(any()) }
        assertNull(vm.conversationSet.value)

        vm.mute()

        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 2) { httpService.fetchGetConversationSet(any()) }
    }

    




    @Test
    fun `an accepted mute request with no config awaits the re-fetch and reports a failed one`() {
        coEvery { httpService.fetchConversationSet(any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = null)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var refreshes = 0
        coEvery { httpService.fetchGetConversationSet(any()) } coAnswers {
            
            if (++refreshes > 1) {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
            }
            throw IllegalStateException("offline")
        }
        val vm = viewModel()
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.fetchGetConversationSet(any()) }
        assertNull(vm.conversationSet.value)

        val job = vm.setConversationConfigs(
            activity = activity,
            conversation = CONVERSATION_ID,
            muteStatus = 1,
        )
        runBlocking { withTimeout(JOIN_TIMEOUT_MS) { refreshStarted.await() } }

        
        assertFalse(job.isCompleted)

        releaseRefresh.complete(Unit)
        runBlocking { withTimeout(JOIN_TIMEOUT_MS) { job.join() } }

        assertNull(vm.conversationSet.value)
        verify { ToastUtil.show("failed") }
    }

    @Test
    fun `a thrown mute request leaves conversationSet untouched`() {
        coEvery { httpService.fetchConversationSet(any()) } throws IllegalStateException("offline")
        val vm = viewModel()

        vm.mute()

        assertNull(vm.conversationSet.value)
        verify { ToastUtil.show("failed") }
    }

    private companion object {
        const val MY_ID = "test-user-001"
        const val CONVERSATION_ID = "conversation-1"
        const val DEFAULT_EXPIRY = 86400L
        const val VERIFY_TIMEOUT_MS = 5_000L
        const val JOIN_TIMEOUT_MS = 5_000L
    }
}
