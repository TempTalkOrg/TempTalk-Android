package com.difft.android.call

import android.annotation.SuppressLint
import android.util.Log
import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.appScope
import com.difft.android.call.BuildConfig.DEBUG
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.ServerNode
import com.difft.android.call.receiver.NetworkConnectionListener
import com.difft.android.network.proxy.ProxyConfigProvider
import io.livekit.android.LiveKit
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import timber.log.Timber
import util.AppForegroundObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object LCallEngine {

    private lateinit var environmentHelper: EnvironmentHelper

    private var _serverNodeSelected = MutableStateFlow<ServerNode?>(null)
    val serverNodeSelected: StateFlow<ServerNode?> get() = _serverNodeSelected

    private var _serverUrlConnected = MutableStateFlow<String?>(null)
    val serverUrlConnected: StateFlow<String?> get() = _serverUrlConnected

    private var _connectionType = MutableStateFlow(CONNECTION_TYPE.WEB_SOCKET)
    val connectionType: StateFlow<CONNECTION_TYPE> get() = _connectionType
    @Volatile
    private var hasManualConnectionTypeOverride: Boolean = false

    private var _isNetworkAvailable = MutableStateFlow(false)

    fun init(context: Context, scope: CoroutineScope, environmentHelper: EnvironmentHelper) {
        this.environmentHelper = environmentHelper
        LiveKit.loggingLevel =
            if (DEBUG) LoggingLevel.VERBOSE else if (environmentHelper.isInsiderChannel()) LoggingLevel.VERBOSE else LoggingLevel.DEBUG
        LiveKit.loggingExternalPrefix = "[livekit] "
        LiveKit.logger = object : LKLog.Logger {
            




            @SuppressLint("TimberDirectCall")
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                if (L.enabled && Timber.treeCount > 0) {
                    val androidPriority = sdkNoisePriority(message) ?: priority.toAndroidLogPriority()
                    Timber.log(androidPriority, t, L.replaceUid(message))
                }
            }
        }
        LiveKit.enableWebRTCLogging = false

        registerNetworkConnectionListener(context, scope)

        LiveKit.init(context)
    }

    



    private fun sdkNoisePriority(message: String): Int? = when {
        
        "onIceCandidate" in message -> Log.DEBUG
        
        "early data is not enabled" in message -> Log.INFO
        else -> null
    }

    private fun registerNetworkConnectionListener(context: Context, scope: CoroutineScope) {
        val networkConnectionListener = NetworkConnectionListener(context) { isNetworkUnavailable ->
            L.d { "[Call] NetworkConnectionListener isNetworkUnavailable:${isNetworkUnavailable()}" }
            _isNetworkAvailable.value = !isNetworkUnavailable()
            if (!isNetworkUnavailable()) {
                if (!AppForegroundObserver.isForegrounded()) {
                    L.d { "[Call] NetworkConnectionListener skip fetch in background, defer to onAppForegrounded" }
                    return@NetworkConnectionListener
                }
                scope.launch(Dispatchers.IO) {
                    LCallManager.fetchCallServiceUrlAndCache()
                }
            }
        }
        networkConnectionListener.register()
    }

    fun setSelectedServerNode(server: ServerNode) {
        _serverNodeSelected.value = server
    }

    



    fun resetSelectedServerNode() {
        _serverNodeSelected.value = null
    }

    fun setConnectedServerUrl(url: String?) {
        _serverUrlConnected.value = url
    }

    
    
    private var serverUrlOwnerSessionId: Long = NO_SERVER_URL_OWNER

    
    @Synchronized
    fun claimConnectedServerUrl(sessionId: Long) {
        serverUrlOwnerSessionId = sessionId
        _serverUrlConnected.value = null
    }

    



    @Synchronized
    fun clearConnectedServerUrl(sessionId: Long) {
        if (serverUrlOwnerSessionId != sessionId) {
            L.i { "[Call] LCallEngine clearConnectedServerUrl skipped: owner=$serverUrlOwnerSessionId session=$sessionId" }
            return
        }
        serverUrlOwnerSessionId = NO_SERVER_URL_OWNER
        _serverUrlConnected.value = null
    }

    private const val NO_SERVER_URL_OWNER = -1L

    


    fun reportConnectionFailure(url: String) {
        L.w { "[Call] LCallEngine reportConnectionFailure url=$url" }
        appScope.launch(Dispatchers.IO) {
            LCallManager.refreshCallServiceUrlsAfterConnectionFailure()
        }
    }

    fun setSelectedConnectMode(type: CONNECTION_TYPE, fromUserSelection: Boolean = false) {
        _connectionType.value = type
        if (fromUserSelection) {
            hasManualConnectionTypeOverride = true
        }
    }

    fun hasManualConnectionTypeOverride(): Boolean {
        return hasManualConnectionTypeOverride
    }

    





    fun isQuicSelectable(): Boolean = !ProxyConfigProvider.isProxyForCallActiveWithoutQuic

    fun isUseQuicSignal(): Boolean {
        
        
        if (ProxyConfigProvider.isProxyForCallActiveWithoutQuic) return false
        return _connectionType.value == CONNECTION_TYPE.HTTP3_QUIC
    }

    fun isNetworkAvailable(): Boolean {
        return _isNetworkAvailable.value
    }
}
