package com.difft.android.call

import android.annotation.SuppressLint
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
            /**
             * LiveKit SDK forwards its log lines through this callback. We deliberately
             * call `Timber.log` directly here instead of `L.log` for two reasons:
             *
             *  - The LiveKit-internal call site lives a few stack frames above this
             *    method. L's async channel captures the Throwable inside `L.log`, by
             *    which point the LiveKit frame is unreachable. Only the synchronous
             *    Timber path keeps the LiveKit frame visible to BaseTree's synthetic-
             *    Throwable fallback (calibrated via `CALL_STACK_INDEX_LIVEKIT`).
             *  - UID masking is applied via the explicit `L.replaceUid` call, so
             *    bypassing L's own redaction layer is intentional, not an oversight.
             *
             * History: introduced for F-Droid open-source build (#3726a03a) and
             * stack-index-calibrated in #18869c50. Do not "fix" by routing through L
             * without re-validating LiveKit log prefixes on a real device.
             */
            @SuppressLint("TimberDirectCall")
            override fun log(priority: LoggingLevel, t: Throwable?, message: String) {
                if (L.enabled && Timber.treeCount > 0) {
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

    /**
     * Whether HTTP/3 QUIC may be selected as the signaling transport. When the proxy is
     * active but advertises no QUIC relay (share-code without `q`), QUIC is forced off
     * (see [isUseQuicSignal]); selecting it would silently do nothing, so the UI must
     * refuse the toggle instead of leaving the switch stuck ON.
     */
    fun isQuicSelectable(): Boolean = !ProxyConfigProvider.isProxyForCallActiveWithoutQuic

    fun isUseQuicSignal(): Boolean {
        // Self-hosted proxy + QUIC: only allowed when the operator runs a MASQUE-lite
        // QUIC relay (share-code `q`), which tunnels QUIC signaling over udp/443 (see
        // design §9.6). Without it the proxy is a TCP-only TLS tunnel that QUIC/UDP
        // cannot traverse, so we force WSS-over-domain to avoid a dead UDP path.
        // Single atomic read of the routing state — combining the active and quic
        // flags as two separate loads could straddle a config change. Gated on the
        // CALL plane: only force WSS when the proxy actually routes calls
        // ("Protect IP address in calls" ON) but advertises no QUIC relay.
        if (ProxyConfigProvider.isProxyForCallActiveWithoutQuic) return false
        return _connectionType.value == CONNECTION_TYPE.HTTP3_QUIC
    }

    fun isNetworkAvailable(): Boolean {
        return _isNetworkAvailable.value
    }
}
