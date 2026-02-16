package com.difft.android.websocket.internal.websocket

import android.content.Context
import android.os.Bundle
import com.difft.android.base.BuildConfig
import com.difft.android.base.log.lumberjack.L
import com.difft.android.websocket.api.util.Tls12SocketFactory
import com.difft.android.websocket.internal.util.Util
import com.difft.android.network.ca.OfficialSSLSocketFactoryCreator
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.protobuf.InvalidProtocolBufferException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.SingleSubject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.ConnectionSpec
import okhttp3.ConnectionSpec.Companion.RESTRICTED_TLS
import okhttp3.Dns.Companion.SYSTEM
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import okio.ByteString
import com.difft.android.websocket.api.websocket.HealthMonitor
import com.difft.android.websocket.api.websocket.KeepAliveSender
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketMessage
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketResponseMessage
import org.whispersystems.signalservice.internal.websocket.webSocketMessage
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Named

class WebSocketConnection @AssistedInject constructor(
    @Assisted("auth")
    private val auth: () -> String,
    @Assisted("urlGetter")
    private val webSocketUrlGetter: () -> String,
    @Assisted
    private val keepAliveSender: KeepAliveSender,
    @param:Named("UserAgent")
    private val userAgent: String,
    private val healthMonitor: HealthMonitor,
    @param:ApplicationContext
    private val context: Context,
) : WebSocketListener() {

    private val incomingRequests = LinkedBlockingQueue<WebSocketRequestMessage>()

    /**
     * Shared OkHttpClient instance to avoid thread pool leaks.
     * Creating a new OkHttpClient for each connection causes thread pool accumulation
     * because each client has its own ConnectionPool and Dispatcher threads.
     * Reusing a single client prevents OutOfMemoryError from pthread_create failures.
     */
    private val okHttpClient: OkHttpClient by lazy {
        val customConnectionSpec: ConnectionSpec = ConnectionSpec.Builder(RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
            .build()

        val clientBuilder: OkHttpClient.Builder = OkHttpClient.Builder()
            .connectionSpecs(Util.immutableList(customConnectionSpec))
            .dns(SYSTEM)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor()
            logging.setLevel(HttpLoggingInterceptor.Level.BODY)
            clientBuilder.addInterceptor(logging)
        }

        val sslCreator = OfficialSSLSocketFactoryCreator(context)
        clientBuilder.sslSocketFactory(
            Tls12SocketFactory(sslCreator.socketFactory),
            sslCreator.trustManager
        )

        clientBuilder.build()
    }

    private val outgoingRequests: MutableMap<Long, OutgoingRequest> = ConcurrentHashMap()

    val name: String = "[ws][chat:" + System.identityHashCode(this) + "]"

    private val _webSocketConnectionState = MutableSharedFlow<WebSocketConnectionState>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val webSocketConnectionState = _webSocketConnectionState.asSharedFlow()
    private var MutableSharedFlow<WebSocketConnectionState>.value: WebSocketConnectionState
        set(value) {
            tryEmit(value)
        }
        get() = this.asSharedFlow().value

    @Volatile
    private var currentWebsocket: WebSocket? = null

    @Volatile
    private var currentWebsocketListener: ControllableWebSocketListener? = null

    @Volatile
    private var startConnectTime = 0L

    init {
        _webSocketConnectionState.tryEmit(WebSocketConnectionState.DISCONNECTED)
    }

    /**
     * Start monitoring and auto-reconnect logic
     */
    fun startMonitoring() {
        healthMonitor.monitor(this)
    }

    /**
     * Stop monitoring and auto-reconnect logic
     */
    fun stopMonitoring() {
        healthMonitor.stopMonitoring(this)
    }

    /**
     * All connect actions should only be triggered by healthMonitor, you should not invoke this function directly
     */
    @Synchronized
    internal fun connect() {
        startConnectTime = System.currentTimeMillis()
        L.i { "$name connect()" }

        if (currentWebsocket == null) {
            val filledUri = webSocketUrlGetter()
            L.i { "$name connect with URL $filledUri" }

            val requestBuilder: Request.Builder = Request.Builder().url(filledUri)

            requestBuilder.addHeader("User-Agent", userAgent)

            requestBuilder.addHeader(
                "Authorization",
                auth()
            )
            _webSocketConnectionState.value = WebSocketConnectionState.CONNECTING

            currentWebsocketListener?.invalidate()
            currentWebsocketListener = null
            val websocketListener = ControllableWebSocketListener(this).also {
                currentWebsocketListener = it
            }
            currentWebsocket = okHttpClient.newWebSocket(requestBuilder.build(), websocketListener)

            L.i { "$name Really start connecting in code" }
        }
    }

    @Synchronized
    fun disconnectWhenConnected() {
        L.i { "$name disconnect(), current webSocketConnectionState: ${webSocketConnectionState.value}" }
        if (webSocketConnectionState.value == WebSocketConnectionState.CONNECTED) {
            currentWebsocket?.let {
                L.i { "$name Client not null when disconnect" }
                it.cancel() //use [cancel()] function instead of [close()] can terminate the connection immediately
                currentWebsocket = null
                currentWebsocketListener?.invalidate()
                currentWebsocketListener = null
                _webSocketConnectionState.value = WebSocketConnectionState.DISCONNECTED
            }
        }
    }

    @Synchronized
    fun cancelConnection() {
        L.i { "$name cancelConnection(), current webSocketConnectionState: ${webSocketConnectionState.value}" }
        currentWebsocket?.let {
            L.i { "$name Client not null when cancelConnection" }
            it.cancel()
            currentWebsocket = null
            currentWebsocketListener?.invalidate()
            currentWebsocketListener = null
            _webSocketConnectionState.value = WebSocketConnectionState.DISCONNECTED
        }
    }

    fun readRequest(): WebSocketRequestMessage {
        return incomingRequests.take()
    }

    @Throws(IOException::class)
    fun sendRequestAwaitResponse(request: WebSocketRequestMessage): Single<WebsocketResponse> {
        L.d { "$name sendMessageRequest() requestId=${request.requestId}, path=${request.path}, verb=${request.verb}" }
        if (webSocketConnectionState.value != WebSocketConnectionState.CONNECTED) {
            throw IOException("$name No connected connection!")
        }

        val message = webSocketMessage {
            type = WebSocketMessage.Type.REQUEST
            this.request = request
        }

        val single = SingleSubject.create<WebsocketResponse>()

        outgoingRequests[request.requestId] = OutgoingRequest(single)

        if (currentWebsocket?.send(ByteString.of(*message.toByteArray())) != true) {
            throw IOException("$name Write failed!")
        }

        return single.subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .timeout(10, TimeUnit.SECONDS, Schedulers.io())
    }

    @Throws(IOException::class)
    fun sendRequest(request: WebSocketRequestMessage) {
        L.d { "$name sendMessageRequest() requestId=${request.requestId}, path=${request.path}, verb=${request.verb}" }
        if (currentWebsocket == null) {
            throw IOException("$name No connection!")
        }
        val message = webSocketMessage {
            type = WebSocketMessage.Type.REQUEST
            this.request = request
        }
        if (currentWebsocket?.send(ByteString.of(*message.toByteArray())) != true) {
            throw IOException("$name send request on web socket failed!")
        }
    }

    @Throws(IOException::class)
    fun sendRequestAwaitResponse(json: String): Single<WebsocketResponse> {
        L.d { "$name sendMessageRequest() jsonLength=${json.length}" }
        if (webSocketConnectionState.value != WebSocketConnectionState.CONNECTED) {
            throw IOException("$name No connected connection!")
        }
        val single = SingleSubject.create<WebsocketResponse>()
        if (currentWebsocket?.send(json) != true) {
            L.e { "$name sendRequest string failed..." }
            throw IOException("$name Write failed!")
        }
        if ("ping" == json) {
            L.e { "$name sendRequest string failed..." }
        }

        return single.subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .timeout(10, TimeUnit.SECONDS, Schedulers.io())
    }

    @Throws(IOException::class)
    fun sendRequest(json: String) {
        L.d { "$name sendMessageRequest() jsonLength=${json.length}" }
        if (currentWebsocket == null) {
            throw IOException("$name No connection!")
        }
        if (currentWebsocket?.send(json) != true) {
            L.e { "$name sendRequest string failed..." }
            throw IOException("$name Write failed!")
        }
        if ("ping" == json) {
            L.i { "$name Sending keep alive..." }
        }
    }

    @Throws(IOException::class)
    fun sendResponse(response: WebSocketResponseMessage) {
        L.d { "$name sendResponse() requestId=${response.requestId}, status=${response.status}" }
        if (currentWebsocket == null) {
            throw IOException("$name Connection closed!")
        }

        val message = webSocketMessage {
            type = WebSocketMessage.Type.RESPONSE
            this.response = response
        }

        if (currentWebsocket?.send(ByteString.of(*message.toByteArray())) != true) {
            throw IOException("$name Write failed!")
        }
    }

    @Synchronized
    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (currentWebsocket == null) {
            // onOpen() is executed immediately after cancelConnection, which will cause the state to be wrong
            L.i { "$name onOpen() currentWebsocket is null, ignore this call back" }
            return
        }
        val connectCostTime = System.currentTimeMillis() - startConnectTime
        Bundle().apply {
            putLong(WEB_SOCKET_CONNECT_TIME, connectCostTime)
        }.let {
            FirebaseAnalytics.getInstance(context).logEvent(WEB_SOCKET_CONNECT_TIME, it)
        }
        L.i { "$name onOpen() connected, time cost $connectCostTime" }
        _webSocketConnectionState.value = WebSocketConnectionState.CONNECTED
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        healthMonitor.onKeepAliveResponse()
        try {
            val message = WebSocketMessage.parseFrom(bytes.toByteArray())
            L.d { "$name onMessage() type=${message.type}, requestId=${if (message.hasRequest()) message.request.requestId else message.response.requestId}" }

            if (message.type.number == WebSocketMessage.Type.REQUEST_VALUE) {
                incomingRequests.add(message.request)
            } else if (message.type.number == WebSocketMessage.Type.RESPONSE_VALUE) {
                outgoingRequests.remove(message.response.requestId)?.onSuccess(
                    WebsocketResponse(
                        message.response.status,
                        String(message.response.body.toByteArray()),
                        message.response.headersList,
                    )
                )
            }
        } catch (e: InvalidProtocolBufferException) {
            L.w(e) { "[WebSocketConnection] onMessage parse error" }
        }
    }

    @Synchronized
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        L.i { "$name onClose()" }
        if (webSocketConnectionState.value is WebSocketConnectionState.DISCONNECTED
            || webSocketConnectionState.value is WebSocketConnectionState.FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.UNKNOWN_HOST_FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.AUTHENTICATION_FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.INACTIVE_FAILED
        ) {
            L.w { "Now the webSocketConnectionState is ${webSocketConnectionState.value}, ignore onClosed() callback" }
            return
        }
        cleanupAfterShutdown()
        _webSocketConnectionState.value = WebSocketConnectionState.DISCONNECTED
    }

    @Synchronized
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        L.w(t) { "$name onFailure()" }
        if (webSocketConnectionState.value is WebSocketConnectionState.DISCONNECTED
            || webSocketConnectionState.value is WebSocketConnectionState.FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.UNKNOWN_HOST_FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.AUTHENTICATION_FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.INACTIVE_FAILED
        ) {
            L.w { "Now the webSocketConnectionState is ${webSocketConnectionState.value}, ignore onFailure() callback" }
            return
        }
        if (t is IOException && t.message == "Canceled") {
            L.i { "$name onFailure() Canceled, ignore this call back as all cancel is handled manually" }
            return
        }
        cleanupAfterShutdown()

        if (response != null && (response.code == 401 || response.code == 403)) {
            _webSocketConnectionState.value = WebSocketConnectionState.AUTHENTICATION_FAILED
        } else if (response != null && (response.code == 451)) {
            L.w { "$name onFailure() inactive device" }
            _webSocketConnectionState.value = WebSocketConnectionState.INACTIVE_FAILED
        } else if (t is UnknownHostException) {
            L.i { "$name onFailure() UnknownHostException, need switch host to do websocket connection" }
            _webSocketConnectionState.value = WebSocketConnectionState.UNKNOWN_HOST_FAILED
        } else {
            _webSocketConnectionState.value = WebSocketConnectionState.FAILED
        }
    }

    @Synchronized
    private fun cleanupAfterShutdown() {
        // Handle outgoing requests
        // Create a copy of the keys to avoid ConcurrentModificationException
        val requestIds = outgoingRequests.keys.toList()
        for (requestId in requestIds) {
            try {
                outgoingRequests.remove(requestId)?.onError(IOException("$name Closed unexpectedly"))
            } catch (e: Exception) {
                L.e(e) { "[WebSocketConnection] $name Error while cleaning up request $requestId" }
            }
        }

        currentWebsocket = null // Allow garbage collection
        currentWebsocketListener?.invalidate()
        currentWebsocketListener = null
        L.i { "$name WebSocket connection cleaned up and set to null." }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        L.d { "$name onMessage() $text" }
        healthMonitor.onKeepAliveResponse()
    }

    @Synchronized
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        L.i { "$name onClosing(), code: $code, reason: $reason" }
        if (webSocketConnectionState.value is WebSocketConnectionState.DISCONNECTED
            || webSocketConnectionState.value is WebSocketConnectionState.FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.UNKNOWN_HOST_FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.AUTHENTICATION_FAILED
            || webSocketConnectionState.value is WebSocketConnectionState.INACTIVE_FAILED
        ) {
            L.w { "Now the webSocketConnectionState is ${webSocketConnectionState.value}, ignore onClosing() callback" }
            return
        }
        cleanupAfterShutdown()
        _webSocketConnectionState.value = WebSocketConnectionState.DISCONNECTED
    }

    fun sendKeepAlive() {
        keepAliveSender.sendKeepAliveFrom(this)
    }

    private class OutgoingRequest(private val responseSingle: SingleSubject<WebsocketResponse>) {
        fun onSuccess(response: WebsocketResponse) {
            responseSingle.onSuccess(response)
        }

        fun onError(throwable: Throwable?) {
            throwable?.let { L.e(it) { "[WebSocketConnection] OutgoingRequest error" } }
        }
    }

    companion object {
        const val KEEP_ALIVE_TIMEOUT_SECONDS: Int = 30
        const val WEB_SOCKET_CONNECT_TIME = "web_socket_connect_time"
    }
}

val SharedFlow<WebSocketConnectionState>.value: WebSocketConnectionState
    get() = replayCache.lastOrNull() ?: WebSocketConnectionState.DISCONNECTED
