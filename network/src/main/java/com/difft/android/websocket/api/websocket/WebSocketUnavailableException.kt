package com.difft.android.websocket.api.websocket

import java.io.IOException

/**
 * Thrown when the WebSocket is not available for use by runtime policy. Currently, the
 * WebSocket is only available when the app is in the foreground and requested via IncomingMessageObserver.
 * Or, when using WebSocket Strategy.
 */
class WebSocketUnavailableException : IOException("WebSocket not currently available.")
