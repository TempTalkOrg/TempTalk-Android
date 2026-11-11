package com.difft.android.network.auth





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


internal fun <T> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }
