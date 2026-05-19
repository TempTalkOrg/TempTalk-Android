package com.difft.android.call

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.appScope
import com.difft.android.call.BuildConfig.DEBUG
import com.difft.android.call.data.CONNECTION_TYPE
import com.difft.android.call.data.ServerNode
import com.difft.android.call.receiver.NetworkConnectionListener
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
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                if (L.enabled && Timber.treeCount() > 0) {
                    Timber.log(priority.toAndroidLogPriority(), t, L.replaceUid(message))
                }
            }
        }
        LiveKit.enableWebRTCLogging = false

        registerNetworkConnectionListener(context, scope)

        LiveKit.init(context)
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

    /**
     * Clears the user's manual node selection. Used to roll back a rejected manual switch
     * so the UI can return to showing the currently-connected node.
     */
    fun resetSelectedServerNode() {
        _serverNodeSelected.value = null
    }

    fun setConnectedServerUrl(url: String?) {
        _serverUrlConnected.value = url
    }

    /**
     * Reports a connection failure (currently used for logging and triggering config refresh).
     */
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

    fun isUseQuicSignal(): Boolean {
        return _connectionType.value == CONNECTION_TYPE.HTTP3_QUIC
    }

    fun isNetworkAvailable(): Boolean {
        return _isNetworkAvailable.value
    }
}
