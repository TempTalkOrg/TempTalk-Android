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

/**
 * Builds [CallSession]s on the plain JVM: test dispatchers, a [FakeCallSessionComponentsFactory]
 * and a [CallSessionDeps] mock that hands out a REAL [OnGoingCallStateManager] (projection rows
 * assert its values). Phase B rows also call [PhaseBCollaboratorStubs.install].
 *
 * The session's room setup (Phase B, launched by its start) runs on [default]. By default that is
 * [parked], a dispatcher no test advances, so the room stays `Pending`; Phase B rows pass a
 * dispatcher they drive.
 *
 * Cleanup executor starts are captured ([cleanupStarts]) instead of running the steps on a real IO
 * thread, unless [captureCleanup] is false. Call [endAll] in `@After` (before `unmockkAll()`) so no
 * session `Job` outlives its test.
 */
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

    /** Every session this harness created, in creation order. */
    val sessions = mutableListOf<CallSession>()
    val factory = object : CallSession.Factory {
        override fun create(params: CallSessionParams, onEnded: (CallSession) -> Unit): CallSession =
            CallSession(params, onEnded, deps, dispatchers, components).also { sessions += it }
    }
    val holder = CallSessionHolder(factory)

    /** `(reason, steps)` of every cleanup-executor start, in call order. */
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

    /** Starts a session through the holder; fails the test when the start is refused or fails. */
    fun start(p: CallSessionParams = params()): CallSession = checkNotNull(holder.start(p)) { "session start refused or failed" }

    /**
     * [start] with a call page attached, as on a call screen: rows that advance virtual time past
     * the first-attach watchdog (10 s, CallSessionWatchdogTest) model a call that has its page.
     */
    fun startWithPage(p: CallSessionParams = params()): CallSession = start(p).also { it.attachUi() }

    /** Writer view of [s]'s UI state: the session exposes the read type, the instance is the writer. */
    fun uiWriter(s: CallSession) = s.uiState as CallSessionUiState

    /**
     * Ends every session (a no-op for an ended one) and cancels its `Job`, as the last cleanup step
     * does, so no session `Job` leaks into later tests.
     */
    fun endAll() {
        sessions.toList().forEach {
            it.end(CallSessionEndReason.EXIT_CLEAR)
            cancelScope(it)
        }
    }

    /** What the session's last cleanup step does to its coroutines; the captured steps never run here. */
    fun cancelScope(s: CallSession) = s.mainScope.cancel()

    companion object {
        /** One test dispatcher for both views, on [scheduler]. */
        fun onScheduler(scheduler: TestCoroutineScheduler) = CallSessionTestHarness(StandardTestDispatcher(scheduler))

        /** A dispatcher on a scheduler of its own that nothing advances: work sent there never runs. */
        fun parked(): CoroutineDispatcher = StandardTestDispatcher(TestCoroutineScheduler(), name = "parked")
    }
}
