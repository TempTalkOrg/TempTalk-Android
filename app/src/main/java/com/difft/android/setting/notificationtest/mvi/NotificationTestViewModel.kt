package com.difft.android.setting.notificationtest.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.notification.OemBrand
import com.difft.android.setting.notificationtest.NotificationTestDevice
import com.difft.android.setting.notificationtest.NotificationTestPoster
import com.difft.android.setting.notificationtest.NotificationTestRules
import com.difft.android.setting.notificationtest.VoidReason
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Dialog
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Effect
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Intent
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Notice
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Phase
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.State
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The notification test page (#1350). One test runs at a time; it lives only in this ViewModel, so
 * a killed process simply abandons it. Only outcomes the user or the system actually produced are
 * recorded: a device unlocked and in use again, Do Not Disturb, a failed post or an error void the
 * run instead.
 */
@HiltViewModel
class NotificationTestViewModel internal constructor(
    private val repository: NotificationTestRepository,
    private val poster: NotificationTestPoster,
    private val device: NotificationTestDevice,
    private val io: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        repository: NotificationTestRepository,
        poster: NotificationTestPoster,
        device: NotificationTestDevice,
    ) : this(repository, poster, device, Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect: Flow<Effect> = _effect.receiveAsFlow()

    /** A new value cancels the run in progress; null only stops it. */
    private val runs = MutableSharedFlow<NotificationTestKind?>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch { runs.collectLatest { kind -> if (kind != null) runGuarded(kind) } }
        reload()
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.Resume -> onResume()
            is Intent.Start -> onStart(intent.kind)
            Intent.ConfirmStart -> onConfirmStart()
            Intent.Cancel -> onCancel()
            is Intent.Answer -> onAnswer(intent.seen)
            Intent.DismissDialog -> _state.update { if (it.dialog is Dialog.Question) it else it.copy(dialog = null) }
            is Intent.OpenFix -> {
                _state.update { it.copy(dialog = null) }
                _effect.trySend(Effect.OpenFix(intent.fix))
            }
            Intent.OpenGuide -> {
                _state.update { it.copy(dialog = null) }
                _effect.trySend(Effect.OpenGuide)
            }
        }
    }

    private fun onResume() {
        reload()
        // The banner test also asks on its own timer; the lock-screen test asks once the user is back.
        _state.update {
            val asking = it.phase == Phase.POSTED && it.dialog == null && it.running != NotificationTestKind.CALL
            if (asking) it.copy(dialog = Dialog.Question(it.running ?: return@update it)) else it
        }
    }

    private fun onStart(kind: NotificationTestKind) {
        if (_state.value.running != null) return
        viewModelScope.launch {
            try {
                val readiness = repository.readReadiness()
                val dndOn = withContext(io) { device.isDndOn() }
                val block = NotificationTestRules.precheck(kind, readiness, dndOn)
                if (block != null) {
                    L.i { "[NotificationTest] blocked kind=${kind.logName} block=${block.block}" }
                    _state.update { it.copy(dialog = Dialog.Blocked(block.block, block.fix)) }
                } else {
                    _state.update { it.copy(dialog = Dialog.Intro(kind)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[NotificationTest] precheck failed kind=${kind.logName} error=${e.javaClass.simpleName}" }
            }
        }
    }

    private fun onConfirmStart() {
        val intro = _state.value.dialog as? Dialog.Intro ?: return
        _state.update { it.copy(dialog = null) }
        runs.tryEmit(intro.kind)
    }

    private fun onCancel() {
        val kind = _state.value.running ?: return
        runs.tryEmit(null)
        poster.cancel(kind)
        logResult(kind, "void", "canceled")
        _state.update { it.copy(running = null, phase = null, postedAt = 0, postedFingerprint = null, dialog = null) }
    }

    private fun onAnswer(seen: Boolean) {
        val question = _state.value.dialog as? Dialog.Question ?: return
        val kind = question.kind
        val fingerprint = _state.value.postedFingerprint
        poster.cancel(kind)
        val dialog = if (seen) null else Dialog.Failed(kind, NotificationFix.MESSAGE_CATEGORY)
        _state.update { it.copy(running = null, phase = null, postedAt = 0, postedFingerprint = null, dialog = dialog) }
        viewModelScope.launch {
            record(kind, if (seen) NotificationTestOutcome.OK else NotificationTestOutcome.FAIL, if (seen) "seen" else "not_seen", fingerprint)
            reload()
        }
    }

    /** Keeps the run collector alive: a failing run ends with a notice, a canceled one leaves no notification. */
    private suspend fun runGuarded(kind: NotificationTestKind) {
        try {
            runTest(kind)
        } catch (e: CancellationException) {
            // A post still in flight when the run was canceled has landed by now; take it back.
            poster.cancel(kind)
            throw e
        } catch (e: Exception) {
            L.w { "[NotificationTest] run failed kind=${kind.logName} error=${e.javaClass.simpleName}" }
            poster.cancel(kind)
            logResult(kind, "void", "error")
            endWith(Dialog.Info(Notice.POST_FAILED))
        }
    }

    private suspend fun runTest(kind: NotificationTestKind) {
        L.i { "[NotificationTest] start kind=${kind.logName}" }
        val firstPhase = if (kind == NotificationTestKind.BANNER) Phase.COUNTDOWN else Phase.WAIT_LOCK
        _state.update { it.copy(running = kind, phase = firstPhase, postedAt = 0, postedFingerprint = null, dialog = null) }
        if (kind == NotificationTestKind.BANNER) runBanner() else runLocked(kind)
    }

    private suspend fun runBanner() {
        val kind = NotificationTestKind.BANNER
        delay(BANNER_DELAY_MS)
        if (withContext(io) { device.isDndOn() }) return endVoid(kind, VoidReason.DND)
        if (post(kind) == null) return
        delay(ASK_DELAY_MS)
        _state.update {
            if (it.running == kind && it.phase == Phase.POSTED && it.dialog == null) it.copy(dialog = Dialog.Question(kind)) else it
        }
    }

    /**
     * Lock-screen and call tests: post 2 s after the screen goes off, giving a delayed keyguard up to
     * [NotificationTestRules.LOCK_SETTLE_MS] to lock, unless the device is in use again by then.
     */
    private suspend fun runLocked(kind: NotificationTestKind) {
        val screenOff = withTimeoutOrNull(LOCK_WAIT_MS) { device.screenOffEvents().first() }
        if (screenOff == null) {
            logResult(kind, "void", "no_lock")
            return endWith(Dialog.Info(Notice.NO_LOCK))
        }
        // The timers below run with the screen off; without a wake lock CPU suspend can stall them.
        val release = device.holdWakeLock(SCREEN_OFF_PHASE_MAX_MS)
        try {
            delay(POST_DELAY_MS)
            var sinceScreenOff = POST_DELAY_MS
            while (keyguardPending(sinceScreenOff)) {
                delay(LOCK_POLL_MS)
                sinceScreenOff += LOCK_POLL_MS
            }
            voidReasonNow(kind)?.let { return endVoid(kind, it) }
            val posted = post(kind) ?: return
            if (kind == NotificationTestKind.CALL) awaitCallScreen(posted)
        } finally {
            release()
        }
    }

    /** The test call screen opening is the objective success; [NotificationTestCallActivity] records it. */
    private suspend fun awaitCallScreen(posted: Posted) {
        val kind = NotificationTestKind.CALL
        val shown = withTimeoutOrNull(CALL_WAIT_MS) { repository.callShown.first { it >= posted.at } } != null
        if (!shown) {
            poster.cancel(kind)
            val reason = voidReasonNow(kind)
            when {
                // The screen raises its signal before its own write, so one opening at the timeout is seen here.
                repository.callShown.value >= posted.at -> Unit
                reason != null -> return endVoid(kind, reason)
                recordCallTimeout(posted) && repository.callShown.value < posted.at -> {
                    val fix = if (OemBrand.current() == OemBrand.XIAOMI) NotificationFix.OEM_POPUP else null
                    endWith(Dialog.Failed(kind, fix))
                    reload()
                    return
                }
            }
        }
        endWith(null)
        reload()
    }

    /** Stores the call test's FAIL; false when the call screen opened after all and its OK stands. */
    private suspend fun recordCallTimeout(posted: Posted): Boolean {
        val recorded = try {
            repository.recordCallTimeout(posted.fingerprint, posted.at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The timeout was observed; only storing it failed.
            L.w { "[NotificationTest] record failed kind=call error=${e.javaClass.simpleName}" }
            true
        }
        if (recorded) logResult(NotificationTestKind.CALL, "fail", "timeout")
        return recorded
    }

    private suspend fun keyguardPending(sinceScreenOffMs: Long): Boolean = withContext(io) {
        NotificationTestRules.awaitingLock(device.isKeyguardLocked(), device.isInteractive(), sinceScreenOffMs)
    }

    private suspend fun voidReasonNow(kind: NotificationTestKind): VoidReason? = withContext(io) {
        val locked = device.isKeyguardLocked()
        val reason = NotificationTestRules.voidReason(kind, locked, device.isInteractive(), device.isDndOn())
        if (reason == null && !locked) {
            val secure = device.isDeviceSecure()
            L.i { "[NotificationTest] counted screen_off keyguard=unlocked kind=${kind.logName} secure=$secure" }
        }
        reason
    }

    /** Null when the notification could not be handed to the system; the run has then ended. */
    private suspend fun post(kind: NotificationTestKind): Posted? {
        // Taken before posting: the call screen may open, and stamp its success, before notify() returns.
        val postedAt = System.currentTimeMillis()
        // The result describes the settings this notification was posted under, not those read at answer time.
        val fingerprint = repository.fingerprint(kind)
        if (kind == NotificationTestKind.CALL) repository.notePostedCall(fingerprint)
        val posted = withContext(io) { poster.post(kind) }
        if (!posted) {
            logResult(kind, "void", "post_failed")
            endWith(Dialog.Info(Notice.POST_FAILED))
            return null
        }
        val phase = if (kind == NotificationTestKind.CALL) Phase.WAIT_CALL else Phase.POSTED
        _state.update { if (it.running == kind) it.copy(phase = phase, postedAt = postedAt, postedFingerprint = fingerprint) else it }
        return Posted(postedAt, fingerprint)
    }

    private fun endVoid(kind: NotificationTestKind, reason: VoidReason) {
        logResult(kind, "void", reason.logName)
        val notice = when (reason) {
            VoidReason.UNLOCKED -> Notice.VOID_UNLOCKED
            VoidReason.NOT_LOCKED -> Notice.VOID_NOT_LOCKED
            VoidReason.DND -> Notice.VOID_DND
        }
        endWith(Dialog.Info(notice))
    }

    private fun endWith(dialog: Dialog?) {
        _state.update { it.copy(running = null, phase = null, postedAt = 0, postedFingerprint = null, dialog = dialog) }
    }

    private suspend fun record(kind: NotificationTestKind, outcome: NotificationTestOutcome, reason: String, fingerprint: String?) {
        logResult(kind, outcome.name.lowercase(), reason)
        // Only a posted notification has a snapshot; without one there is nothing to vouch for.
        if (fingerprint == null) return
        try {
            repository.record(kind, outcome, fingerprint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[NotificationTest] record failed kind=${kind.logName} error=${e.javaClass.simpleName}" }
        }
    }

    private fun reload() {
        viewModelScope.launch {
            try {
                val results = repository.readReadiness().testResults
                _state.update { it.copy(loaded = true, results = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[NotificationTest] load failed error=${e.javaClass.simpleName}" }
                _state.update { it.copy(loaded = true) }
            }
        }
    }

    private fun logResult(kind: NotificationTestKind, outcome: String, reason: String) {
        L.i { "[NotificationTest] result kind=${kind.logName} outcome=$outcome reason=$reason brand=${OemBrand.current()}" }
    }

    override fun onCleared() {
        // A test abandoned by leaving the page must not keep ringing or waiting on the lock screen.
        _state.value.running?.let { poster.cancel(it) }
        super.onCleared()
    }

    private companion object {
        const val BANNER_DELAY_MS = 5_000L
        const val ASK_DELAY_MS = 3_000L
        const val LOCK_WAIT_MS = 60_000L
        const val POST_DELAY_MS = 2_000L
        const val CALL_WAIT_MS = 15_000L
        const val LOCK_POLL_MS = 500L

        /** Upper bound of the screen-off phase: post delay, lock settle and call wait, plus margin. */
        const val SCREEN_OFF_PHASE_MAX_MS = POST_DELAY_MS + NotificationTestRules.LOCK_SETTLE_MS + CALL_WAIT_MS + 5_000L
    }

    /** A test notification handed to the system: when, and under which settings. */
    private data class Posted(val at: Long, val fingerprint: String)
}
