package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.application
import com.difft.android.network.auth.AuthStyle
import com.difft.android.network.auth.AuthStyleResolver
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.config.UserAgentManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * The one place a request's `Authorization` header is decided (issue #1231).
 *
 * Adds `User-Agent` / `Accept-Language`, resolves the endpoint's [AuthStyle] and applies it, and
 * owns the rejection policy: an HTTP 401 on a JWT route triggers exactly one refresh-and-retry,
 * and anything still rejected becomes `NetworkException(401)` — the contract callers already have.
 *
 * Sits outside `HttpClientInterceptor` so the response it inspects is the final one, after host
 * failover, and so its retry re-enters failover.
 *
 * @param authProvider the persisted Basic credential source. Null on clients that never send Basic.
 * @param tokenProvider the account JWT source, injected lazily to keep the Dagger graph acyclic
 * (the provider's own refresh client is a `ChativeHttpClient`).
 * @param handleRejection true on clients that also install `HttpClientInterceptor`, i.e. clients
 * whose callers expect `NetworkException` rather than a raw status. False for the Signal API client,
 * whose repositories map raw 401/403 themselves.
 * @param failFastOnMissingDeclaration crash instead of falling back when an endpoint carries no
 * declaration. On in debug builds so the omission cannot reach a release build unnoticed;
 * injectable so the release fallback itself is testable.
 */
class HeaderInterceptor(
    private val authProvider: ChativeHttpClient.AuthProvider?,
    private val tokenProvider: dagger.Lazy<AuthTokenProvider>? = null,
    private val handleRejection: Boolean = true,
    private val failFastOnMissingDeclaration: Boolean = BuildConfig.DEBUG,
) : Interceptor {

    // OkHttp's intercept is a synchronous SPI and the JWT branch must await a possible refresh.
    // Runs on an OkHttp dispatcher worker, never Main, and is bounded by the auth client's
    // call timeout.
    @Suppress("BanRunBlockingOutsideTests")
    override fun intercept(chain: Interceptor.Chain): Response {
        val base = chain.request()
            .newBuilder()
            .addHeader("User-Agent", UserAgentManager.getUserAgent())
            .addHeader("Accept-Language", LanguageUtils.getLanguage(application).language)
            .build()

        val style = resolveStyle(base)
        val sent = runBlocking { applyAuth(base, style) }
        val response = chain.proceed(sent.request)

        if (response.code != HTTP_UNAUTHORIZED || !handleRejection) return response
        retryAfterRejection(chain, response, style, sent.token)?.let { return it }
        throw NetworkException(HTTP_UNAUTHORIZED, application.getString(R.string.chat_net_error))
    }

    private fun resolveStyle(request: Request): AuthStyle {
        AuthStyleResolver.styleFor(request)?.let { return it }
        // Fail-safe, not fail-secure: an endpoint reaching here without an @Auth declaration keeps
        // the pre-declaration behaviour rather than losing its credential. The AuthStyleMissing
        // lint rule makes this unreachable for every Retrofit endpoint in this repository.
        val name = AuthStyleResolver.describe(request)
        L.e { "[Auth] endpoint declares no @Auth, falling back endpoint=$name" }
        check(!failFastOnMissingDeclaration) { "endpoint declares no @Auth: $name" }
        return undeclaredStyle(request)
    }

    /**
     * The one surviving pre-declaration rule, reached only by an undeclared endpoint: a header
     * already present wins; absent means implicit Basic.
     */
    private fun undeclaredStyle(request: Request): AuthStyle {
        val existing = request.headers[HEADER_KEY_AUTHORIZATION] ?: return AuthStyle.BASIC
        return if (existing.startsWith("basic", ignoreCase = true)) AuthStyle.EXPLICIT else AuthStyle.JWT
    }

    /** The request as it goes on the wire, plus the credential it carries (for the retry decision). */
    private data class Sent(val request: Request, val token: String)

    private suspend fun applyAuth(request: Request, style: AuthStyle): Sent = when (style) {
        AuthStyle.EXPLICIT -> Sent(request, request.headers[HEADER_KEY_AUTHORIZATION].orEmpty())
        // Only Authorization — never content-type: some routes declare their own Content-Type.
        AuthStyle.NONE -> Sent(request.newBuilder().removeHeader(HEADER_KEY_AUTHORIZATION).build(), "")
        AuthStyle.BASIC -> {
            // Empty before storage warm-up and after logout; send nothing rather than "Basic ".
            val basic = authProvider?.provideAuth().orEmpty()
            Sent(request.withAuthorization(basic), basic)
        }

        AuthStyle.JWT -> {
            val jwt = tokenProvider?.get()?.current().orEmpty()
            Sent(request.withAuthorization(jwt), jwt)
        }
    }

    private fun Request.withAuthorization(value: String): Request =
        if (value.isEmpty()) newBuilder().removeHeader(HEADER_KEY_AUTHORIZATION).build()
        else newBuilder().removeHeader(HEADER_KEY_AUTHORIZATION)
            .addHeader(HEADER_KEY_AUTHORIZATION, value).build()

    /**
     * One retry, only for JWT, only with a repeatable body, only against the host that answered —
     * so a 401 seen after failover is retried where it happened. Always closes [rejected].
     */
    private fun retryAfterRejection(
        chain: Interceptor.Chain,
        rejected: Response,
        style: AuthStyle,
        sentToken: String,
    ): Response? {
        val original = rejected.request
        if (style != AuthStyle.JWT) {
            rejected.close()
            return null
        }
        if (original.body?.isOneShot() == true) {
            L.w { "[Auth] 401 on a one-shot body, not retrying url=${original.url.encodedPath}" }
            rejected.close()
            return null
        }
        @Suppress("BanRunBlockingOutsideTests") // same OkHttp sync-API rationale as intercept
        val fresh = runBlocking { tokenProvider?.get()?.refreshRejected(sentToken).orEmpty() }
        rejected.close()
        if (fresh.isEmpty() || fresh == sentToken) return null
        L.w { "[Auth] 401 refreshed, retrying once path=${original.url.encodedPath}" }
        val retried = chain.proceed(original.withAuthorization(fresh))
        return if (retried.code == HTTP_UNAUTHORIZED) {
            retried.close()
            null
        } else {
            retried
        }
    }

    companion object {
        const val HEADER_KEY_AUTHORIZATION = "Authorization"
        private const val HTTP_UNAUTHORIZED = 401
    }
}
