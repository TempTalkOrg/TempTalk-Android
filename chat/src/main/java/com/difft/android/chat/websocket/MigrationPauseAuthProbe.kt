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

/**
 * Detects the end of the old login while a migration pause past the commit marker waits for it
 * (#1382): committing, awaiting the old login, awaiting a decision, or a corrupt record. The socket
 * is closed while paused, so the usual kick-out signals never arrive; instead the device list is
 * asked every [INTERVAL_MS] while the app is in the foreground, once on every foreground.
 *
 * Only a 401 the probe itself received ends the pause, through the ordinary passive logout. Each
 * probe is sent with the credential it read and on a path that does not report to
 * `AuthFailureSink`, so its own 403 changes nothing (#1382 A09, server question S06); a 401 that
 * arrives after the credential or the pause changed is ignored. Other answers keep waiting.
 */
@Singleton
class MigrationPauseAuthProbe @Inject constructor(
    private val gate: MessagingGate,
    private val deviceRepository: DeviceRepository,
    // Lazy: the logout manager is only needed once a kick-out is confirmed (same cycle as SessionAuthGuard).
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

    /** Probes while [foreground] is true and the pause waits for the old login. */
    internal suspend fun run(foreground: Flow<Boolean>, intervalMs: Long = INTERVAL_MS) {
        combine(gate.onChanged, foreground) { state, inForeground -> inForeground && state.awaitsOldLogin() }
            .distinctUntilChanged()
            .collectLatest { active ->
                if (!active) return@collectLatest
                while (!probeOnce()) delay(intervalMs)
            }
    }

    /** One device-list call; true once the old login is confirmed gone and the logout has started. */
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

    /** Acts only on this probe's own 401, and only while the login and the pause it asked about still hold. */
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

    /** Logged once per distinct failure, so an offline foreground does not log every interval. */
    private fun logOnce(failure: String, message: (String) -> String) {
        if (failure == lastFailure) return
        lastFailure = failure
        L.w { message(failure) }
    }

    /** A record past the commit marker, or a corrupt one whose phase is unknown (#1382 A08). */
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

        /** Paused past the commit marker, where the receiver may have taken over the account. */
        val AWAITING_PHASES = setOf(
            MigrationPausePhase.COMMITTING,
            MigrationPausePhase.AWAITING_LOGIN,
            MigrationPausePhase.AWAITING_DECISION,
        )
    }
}
