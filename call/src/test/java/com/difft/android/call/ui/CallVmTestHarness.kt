package com.difft.android.call.ui

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.media.CallAudioSetup
import com.difft.android.network.UrlManager
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import dagger.hilt.EntryPoints
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared Phase-A-only [LCallViewModel] construction harness for `:call` Compose integration tests
 * (T5-* rows). Never calls `startRoomDependentWiring()`, so it needs none of
 * `LCallViewModelTwoPhaseInitTest`'s Phase-B
 * mocks (LiveKit, RoomEventDispatcher, CallSessionStarter, CallObservers) — those tests exercise
 * Phase B; these tests only exercise the Compose UI rendered off a constructed-but-never-connected
 * ViewModel (`callStatus`/`callType`/`participants`/`callUiController`/`timerManager` are all set
 * up during Phase A, i.e. the constructor).
 *
 * [CallRoomController]'s real constructor is cheap (only `MutableStateFlow` init, no I/O — see
 * `CallRoomController.kt`), so it is allowed to run for real; `mockkConstructor` only intercepts
 * the specific member calls stubbed via [stubCallStatus] (`callStatus`/`callType`). No other
 * `CallRoomController` method is reachable from Phase A + `getCallRoomName()` + `participants`.
 */
internal object CallVmTestHarness {

    /** Must run before [buildViewModel]. */
    fun mockConstructionCollaborators() {
        mockkConstructor(AudioPipelineProcessor::class)
        every { anyConstructed<AudioPipelineProcessor>().setModule(any()) } just Runs
        every { anyConstructed<AudioPipelineProcessor>().setSoundTouchPreset(any()) } just Runs
        every { anyConstructed<AudioPipelineProcessor>().setDenoiseEnabled(any()) } just Runs
        every { anyConstructed<AudioPipelineProcessor>().release() } just Runs

        mockkConstructor(AudioDeviceManager::class)
        every { anyConstructed<AudioDeviceManager>().audioHandler } returns mockk(relaxed = true)
        every { anyConstructed<AudioDeviceManager>().switchVoicePreset(any()) } just Runs
        every { anyConstructed<AudioDeviceManager>().initDeNoiseMode(any()) } just Runs

        mockkConstructor(CallAudioSetup::class)
        every { anyConstructed<CallAudioSetup>().start() } just Runs
        every { anyConstructed<CallAudioSetup>().stop() } just Runs

        mockkConstructor(CallRoomController::class)
    }

    /** Overrides the constructed [CallRoomController]'s `callStatus`/`callType` StateFlows. */
    fun stubCallStatus(callStatus: CallStatus, callType: String) {
        every { anyConstructed<CallRoomController>().callStatus } returns MutableStateFlow(callStatus).asStateFlow()
        every { anyConstructed<CallRoomController>().callType } returns MutableStateFlow(callType).asStateFlow()
    }

    /**
     * `MainPageWithTopStatusView` resolves its OWN `contactorCacheManager` via
     * `EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(...)` (not the one injected
     * into [LCallViewModel]) — a pre-existing production path (`screenShareUserName`'s
     * resolution), reused unmodified by this task's `oneOnOnePeerName` resolution. A plain
     * (non-Hilt-generated) Robolectric test `Application` fails that lookup with
     * `IllegalStateException: ... does not implement ... GeneratedComponentManager`, so tests
     * that compose `MainPageWithTopStatusView` must stub the underlying `EntryPoints.get` call.
     * Must run before `composeTestRule.setContent { ... }`.
     *
     * Also stubs `GlobalHiltEntryPoint` (the same Hilt lookup mechanism, via `globalServices`):
     * group calls' `getCallRoomName()` eagerly constructs `CallTypeCoordinator`, whose ctor
     * param evaluation touches `LCallViewModel.mySelfId` → `globalServices.myId` →
     * `EntryPointAccessors.fromApplication<GlobalHiltEntryPoint>(...)` — the exact same
     * non-Hilt-test-Application failure mode, on a different entry point interface. Any other
     * entry point type falls through to the real (still-failing) call — no test here needs one.
     */
    fun mockEntryPointAccessors(contactorCacheManager: ContactorCacheManager = mockk(relaxed = true)): ContactorCacheManager {
        mockkStatic(EntryPoints::class)
        val entryPoint = mockk<LCallManager.EntryPoint>(relaxed = true)
        every { entryPoint.contactorCacheManager } returns contactorCacheManager
        val globalEntryPoint = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { globalEntryPoint.myId } returns "self-uid"
        every { EntryPoints.get(any(), LCallManager.EntryPoint::class.java) } returns entryPoint
        every { EntryPoints.get(any(), GlobalHiltEntryPoint::class.java) } returns globalEntryPoint
        return contactorCacheManager
    }

    fun buildCallIntent(
        action: CallIntent.Action,
        callType: String,
        conversationId: String? = "test-conversation",
        roomName: String? = "Test Room",
    ): CallIntent {
        val context = ApplicationProvider.getApplicationContext<ScopeApplication>()
        val intent = CallIntent.Builder(context, LCallActivity::class.java)
            .withAction(action)
            .withCallType(callType)
            .withConversationId(conversationId)
            .withRoomName(roomName)
            .build()
        return CallIntent(intent)
    }

    fun buildViewModel(
        callIntent: CallIntent,
        callRole: CallRole = CallRole.CALLEE,
        contactorCacheManager: ContactorCacheManager = mockk(relaxed = true),
        urlManager: UrlManager = mockk(relaxed = true),
    ): LCallViewModel {
        val application = ApplicationProvider.getApplicationContext<ScopeApplication>()
        ApplicationHelper.init(application)
        return LCallViewModel(
            application = application,
            e2eeEnable = false,
            callIntent = callIntent,
            callConfig = CallConfig(),
            callRole = callRole,
            initialVoicePreset = VoicePreset.ORIGINAL,
            callToChatController = mockk(relaxed = true),
            messageEncryptor = mockk(relaxed = true),
            onGoingCallStateManager = mockk(relaxed = true),
            callDataManager = mockk(relaxed = true),
            callVibrationManager = mockk(relaxed = true),
            callRingtoneManager = mockk(relaxed = true),
            contactorCacheManager = contactorCacheManager,
            callFeedbackManager = mockk(relaxed = true),
            callStatisticsLogManager = mockk(relaxed = true),
            callTimeoutManager = mockk(relaxed = true),
            callTlsProvider = mockk(relaxed = true),
            httpClient = dagger.Lazy { mockk(relaxed = true) },
            userManager = mockk(relaxed = true),
            proxyConfigProvider = mockk(relaxed = true),
            urlManager = urlManager,
        )
    }
}
