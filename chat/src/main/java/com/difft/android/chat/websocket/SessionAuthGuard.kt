package com.difft.android.chat.websocket

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.session.AuthFailureSink
import com.difft.android.base.session.AuthFailureSource
import com.difft.android.base.session.InactiveDeviceNotifier
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
























@Singleton
class SessionAuthGuard @Inject constructor(
    private val appWebSocketHelper: AppWebSocketHelper,
    private val deviceRepository: DeviceRepository,
    
    
    private val logoutManager: dagger.Lazy<LogoutManager>,
    private val userManager: UserManager,
    @param:AppCoroutineScope private val scope: CoroutineScope,
) : AuthFailureSink {

    
    
    private val signals = Channel<String>(Channel.CONFLATED)

    
    private val loggedOut = AtomicBoolean(false)

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) {
            L.w { "[SessionAuthGuard] already started, ignoring duplicate call" }
            return
        }
        L.i { "[SessionAuthGuard] start" }
        job = scope.launch {
            launch { collectSocketState() }
            launch { consumeSignals() }
        }
    }

    @Synchronized
    fun stop() {
        L.i { "[SessionAuthGuard] stop" }
        job?.cancel()
        job = null
        drainSignals()
    }

    
    override fun onAuthRejected(source: String) {
        if (loggedOut.get()) return
        signals.trySend(source)
    }

    private suspend fun collectSocketState() {
        appWebSocketHelper.chatDataWebSocketConnection.webSocketConnectionState.collect { state ->
            when (state) {
                WebSocketConnectionState.AUTHENTICATION_FAILED ->
                    onAuthRejected(AuthFailureSource.WS_HANDSHAKE)

                WebSocketConnectionState.INACTIVE_FAILED -> InactiveDeviceNotifier.raise()

                
                
                
                WebSocketConnectionState.CONNECTED -> InactiveDeviceNotifier.clear()

                
                WebSocketConnectionState.CONNECTING,
                WebSocketConnectionState.DISCONNECTED,
                WebSocketConnectionState.FAILED,
                WebSocketConnectionState.UNKNOWN_HOST_FAILED -> Unit
            }
        }
    }

    private suspend fun consumeSignals() {
        for (source in signals) {
            if (loggedOut.get()) return
            confirm(source)
            delay(MIN_CONFIRM_INTERVAL_MS)
        }
    }

    





    private suspend fun confirm(source: String) {
        val credential = currentCredential()
        if (credential == null) {
            L.i { "[SessionAuthGuard] confirm skipped source=$source reason=noCredential" }
            return
        }
        L.i { "[SessionAuthGuard] confirming source=$source" }
        try {
            deviceRepository.checkDeviceAuthAs(credential, reportAuthFailure = true)
            L.i { "[SessionAuthGuard] confirm: credential still valid, false alarm source=$source" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthorizationFailedException) {
            if (currentCredential() != credential) {
                L.w { "[SessionAuthGuard] confirm ignored code=${e.code} source=$source reason=credentialChanged" }
                return
            }
            if (!loggedOut.compareAndSet(false, true)) return
            L.w { "[SessionAuthGuard] kick-out confirmed code=${e.code} source=$source, logging out" }
            logoutManager.get().doLogoutWithoutRemoveData()
        } catch (e: Exception) {
            L.w { "[SessionAuthGuard] confirm inconclusive source=$source: ${e.stackTraceToString()}" }
        }
    }

    private fun currentCredential(): String? = userManager.getUserData()?.baseAuth?.takeIf { it.isNotEmpty() }

    private fun drainSignals() {
        while (signals.tryReceive().isSuccess) Unit
    }

    private companion object {
        
        
        const val MIN_CONFIRM_INTERVAL_MS = 5_000L
    }
}
