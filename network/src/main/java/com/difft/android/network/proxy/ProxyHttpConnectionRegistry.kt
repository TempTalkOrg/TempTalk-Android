package com.difft.android.network.proxy

import com.difft.android.base.log.lumberjack.L
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry of every proxy-aware [OkHttpClient] (each [com.difft.android.network.ChativeHttpClient]
 * owns one with its own connection pool) so a proxy enable/disable can drop their
 * pooled keep-alive connections at once.
 *
 * Why this exists: proxy routing is decided per-connection by
 * [ProxyTunnelSocketFactory] / [ProxyTunnelDns] reading [ProxyConfigProvider.current]
 * at connect time, so NEW connections honor the latest state without rebuilding the
 * client. But OkHttp keeps idle connections alive (default keep-alive), and
 * [okhttp3.internal.connection.RealConnection.isEligible] reuses a pooled connection
 * whenever the request host + the non-host [okhttp3.Address] (dns / socketFactory /
 * sslSocketFactory instances) match — regardless of which IP the socket is actually
 * connected to. So after the proxy is turned OFF, a request to the same host happily
 * reuses the still-open tunnel socket and keeps flowing through the proxy until the
 * connection dies on its own (idle timeout). The symmetric leak exists when turning
 * the proxy ON (a pre-existing direct connection is reused, leaking the real IP).
 *
 * The IM WebSocket already handles this via
 * [com.difft.android.base.utils.IConnectionRefresher.reconnectAfterProxyChange];
 * this registry extends the same eviction to the HTTP API clients, which each carry
 * their OWN connection pool (they are not pooled together).
 *
 * Holds [WeakReference]s so a client that is ever GC'd does not leak here (the
 * production clients are @Singleton, so in practice they live for the process).
 */
@Singleton
class ProxyHttpConnectionRegistry @Inject constructor() {

    private val clients = mutableListOf<WeakReference<OkHttpClient>>()
    private val lock = Any()

    /** Registers a proxy-aware client so its connection pool is evicted on proxy change. */
    fun register(client: OkHttpClient) {
        synchronized(lock) {
            // Drop any already-collected refs while we hold the lock (cheap, list is tiny).
            clients.removeAll { it.get() == null }
            if (clients.none { it.get() === client }) {
                clients.add(WeakReference(client))
            }
        }
    }

    /**
     * Evicts the connection pool of every registered client so the next request
     * builds a fresh connection that honors the current proxy state. Idle pooled
     * connections are closed immediately; a connection that is mid-request at this
     * instant finishes its current exchange (short-lived HTTP calls) before the
     * pool stops reusing it.
     */
    fun evictAll() {
        val snapshot = synchronized(lock) {
            clients.removeAll { it.get() == null }
            clients.mapNotNull { it.get() }
        }
        snapshot.forEach { client ->
            runCatching { client.connectionPool.evictAll() }
                .onFailure { L.w { "[Proxy] evict http pool failed: ${it.message}" } }
        }
        L.i { "[Proxy] evicted ${snapshot.size} http client pool(s) after proxy change" }
    }
}
