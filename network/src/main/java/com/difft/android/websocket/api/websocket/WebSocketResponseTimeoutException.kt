package com.difft.android.websocket.api.websocket

import java.io.IOException






class WebSocketResponseTimeoutException(timeoutMs: Long) :
    IOException("WebSocket response timed out after $timeoutMs ms")
