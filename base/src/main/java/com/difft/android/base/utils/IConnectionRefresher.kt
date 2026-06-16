package com.difft.android.base.utils

/**
 * Cross-module bridge so `:network` can ask `:chat` to drop the IM WebSocket
 * after a proxy state change, so the next connect picks up the new
 * `ProxyConfigProvider.current` (host / port / pin / TURN secret).
 *
 * Without this, OkHttp keeps the existing TCP+TLS socket alive even after
 * `ProxyConfigProvider.save()` / `.clear()` mutate the in-memory cache — so
 * a user who toggles "Use proxy" ON in settings continues to leak IM
 * signaling through direct DNS until the connection naturally dies.
 *
 * Contract:
 *  - Fire-and-forget. Caller MUST NOT block on this returning.
 *  - Idempotent. Safe to call multiple times in quick succession.
 *  - No-op when no WS is currently connected (e.g. proxy toggled before login).
 *  - MUST NOT throw across the boundary; implementation catches internally.
 *  - Call-media (WebRTC) is out of scope; only IM signaling WS is reset.
 */
interface IConnectionRefresher {
    fun reconnectAfterProxyChange()
}
