package com.difft.android.network.auth

import com.auth0.android.jwt.JWT
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton












@Singleton
class AuthTokenManager @Inject constructor(
    @param:ChativeHttpClientModule.AuthToken
    private val authClient: ChativeHttpClient,
    private val userManager: UserManager,
) : AuthTokenProvider {

    private val refreshMutex = Mutex()

    
    private var lastFailureAtMs = 0L

    



    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    override fun peek(): String? = userManager.getUserData()?.microToken

    override suspend fun current(): String = withContext(Dispatchers.IO) {
        if (credential().isEmpty()) return@withContext ""
        peek()?.takeIf { !isExpiring(it) } ?: refreshMutex.withLock {
            
            
            peek()?.takeIf { !isExpiring(it) } ?: fetchLocked()
        }
    }

    override suspend fun refreshRejected(rejected: String): String = withContext(Dispatchers.IO) {
        if (credential().isEmpty()) return@withContext ""
        refreshMutex.withLock {
            val stored = peek().orEmpty()
            if (rejected.isNotEmpty() && stored != rejected) {
                L.i { "[Auth] rejected token already replaced by a concurrent refresh" }
                return@withLock stored
            }
            
            
            fetchLocked().takeIf { it != rejected }.orEmpty()
        }
    }

    private fun credential(): String = userManager.getUserData()?.baseAuth.orEmpty()

    










    private suspend fun fetchLocked(): String {
        val sinceFailure = nowMs() - lastFailureAtMs
        if (sinceFailure < REFRESH_FAILURE_COOLDOWN_MS) {
            L.i { "[Auth] refresh suppressed, cooldown ${REFRESH_FAILURE_COOLDOWN_MS - sinceFailure}ms" }
            return peek().orEmpty()
        }
        return try {
            
            val basic = credential()
            val token = authClient.httpService.fetchAuthToken(basic).data?.token.orEmpty()
            if (token.isEmpty()) {
                lastFailureAtMs = nowMs()
                L.w { "[Auth] refresh returned an empty token" }
                return storedIfStillValid()
            }
            if (credential().isEmpty()) {
                L.i { "[Auth] refresh completed after logout, discarding token" }
                return ""
            }
            userManager.update { this.microToken = token }
            lastFailureAtMs = 0L
            L.i { "[Auth] refresh ok expiresInMs=${expiresInMs(token)}" }
            token
        } catch (e: Exception) {
            lastFailureAtMs = nowMs()
            L.w { "[Auth] refresh failed: ${e.stackTraceToString()}" }
            storedIfStillValid()
        }
    }

    




    private fun storedIfStillValid(): String {
        val stored = peek().orEmpty()
        if (stored.isEmpty()) return ""
        val expiresAt = runCatching { JWT(stored).expiresAt!!.time }.getOrNull() ?: return ""
        return if (maxOf(ServerTimeProvider.nowMillis(), nowMs()) < expiresAt) stored else ""
    }

    





    private fun isExpiring(token: String): Boolean {
        if (token.isEmpty()) return true
        return runCatching {
            val expiresAt = JWT(token).expiresAt!!.time
            maxOf(ServerTimeProvider.nowMillis(), nowMs()) + TOKEN_EXPIRY_MARGIN_MS > expiresAt
        }.onFailure { L.w { "[Auth] unparsable token, treating as expiring: ${it.stackTraceToString()}" } }
            .getOrDefault(true)
    }

    private fun expiresInMs(token: String): Long = runCatching {
        JWT(token).expiresAt!!.time - System.currentTimeMillis()
    }.getOrDefault(-1L)

    companion object {
        



        const val TOKEN_EXPIRY_MARGIN_MS = 120_000L

        
        const val REFRESH_FAILURE_COOLDOWN_MS = 3_000L
    }
}
