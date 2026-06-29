package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins this client's OWN API host to the embedded "best host" while the proxy is
 * active (see [UrlManager.proxyForcedHostOrNull]).
 *
 * Why this is needed: each [ChativeHttpClient] is a Hilt `@Singleton` whose
 * Retrofit `baseUrl` host is fixed at construction time — typically app startup,
 * when the proxy is still OFF. A runtime proxy toggle reconnects the WebSocket
 * and flips DNS/socket routing, but it does NOT rebuild the HTTP clients, so
 * their `baseUrl` host stays whatever it was at startup. Without this interceptor
 * HTTP requests would keep hitting that startup host instead of the embedded,
 * `ssl_preread`-whitelist-aligned domain (and a non-whitelisted host would route
 * DIRECT, leaking the real IP).
 *
 * Scope — [originHost] gate: ONLY requests whose host equals this client's own
 * baseUrl host are rewritten (i.e. relative-path chat-API calls). Absolute-URL
 * requests to OTHER hosts — e.g. the global-config files on public-CA CDNs
 * (cloudfront / OSS / S3) — are left untouched, so their host and TLS trust
 * anchor are preserved. Rewriting those to the self-cert chat host would break
 * the handshake ("Trust anchor for certification path not found"). Those hosts
 * are also off the embedded whitelist, so they correctly bypass the tunnel.
 *
 * This is an application interceptor (runs before routing/DNS), so the rewritten
 * host is what the connection actually dials. No-op when the proxy is off, or
 * when the request is already on the target host.
 *
 * Installed on ALL clients — including the SignalApi client, which omits
 * [HttpClientInterceptor] — so host pinning is uniform.
 */
class ProxyHostInterceptor(
    private val originHost: String?,
    /**
     * Resolves the embedded host to pin to while the proxy is active, or `null`
     * when the proxy is off. Defaults to [UrlManager.proxyForcedHostOrNull] via a
     * Hilt [EntryPoint]; overridable so the rewrite logic is unit-testable without
     * the Hilt graph.
     */
    private val forcedHostProvider: () -> String? = {
        runCatching {
            EntryPointAccessors.fromApplication<EntryPoint>(application).urlManager.proxyForcedHostOrNull()
        }.onFailure { e ->
            // Security-sensitive: a silent miss here leaves HTTP on the startup
            // host (the IP leak this interceptor prevents). Keep it observable.
            L.w { "[Proxy] ProxyHostInterceptor failed to resolve forced host, pinning skipped: ${e.message}" }
        }.getOrNull()
    },
) : Interceptor {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val urlManager: UrlManager
    }

    /**
     * Last "origin -> target" pin logged, used to dedupe. The rewrite fires on
     * EVERY request while the proxy is active; logging each one floods the rolling
     * file log in a busy session. We only need to see pinning take effect or change
     * target, so we log at [L.i] once per distinct mapping and stay silent on repeats.
     *
     * [AtomicReference.getAndSet] makes the check-and-set atomic, so concurrent
     * OkHttp dispatcher threads can't both emit a duplicate line for the same pin.
     */
    private val lastLoggedPin = AtomicReference<String?>(null)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Only this client's own API host is a pin candidate; leave absolute-URL
        // requests to other hosts (CDN config/avatars) on their original host.
        if (originHost.isNullOrBlank() || request.url.host != originHost) {
            return chain.proceed(request)
        }
        val target = forcedHostProvider()
        if (target.isNullOrBlank() || request.url.host == target) {
            return chain.proceed(request)
        }
        val pin = "${request.url.host} -> $target"
        if (lastLoggedPin.getAndSet(pin) != pin) {
            L.i { "[Proxy] pin request host $pin" }
        }
        val newUrl = request.url.newBuilder().host(target).build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
