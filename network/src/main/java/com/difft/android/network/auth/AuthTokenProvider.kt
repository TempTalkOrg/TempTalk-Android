package com.difft.android.network.auth

import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.BaseResponse
import com.difft.android.network.NetworkException


object AuthStatus {
    
    const val INVALID_TOKEN = 5
}






class InvalidTokenException(tag: String) :
    NetworkException(AuthStatus.INVALID_TOKEN, "invalid token: $tag")






interface AuthTokenProvider {

    







    suspend fun current(): String

    






    fun peek(): String?

    






    suspend fun refreshRejected(rejected: String): String
}














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
