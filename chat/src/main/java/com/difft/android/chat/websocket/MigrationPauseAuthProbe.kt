package com.difft.android.chat.websocket

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import util.AppForegroundObserver
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton












@Singleton
class MigrationPauseAuthProbe @Inject constructor(
    private val gate: MessagingGate,
    private val deviceRepository: DeviceRepository,
    
    private val logoutManager: dagger.Lazy<LogoutManager>,
    private val userManager: UserManager,
    @param:AppCoroutineScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val kicked = AtomicBoolean(false)

    @Volatile
    private var lastFailure: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { run(foregroundStates()) }
    }

    
    internal suspend fun run(foreground: Flow<Boolean>, intervalMs: Long = INTERVAL_MS) {
        combine(gate.onChanged, foreground) { state, inForeground -> inForeground && state.awaitsOldLogin() }
            .distinctUntilChanged()
            .collectLatest { active ->
                if (!active) return@collectLatest
                while (!probeOnce()) delay(intervalMs)
            }
    }

    
    internal suspend fun probeOnce(): Boolean {
        if (kicked.get()) return true
        val credential = currentCredential() ?: return false
        val pauseOwner = pauseOwner()
        return try {
            deviceRepository.checkDeviceAuthAs(credential, reportAuthFailure = false, logSuccess = false)
            lastFailure = null
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthorizationFailedException) {
            onRejected(e.code, credential, pauseOwner)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logOnce(e.javaClass.simpleName) { "[MigrationPause] event=auth_probe_result result=inconclusive error=$it" }
            false
        }
    }

    
    private fun onRejected(code: Int, credential: String, pauseOwner: String?): Boolean {
        if (code != HTTP_UNAUTHORIZED) {
            logOnce("code_$code") { "[MigrationPause] event=auth_probe_result result=ignored code=$code" }
            return false
        }
        if (currentCredential() != credential || !gate.state.awaitsOldLogin() || pauseOwner() != pauseOwner) {
            L.w { "[MigrationPause] event=auth_probe_result result=stale code=$code" }
            return false
        }
        if (kicked.compareAndSet(false, true)) {
            L.w { "[MigrationPause] event=auth_probe_result result=kicked code=$code" }
            logoutManager.get().doLogoutWithoutRemoveData()
        }
        return true
    }

    private fun currentCredential(): String? = userManager.getUserData()?.baseAuth?.takeIf { it.isNotEmpty() }

    private fun pauseOwner(): String? = (gate.state as? MessagingPauseState.Paused)?.record?.owner

    
    private fun logOnce(failure: String, message: (String) -> String) {
        if (failure == lastFailure) return
        lastFailure = failure
        L.w { message(failure) }
    }

    
    private fun MessagingPauseState.awaitsOldLogin(): Boolean {
        if (this !is MessagingPauseState.Paused) return false
        return record?.phase?.let { it in AWAITING_PHASES } ?: true
    }

    private fun foregroundStates(): Flow<Boolean> = callbackFlow {
        val listener = object : AppForegroundObserver.Listener {
            override fun onForeground() {
                trySend(true)
            }

            override fun onBackground() {
                trySend(false)
            }
        }
        trySend(AppForegroundObserver.isForegrounded())
        AppForegroundObserver.addListener(listener)
        awaitClose { AppForegroundObserver.removeListener(listener) }
    }

    internal companion object {
        const val INTERVAL_MS = 10_000L
        private const val HTTP_UNAUTHORIZED = 401

        
        val AWAITING_PHASES = setOf(
            MigrationPausePhase.COMMITTING,
            MigrationPausePhase.AWAITING_LOGIN,
            MigrationPausePhase.AWAITING_DECISION,
        )
    }
}
