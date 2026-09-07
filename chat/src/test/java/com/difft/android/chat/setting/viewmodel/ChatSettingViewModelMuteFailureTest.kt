package com.difft.android.chat.setting.viewmodel

import android.app.Activity
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.responses.ConversationSetResponseBody
import difft.android.messageserialization.For
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * `setConversationConfigs` is what the mute toggles drive, and those toggles are controlled: they
 * move only when [ChatSettingViewModel.conversationSet] re-emits. So a rejected or thrown request
 * must leave that flow untouched — otherwise a failed mute would still look applied.
 *
 * Main is an unconfined test dispatcher and the returned job is joined rather than advanced on a
 * scheduler: the request hops through `Dispatchers.IO`, which no test scheduler controls.
 * Robolectric is needed only for the framework classes `ToastUtil` touches at class-init time.
 */
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
    )

    /** Sends a mute request and blocks until the returned job settled. */
    private fun ChatSettingViewModel.mute() = runBlocking {
        withTimeout(JOIN_TIMEOUT_MS) {
            setConversationConfigs(
                activity = activity,
                conversation = CONVERSATION_ID,
                muteStatus = 1,
            ).join()
        }
    }

    /**
     * Gives the view model a config to merge into, through the same update channel the rest of the
     * app uses. Deterministic: the collector runs on the unconfined main dispatcher, so the state
     * is there when [emit][MutableSharedFlow.emit] returns.
     */
    private fun ChatSettingViewModel.seedUnmutedConfig() = runBlocking {
        settingUpdates.emit(ConversationSettingUpdate(conversationId = CONVERSATION_ID, muteStatus = 0))
        assertEquals(0, conversationSet.value?.muteStatus)
    }

    @Test
    fun `a rejected mute request leaves conversationSet untouched`() {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns
            BaseResponse(ver = 1, status = 10001, reason = "rejected", data = null)
        val vm = viewModel()
        assertNull(vm.conversationSet.value)

        vm.mute()

        // No emission: the controlled switch has nothing to follow, so it stays where it was.
        assertNull(vm.conversationSet.value)
        verify { ToastUtil.show("rejected") }
    }

    @Test
    fun `a rejected mute request with no reason still toasts`() {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns
            BaseResponse(ver = 1, status = 10001, reason = null, data = null)
        val vm = viewModel()

        vm.mute()

        assertNull(vm.conversationSet.value)
        verify { ToastUtil.show("failed") }
    }

    @Test
    fun `an accepted mute request emits the new config`() {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns BaseResponse(
            ver = 1,
            status = 0,
            reason = null,
            data = ConversationSetResponseBody(conversation = CONVERSATION_ID, muteStatus = 1),
        )
        val vm = viewModel()

        vm.mute()

        assertEquals(1, vm.conversationSet.value?.muteStatus)
        // An echoed body is persisted as narrowly as an empty one: only the requested columns, so
        // a concurrent change to any other column survives.
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

    /**
     * Accepted with an empty body: the requested value is merged into the config already held, so
     * the controlled switch learns it from the same emission a full echo would have produced — and
     * without a re-fetch, which would discard the request's own values and report nothing if it
     * failed.
     */
    @Test
    fun `an accepted mute request with no data merges the requested value`() {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = null)
        val vm = viewModel()
        vm.seedUnmutedConfig()
        // The startup fetch has landed, so anything past this count would be a refresh.
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.fetchGetConversationSet(any(), any()) }

        vm.mute()

        assertEquals(1, vm.conversationSet.value?.muteStatus)
        coVerify(exactly = 1) { httpService.fetchGetConversationSet(any(), any()) }
        // Only the requested column is written; the rest keep whatever the database holds instead
        // of being rewritten from an in-memory config.
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

    /**
     * The merged config reaches [ChatSettingViewModel.conversationSet] before the request persists
     * it, so a config update landing during that database hop is no longer overwritten by the
     * snapshot the request started from.
     */
    @Test
    fun `an accepted mute request emits before it persists`() {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns BaseResponse(
            ver = 1,
            status = 0,
            reason = null,
            data = ConversationSetResponseBody(conversation = CONVERSATION_ID, muteStatus = 1),
        )
        val vm = viewModel()
        var muteStatusAtWrite: Int? = null
        every { dbRoomStore.updateConversationSettings(any(), any(), any(), any(), any(), any()) } answers {
            muteStatusAtWrite = vm.conversationSet.value?.muteStatus
        }

        vm.mute()

        assertEquals(1, muteStatusAtWrite)
    }

    /**
     * Same empty body with nothing loaded yet: there is no config to merge into, so the whole one
     * is pulled instead. The observable effect is a second `fetchGetConversationSet` (the first
     * runs from the view model's init).
     *
     * That refresh runs in its own view-model coroutine that hops to `Dispatchers.IO`, so joining
     * the mute job does not await it — hence the verification timeout rather than a bare count.
     */
    @Test
    fun `an accepted mute request with no data and no config re-fetches it`() {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = null)
        val vm = viewModel()
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.fetchGetConversationSet(any(), any()) }
        assertNull(vm.conversationSet.value)

        vm.mute()

        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 2) { httpService.fetchGetConversationSet(any(), any()) }
    }

    /**
     * Same empty body with nothing loaded, but watching the job rather than the call count: the
     * fallback re-fetch is awaited, so the job - and the wait dialog a caller gates on it - stays
     * up until the config is there, and a re-fetch that brings nothing back is reported.
     */
    @Test
    fun `an accepted mute request with no config awaits the re-fetch and reports a failed one`() {
        coEvery { httpService.fetchConversationSet(any(), any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = null)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var refreshes = 0
        coEvery { httpService.fetchGetConversationSet(any(), any()) } coAnswers {
            // The first call is the view model's own startup refresh; hold the fallback one.
            if (++refreshes > 1) {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
            }
            throw IllegalStateException("offline")
        }
        val vm = viewModel()
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { httpService.fetchGetConversationSet(any(), any()) }
        assertNull(vm.conversationSet.value)

        val job = vm.setConversationConfigs(
            activity = activity,
            conversation = CONVERSATION_ID,
            muteStatus = 1,
        )
        runBlocking { withTimeout(JOIN_TIMEOUT_MS) { refreshStarted.await() } }

        // The re-fetch is still in flight, so the request has not released its guard yet.
        assertFalse(job.isCompleted)

        releaseRefresh.complete(Unit)
        runBlocking { withTimeout(JOIN_TIMEOUT_MS) { job.join() } }

        assertNull(vm.conversationSet.value)
        verify { ToastUtil.show("failed") }
    }

    @Test
    fun `a thrown mute request leaves conversationSet untouched`() {
        coEvery { httpService.fetchConversationSet(any(), any()) } throws IllegalStateException("offline")
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
