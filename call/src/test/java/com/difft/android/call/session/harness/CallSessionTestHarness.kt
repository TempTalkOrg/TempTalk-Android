package com.difft.android.call.session.harness

import android.app.Application
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.CallIntent
import com.difft.android.call.cleanup.CallCleanupExecutor
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.manager.CallTimeoutManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.service.OngoingCallServiceController
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionDeps
import com.difft.android.call.session.CallSessionDispatchers
import com.difft.android.call.session.CallSessionEndReason
import com.difft.android.call.session.CallSessionHolder
import com.difft.android.call.session.CallSessionParams
import com.difft.android.call.session.CallSessionUiState
import com.difft.android.call.state.OnGoingCallStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.Collections














internal class CallSessionTestHarness(
    val main: CoroutineDispatcher,
    val default: CoroutineDispatcher = parked(),
    val contactorCacheManager: ContactorCacheManager = mockk(relaxed = true),
    captureCleanup: Boolean = true,
) {
    val ogsm = OnGoingCallStateManager()
    val components = FakeCallSessionComponentsFactory()
    val ringtone = mockk<CallRingtoneManager>(relaxed = true)
    val callTimeoutManager = mockk<CallTimeoutManager>(relaxed = true)
    val application = mockk<Application>(relaxed = true)
    val ongoingCallService = mockk<OngoingCallServiceController>(relaxed = true)
    val deps: CallSessionDeps = mockk(relaxed = true) {
        every { application } returns this@CallSessionTestHarness.application
        every { ongoingCallService } returns this@CallSessionTestHarness.ongoingCallService
        every { onGoingCallStateManager } returns ogsm
        every { callRingtoneManager } returns ringtone
        every { callTimeoutManager } returns this@CallSessionTestHarness.callTimeoutManager
        every { contactorCacheManager } returns this@CallSessionTestHarness.contactorCacheManager
    }
    val dispatchers = CallSessionDispatchers(default = default, main = main)

    
    val sessions = mutableListOf<CallSession>()
    val factory = object : CallSession.Factory {
        override fun create(params: CallSessionParams, onEnded: (CallSession) -> Unit): CallSession =
            CallSession(params, onEnded, deps, dispatchers, components).also { sessions += it }
    }
    val holder = CallSessionHolder(factory)

    
    val cleanupStarts: MutableList<Pair<String, List<CallCleanupExecutor.Step>>> =
        Collections.synchronizedList(mutableListOf())

    init {
        if (captureCleanup) {
            mockkConstructor(CallCleanupExecutor::class)
            every { anyConstructed<CallCleanupExecutor>().start(any(), any()) } answers {
                cleanupStarts += firstArg<String>() to secondArg<List<CallCleanupExecutor.Step>>()
            }
        }
    }

    fun params(
        action: CallIntent.Action = CallIntent.Action.START_CALL,
        type: String = CallType.ONE_ON_ONE.type,
        role: CallRole = CallRole.CALLER,
        callIntent: CallIntent = CallIntentFixture.build(action, type),
        launchKey: String = "",
        needAppLock: Boolean = false,
        entryWaitToken: Int = ComposeDialogManager.NO_CLAIM,
    ) = CallSessionParams(
        e2eeEnable = false,
        callIntent = callIntent,
        callConfig = CallConfig(),
        callRole = role,
        initialVoicePreset = VoicePreset.ORIGINAL,
        launchKey = launchKey,
        needAppLock = needAppLock,
        entryWaitToken = entryWaitToken,
    )

    
    fun start(p: CallSessionParams = params()): CallSession = checkNotNull(holder.start(p)) { "session start refused or failed" }

    



    fun startWithPage(p: CallSessionParams = params()): CallSession = start(p).also { it.attachUi() }

    
    fun uiWriter(s: CallSession) = s.uiState as CallSessionUiState

    



    fun endAll() {
        sessions.toList().forEach {
            it.end(CallSessionEndReason.EXIT_CLEAR)
            cancelScope(it)
        }
    }

    
    fun cancelScope(s: CallSession) = s.mainScope.cancel()

    companion object {
        
        fun onScheduler(scheduler: TestCoroutineScheduler) = CallSessionTestHarness(StandardTestDispatcher(scheduler))

        
        fun parked(): CoroutineDispatcher = StandardTestDispatcher(TestCoroutineScheduler(), name = "parked")
    }
}
