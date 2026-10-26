package com.difft.android.network.auth

import okhttp3.Request
import retrofit2.Invocation

/**
 * Reads the [Auth] declaration of the endpoint a request came from. Pure — no I/O, no state.
 *
 * Retrofit tags every request it builds with an `Invocation` naming the service interface and the
 * invoked method, and `Request.newBuilder()` copies tags, so the declaration survives the
 * host rewrite in `ChatPoolRoutingInterceptor` and the per-host rebuild in
 * `HostFailoverInterceptor`. A request built outside Retrofit carries no
 * tag and therefore no declaration (issue #1231).
 */
object AuthStyleResolver {

    /** The declared style for [request], or null when neither the method nor its interface declares one. */
    fun styleFor(request: Request): AuthStyle? {
        val invocation = request.tag(Invocation::class.java) ?: return null
        return invocation.method().getAnnotation(Auth::class.java)?.style
            ?: invocation.service().getAnnotation(Auth::class.java)?.style
    }

    /** `Service.method` for logging. A code symbol, never a URL — query strings can carry user input. */
    fun describe(request: Request): String {
        val invocation = request.tag(Invocation::class.java) ?: return "unknown(no-invocation-tag)"
        return "${invocation.service().simpleName}.${invocation.method().name}"
    }
}
