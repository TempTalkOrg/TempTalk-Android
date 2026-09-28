package com.difft.android.network.auth

import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.BaseResponse
import com.difft.android.network.NetworkException

/** Business status codes in `BaseResponse.status` that describe the credential that was sent. */
object AuthStatus {
    /** 5 — Invalid token. The only code this layer acts on. */
    const val INVALID_TOKEN = 5
}

/**
 * Thrown when a token-authenticated request is still rejected after one successful refresh, i.e.
 * the server rejected a token it had just issued (revocation, key rotation, clock/credential fault).
 * [tag] names the call site so field logs can attribute the failure. Issue #1235.
 */
class InvalidTokenException(tag: String) :
    NetworkException(AuthStatus.INVALID_TOKEN, "invalid token: $tag")

/**
 * The single source of the account JWT. Backed by `UserData.microToken` — read through, never
 * cached separately — so a cold start, a login and a logout need no seeding, rehydration or
 * ordering rules (issue #1231).
 */
interface AuthTokenProvider {

    /**
     * A JWT for the current credential, refreshing when it is missing, expired, or within the
     * expiry margin. Concurrent callers collapse onto one refresh. Returns "" when there is no
     * credential (logged out, or before storage warm-up) — callers must treat "" as "send no
     * Authorization", never as an error.
     *
     * Suspends; may perform one network round trip bounded by the auth client's call timeout.
     */
    suspend fun current(): String

    /**
     * The stored JWT with no expiry test and no refresh. Synchronous, non-suspending.
     *
     * Sanctioned callers only: the cold-start identity gate (`LocalTokenVerifier`, issue #758)
     * and this provider's own expiry test. Everything else must use [current].
     */
    fun peek(): String?

    /**
     * The server rejected [rejected]. Returns a token known not to be it: if another caller has
     * already refreshed, that newer token; otherwise the result of exactly one refresh. Keying on
     * the rejected value is what collapses a rejection burst into a single refresh request.
     *
     * Returns "" when there is no credential or the refresh failed.
     */
    suspend fun refreshRejected(rejected: String): String
}

/**
 * Runs [block] with a valid token and retries it **once** if the server rejects that token —
 * either as business status [AuthStatus.INVALID_TOKEN] inside a 200 envelope, or as a bare HTTP
 * 401 surfaced as `NetworkException(401)`. Both shapes occur (issue #1235).
 *
 * This is the body-token counterpart of the header-token retry in `HeaderInterceptor`: an OkHttp
 * interceptor cannot rewrite a serialised request body, so routes that carry the token in the body
 * (fileshare) retry here instead. [tag] is a short call-site name, for logging only.
 *
 * Any failure that is not a token rejection propagates from the first attempt untouched.
 *
 * @throws InvalidTokenException if the retry is rejected too.
 */
suspend fun <T> AuthTokenProvider.withInvalidTokenRetry(
    tag: String,
    block: suspend (token: String) -> BaseResponse<T>,
): BaseResponse<T> {
    val token = current()
    val first = runCatching { block(token) }
    if (!first.isTokenRejection()) return first.getOrThrow()

    L.w { "[Auth] invalid token tag=$tag, refreshing and retrying once" }
    val refreshed = refreshRejected(token)
    if (refreshed.isEmpty()) throw InvalidTokenException(tag)

    val second = runCatching { block(refreshed) }
    if (second.isTokenRejection()) {
        L.w { "[Auth] invalid token persists after refresh tag=$tag" }
        throw InvalidTokenException(tag)
    }
    return second.getOrThrow()
}

private fun <T> Result<BaseResponse<T>>.isTokenRejection(): Boolean = fold(
    onSuccess = { it.status == AuthStatus.INVALID_TOKEN },
    onFailure = { it is NetworkException && it.errorCode == HTTP_UNAUTHORIZED },
)

private const val HTTP_UNAUTHORIZED = 401
