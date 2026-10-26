package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves the host (and service path) of every request on this client's own origin at request
 * time, so a host marked unavailable or a live config change applies without rebuilding the
 * `@Singleton` [ChativeHttpClient] whose Retrofit `baseUrl` is fixed at construction (#1396).
 * It generalises the proxy-only host pin of #945/#959/#960.
 *
 * - **Host:** a request whose host equals the origin host is rewritten to
 *   [UrlManager.currentHostFor], which is the proxy-forced host while the proxy is active and the
 *   best ranked host otherwise. This covers root-relative APIs (`/v1/messages`) as well as
 *   service-prefixed ones.
 * - **Path:** only a path that starts with the origin path prefix (for example `/chat/`) has that
 *   prefix replaced by the live service path. Other paths keep theirs.
 * - **Gate (#948):** a request to any other host passes through unchanged, so its host and trust
 *   anchor are preserved. An absolute URL on another chat-pool host keeps its URL but is tagged for
 *   failover; one on a host outside the pool (CDN, OSS, avatars) is not tagged.
 *
 * The routed request carries a [RoutedHost] tag, which [HostFailoverInterceptor] uses to decide
 * whether a call belongs to the chat pool.
 *
 * Application interceptor, installed first on every pool client: the rewritten URL is what
 * [HeaderInterceptor], failover and logging see, and what the connection dials.
 */
class ChatPoolRoutingInterceptor(
    origin: HttpUrl,
    internal val service: ChatService,
    /**
     * Resolves the [UrlManager] on first request, through a Hilt [EntryPoint] by default, so the
     * client can be built before the graph is queried; overridable so the routing logic is
     * unit-testable without the Hilt graph.
     */
    private val urlManagerProvider: () -> UrlManager = {
        EntryPointAccessors.fromApplication<EntryPoint>(application).urlManager
    },
) : Interceptor {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val urlManager: UrlManager
    }

    private val originHost: String = origin.host

    /** `/chat/` for a `https://host/chat/` origin; null for a bare-host origin. */
    private val originPathPrefix: String? = origin.encodedPath.takeIf { it.length > 1 }

    /**
     * Last "origin -> target" host mapping logged. Routing runs on every request, so a line per
     * request would flood the rolling log; one line per distinct mapping shows the switch and
     * nothing more. [AtomicReference.getAndSet] keeps concurrent dispatcher threads from
     * double-logging.
     */
    private val lastLoggedRoute = AtomicReference<String?>(null)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != originHost) return chain.proceed(tagIfPoolHost(request))

        val routedUrl = runCatching { route(request.url) }
            .onFailure { e ->
                // A silent miss leaves the request on the construction-time host, which is the
                // stale-host problem (and, under the proxy, the IP leak) this interceptor exists
                // to prevent, so it stays observable.
                L.w { "[HostRoute] resolve failed service=$service error=${e.javaClass.simpleName}" }
            }
            .getOrNull() ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .url(routedUrl)
                .tag(RoutedHost::class.java, RoutedHost(routedUrl.host, service))
                .build()
        )
    }

    /**
     * An absolute URL already built on a chat-pool host (the GIF endpoints use [UrlManager.gifs])
     * keeps its URL but is tagged, so a host failure there still fails over. Any other host (CDN,
     * OSS, avatars) passes through untagged (#948).
     */
    private fun tagIfPoolHost(request: Request): Request {
        val host = request.url.host
        val inPool = runCatching { urlManagerProvider().isChatPoolHost(host) }.getOrDefault(false)
        if (!inPool) return request
        return request.newBuilder().tag(RoutedHost::class.java, RoutedHost(host, service)).build()
    }

    private fun route(url: HttpUrl): HttpUrl {
        val urlManager = urlManagerProvider()
        val builder = url.newBuilder().host(urlManager.currentHostFor(service))
        val prefix = originPathPrefix
        if (prefix != null && url.encodedPath.startsWith(prefix)) {
            val livePath = urlManager.servicePathFor(service).trimEnd('/')
            builder.encodedPath(livePath + "/" + url.encodedPath.substring(prefix.length))
        }
        val routed = builder.build()
        val mapping = "${url.host} -> ${routed.host}"
        if (routed.host != url.host && lastLoggedRoute.getAndSet(mapping) != mapping) {
            L.i { "[HostRoute] origin=${url.host} target=${routed.host} service=$service" }
        }
        return routed
    }
}
