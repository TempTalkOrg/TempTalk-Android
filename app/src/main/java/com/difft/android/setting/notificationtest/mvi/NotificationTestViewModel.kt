package com.difft.android.setting.notificationtest.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationTestCallUpdate
import com.difft.android.base.notification.NotificationTestClock
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestOwner
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.notification.NotificationTestRun
import com.difft.android.base.notification.NotificationTestRuns
import com.difft.android.base.notification.OemBrand
import com.difft.android.setting.notificationtest.NotificationTestDevice
import com.difft.android.setting.notificationtest.NotificationTestLifetime
import com.difft.android.setting.notificationtest.NotificationTestPoster
import com.difft.android.setting.notificationtest.NotificationTestReturnRoute
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject


@HiltViewModel
class NotificationTestViewModel internal constructor(
    private val repository: NotificationTestRepository,
    private val poster: NotificationTestPoster,
    private val device: NotificationTestDevice,
    private val leases: NotificationTestRuns,
    private val lifetime: NotificationTestLifetime,
    private val clock: NotificationTestClock,
    savedState: SavedStateHandle,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        repository: NotificationTestRepository,
        poster: NotificationTestPoster,
        device: NotificationTestDevice,
        leases: NotificationTestRuns,
        lifetime: NotificationTestLifetime,
        clock: NotificationTestClock,
        savedState: SavedStateHandle,
    ) : this(repository, poster, device, leases, lifetime, clock, savedState, Dispatchers.IO)

    val owner = NotificationTestOwner(
        savedState.get<String>(OWNER_ID) ?: UUID.randomUUID().toString().also { savedState[OWNER_ID] = it },
        if (savedState.get<Boolean>(SHELL) == true) NotificationTestOwner.Host.SHELL else NotificationTestOwner.Host.HOSTED,
    )
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect: Flow<Effect> = _effect.receiveAsFlow()
    private var runJob: Job? = null
    private var activeRun: NotificationTestRun? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var precheckGeneration = 0

    init { reload() }

    fun matchesReturn(route: NotificationTestReturnRoute): Boolean =
        activeRun?.runId == route.runId && route.ownerId == owner.ownerId && leases.matches(route.ownerId, route.runId, route.kind)

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.Resume -> {
                reload()
                val current = activeRun
                if (current != null && _state.value.phase == Phase.POSTED && current.kind != NotificationTestKind.CALL) {
                    _state.update { it.copy(dialog = Dialog.Question(current.kind, current.runId)) }
                }
            }
            is Intent.Start -> onStart(intent.kind)
            Intent.ConfirmStart -> onConfirmStart()
            Intent.Cancel -> {
                if (activeRun != null) {
                    _state.update { it.copy(phase = Phase.FINISHING, dialog = null) }
                    runJob?.cancel()
                }
            }
            is Intent.Answer -> onAnswer(intent)
            Intent.DismissDialog -> {
                precheckGeneration++
                _state.update { if (it.dialog is Dialog.Question) it else it.copy(dialog = null) }
            }
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

    private fun onStart(kind: NotificationTestKind) {
        if (activeRun != null) return
        val generation = ++precheckGeneration
        viewModelScope.launch {
            try {
                val readiness = repository.readReadiness()
                val dnd = withContext(io) { device.isDndOn() }
                if (activeRun != null || generation != precheckGeneration) return@launch
                val block = NotificationTestRules.precheck(kind, readiness, dnd)
                if (block != null) L.i { "[NotificationTest] blocked kind=${kind.logName} block=${block.block}" }
                _state.update {
                    it.copy(dialog = block?.let { blocked -> Dialog.Blocked(blocked.block, blocked.fix) } ?: Dialog.Intro(kind))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[NotificationTest] precheck_failed kind=${kind.logName} error=${e.javaClass.simpleName}" }
            }
        }
    }

    private fun onConfirmStart() {
        if (activeRun != null) return
        val intro = _state.value.dialog as? Dialog.Intro ?: return
        val run = leases.claim(owner, intro.kind)
        if (run == null) {
            L.i { "[NotificationTest] claim_rejected reason=busy" }
            _state.update { it.copy(dialog = Dialog.Info(Notice.BUSY)) }
            return
        }
        activeRun = run
        answer = CompletableDeferred()
        precheckGeneration++
        _state.update {
            it.copy(running = run.kind, runId = run.runId, phase = if (run.kind == NotificationTestKind.BANNER) Phase.COUNTDOWN else Phase.WAIT_LOCK, dialog = null)
        }
        runJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { runGuarded(run) }
    }

    private fun onAnswer(intent: Intent.Answer) {
        val run = activeRun ?: return
        val question = _state.value.dialog as? Dialog.Question ?: return
        if (intent.runId != run.runId || question.runId != run.runId || !leases.owns(run) ||
            _state.value.phase != Phase.POSTED || _state.value.postedFingerprint == null
        ) return
        _state.update { it.copy(phase = Phase.FINISHING, dialog = null) }
        answer?.complete(intent.seen)
    }

    private suspend fun runGuarded(run: NotificationTestRun) {
        var terminal: Dialog? = null
        var retainCallTicket = false
        try {
            L.i { "[NotificationTest] start kind=${run.kind.logName} host=${run.owner.host}" }
            if (run.kind == NotificationTestKind.BANNER) {
                delay(BANNER_DELAY_MS)
                if (withContext(io) { device.isDndOn() }) throw Voided(VoidReason.DND)
                post(run)
                delay(ASK_DELAY_MS)
                _state.update { if (it.phase == Phase.POSTED) it.copy(dialog = Dialog.Question(run.kind, run.runId)) else it }
            } else {
                val off = withTimeoutOrNull(LOCK_WAIT_MS) { device.screenOffEvents().first() }
                if (off == null) throw NoLock()
                val release = device.holdWakeLock(SCREEN_OFF_PHASE_MAX_MS)
                try {
                    delay(POST_DELAY_MS)
                    var elapsed = POST_DELAY_MS
                    while (withContext(io) { NotificationTestRules.awaitingLock(device.isKeyguardLocked(), device.isInteractive(), elapsed) }) {
                        delay(LOCK_POLL_MS)
                        elapsed += LOCK_POLL_MS
                    }
                    voidReasonNow(run.kind)?.let { throw Voided(it) }
                    post(run)
                    if (run.kind == NotificationTestKind.CALL) {
                        terminal = awaitCall(run)
                        retainCallTicket = true
                        return
                    }
                } finally { release() }
            }
            val seen = answer!!.await()
            check(leases.owns(run))
            val fingerprint = checkNotNull(_state.value.postedFingerprint)
            _state.update { it.copy(phase = Phase.FINISHING) }
            persist { repository.record(run.kind, if (seen) NotificationTestOutcome.OK else NotificationTestOutcome.FAIL, fingerprint) }
            terminal = if (seen) null else Dialog.Failed(run.kind, NotificationFix.MESSAGE_CATEGORY)
            logResult(run.kind, if (seen) "ok" else "fail", if (seen) "seen" else "not_seen")
        } catch (e: CancellationException) {
            logResult(run.kind, "void", "canceled")
            throw e
        } catch (e: Voided) {
            logResult(run.kind, "void", e.reason.logName)
            terminal = Dialog.Info(when (e.reason) {
                VoidReason.UNLOCKED -> Notice.VOID_UNLOCKED
                VoidReason.NOT_LOCKED -> Notice.VOID_NOT_LOCKED
                VoidReason.DND -> Notice.VOID_DND
            })
        } catch (e: NoLock) {
            logResult(run.kind, "void", "no_lock")
            terminal = Dialog.Info(Notice.NO_LOCK)
        } catch (e: PostFailed) {
            logResult(run.kind, "fail", "post_failed", degraded = true)
            terminal = Dialog.Info(Notice.POST_FAILED)
        } catch (e: PersistenceFailed) {
            L.w { "[NotificationTest] persistence_failed kind=${run.kind.logName}" }
            terminal = Dialog.Info(Notice.PERSISTENCE_FAILED)
        } catch (e: Exception) {
            L.w { "[NotificationTest] run_failed kind=${run.kind.logName} error=${e.javaClass.simpleName}" }
            logResult(run.kind, "void", "error")
            terminal = Dialog.Info(Notice.POST_FAILED)
        } finally {
            _state.update { it.copy(phase = Phase.FINISHING, dialog = null) }
            withContext(NonCancellable) {
                if (!lifetime.finish(run, retainCallTicket)) terminal = Dialog.Info(Notice.PERSISTENCE_FAILED)
                if (activeRun == run) {
                    activeRun = null
                    answer = null
                    _state.update { it.copy(running = null, runId = null, phase = null, postedAt = 0, postedFingerprint = null, dialog = terminal) }
                    reload()
                }
            }
        }
    }

    private suspend fun post(run: NotificationTestRun) {
        check(leases.owns(run))
        val postedAt = clock.nowMs()
        val fingerprint = repository.fingerprint(run.kind)
        if (run.kind == NotificationTestKind.CALL) persist { repository.prepareCall(run.runId, fingerprint, postedAt) }
        if (!poster.post(run)) throw PostFailed()
        _state.update {
            it.copy(phase = if (run.kind == NotificationTestKind.CALL) Phase.WAIT_CALL else Phase.POSTED, postedAt = postedAt, postedFingerprint = fingerprint)
        }
    }

    private suspend fun awaitCall(run: NotificationTestRun): Dialog? {
        val shown = withTimeoutOrNull(CALL_WAIT_MS) { repository.callShownRun.first { it == run.runId } } != null
        if (shown) return null
        val fix = if (withContext(io) { device.opensVendorPopupDirectly() }) NotificationFix.OEM_POPUP else null
        if (repository.callShownRun.value == run.runId) return null
        voidReasonNow(run.kind)?.let { throw Voided(it) }
        _state.update { it.copy(phase = Phase.FINISHING) }
        return when (repository.recordCallTimeout(run.runId, clock.nowMs())) {
            NotificationTestCallUpdate.RECORDED -> {
                logResult(run.kind, "fail", "timeout")
                Dialog.Failed(run.kind, fix)
            }
            NotificationTestCallUpdate.ALREADY_SHOWN -> null
            NotificationTestCallUpdate.REJECTED -> throw PostFailed()
            NotificationTestCallUpdate.PERSISTENCE_FAILED -> throw PersistenceFailed()
        }
    }

    private suspend fun voidReasonNow(kind: NotificationTestKind): VoidReason? = withContext(io) {
        NotificationTestRules.voidReason(kind, device.isKeyguardLocked(), device.isInteractive(), device.isDndOn())
    }

    private suspend fun persist(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PersistenceFailed()
        }
    }

    
    private fun logResult(kind: NotificationTestKind, outcome: String, reason: String, degraded: Boolean = false) {
        val line = { "[NotificationTest] result kind=${kind.logName} outcome=$outcome reason=$reason brand=${OemBrand.current()}" }
        if (degraded) L.w(line) else L.i(line)
    }

    private fun reload() {
        viewModelScope.launch {
            try {
                val results = repository.readReadiness().testResults
                _state.update { it.copy(loaded = true, results = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[NotificationTest] load_failed error=${e.javaClass.simpleName}" }
                _state.update { it.copy(loaded = true) }
            }
        }
    }

    override fun onCleared() {
        lifetime.cancelAndJoin(runJob)
        super.onCleared()
    }

    companion object {
        const val SHELL = "notification_test_shell"
        private const val OWNER_ID = "notification_test_owner_id"
        private const val BANNER_DELAY_MS = 5_000L
        private const val ASK_DELAY_MS = 3_000L
        private const val LOCK_WAIT_MS = 60_000L
        private const val POST_DELAY_MS = 2_000L
        private const val CALL_WAIT_MS = 15_000L
        private const val LOCK_POLL_MS = 500L
        private const val SCREEN_OFF_PHASE_MAX_MS = POST_DELAY_MS + NotificationTestRules.LOCK_SETTLE_MS + CALL_WAIT_MS + 5_000L
    }

    private class Voided(val reason: VoidReason) : Exception()
    private class PersistenceFailed : Exception()
    private class NoLock : Exception()
    private class PostFailed : Exception()
}
