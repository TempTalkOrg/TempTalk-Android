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





















class HeaderInterceptor(
    private val authProvider: ChativeHttpClient.AuthProvider?,
    private val tokenProvider: dagger.Lazy<AuthTokenProvider>? = null,
    private val handleRejection: Boolean = true,
    private val failFastOnMissingDeclaration: Boolean = BuildConfig.DEBUG,
) : Interceptor {

    
    
    
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
        
        
        val name = AuthStyleResolver.describe(request)
        L.e { "[Auth] endpoint declares no @Auth, falling back endpoint=$name" }
        check(!failFastOnMissingDeclaration) { "endpoint declares no @Auth: $name" }
        return undeclaredStyle(request)
    }

    
    private fun undeclaredStyle(request: Request): AuthStyle {
        val existing = request.headers[HEADER_KEY_AUTHORIZATION] ?: return AuthStyle.BASIC
        return if (existing.startsWith("basic", ignoreCase = true)) AuthStyle.EXPLICIT else AuthStyle.JWT
    }

    
    private data class Sent(val request: Request, val token: String)

    private suspend fun applyAuth(request: Request, style: AuthStyle): Sent = when (style) {
        AuthStyle.EXPLICIT -> Sent(request, request.headers[HEADER_KEY_AUTHORIZATION].orEmpty())
        
        AuthStyle.NONE -> Sent(request.newBuilder().removeHeader(HEADER_KEY_AUTHORIZATION).build(), "")
        AuthStyle.BASIC -> {
            
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
        @Suppress("BanRunBlockingOutsideTests") 
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
