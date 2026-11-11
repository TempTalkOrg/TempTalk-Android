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






















class ChatPoolRoutingInterceptor(
    origin: HttpUrl,
    internal val service: ChatService,
    




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

    
    private val originPathPrefix: String? = origin.encodedPath.takeIf { it.length > 1 }

    





    private val lastLoggedRoute = AtomicReference<String?>(null)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != originHost) return chain.proceed(tagIfPoolHost(request))

        val routedUrl = runCatching { route(request.url) }
            .onFailure { e ->
                
                
                
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
