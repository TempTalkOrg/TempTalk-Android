package com.difft.android.network.auth

/**
 * Hand-written [AuthTokenProvider] so call counts are plain fields — the shape
 * `ProxyConfigProviderTunnelHostsTest`'s `NoOpRefresher` uses.
 */
internal class FakeAuthTokenProvider(
    private var token: String = "",
    private val refreshedTo: String = "",
) : AuthTokenProvider {

    var currentCalls = 0
        private set
    var refreshCalls = 0
        private set
    var lastRejected: String? = null
        private set

    override suspend fun current(): String {
        currentCalls++
        return token
    }

    override fun peek(): String = token

    override suspend fun refreshRejected(rejected: String): String {
        refreshCalls++
        lastRejected = rejected
        token = refreshedTo
        return refreshedTo
    }
}

/** Wraps [value] as a `dagger.Lazy`, which is what `HeaderInterceptor` takes. */
internal fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }
