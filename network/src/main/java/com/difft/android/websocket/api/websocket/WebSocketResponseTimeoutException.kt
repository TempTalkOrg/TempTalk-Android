package com.difft.android.websocket.api.websocket

import java.io.IOException

/**
 * Thrown when a WebSocket request gets no response in time. An [IOException] so senders fall
 * back to HTTP and send jobs retry, instead of a TimeoutCancellationException that callers would
 * mistake for coroutine cancellation.
 */
class WebSocketResponseTimeoutException(timeoutMs: Long) :
    IOException("WebSocket response timed out after $timeoutMs ms")
