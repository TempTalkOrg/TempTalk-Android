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

/**
 * [AuthTokenProvider] over `UserData.microToken`, which is the only store: every access reads it
 * through, so nothing has to be seeded at login, rehydrated after process death, or cleared in a
 * particular order at logout (issue #1231).
 *
 * The only state of its own is a [Mutex] and one mutex-confined timestamp, so concurrent callers
 * collapse onto a single refresh and a failing refresh cannot be amplified into a request storm.
 *
 * The refresh runs on the dedicated `@AuthToken` client — see
 * [ChativeHttpClientModule.provideAuthTokenClient] for why it must not share `@Chat`'s dispatcher.
 */
@Singleton
class AuthTokenManager @Inject constructor(
    @param:ChativeHttpClientModule.AuthToken
    private val authClient: ChativeHttpClient,
    private val userManager: UserManager,
) : AuthTokenProvider {

    private val refreshMutex = Mutex()

    /** Written and read only while [refreshMutex] is held. */
    private var lastFailureAtMs = 0L

    /**
     * Wall clock, indirected so the failure cooldown and the expiry margin are unit-testable
     * without sleeping.
     */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    override fun peek(): String? = userManager.getUserData()?.microToken

    override suspend fun current(): String = withContext(Dispatchers.IO) {
        if (credential().isEmpty()) return@withContext ""
        peek()?.takeIf { !isExpiring(it) } ?: refreshMutex.withLock {
            // Re-check under the lock: the winner's userManager.update {} mutated the in-memory
            // snapshot synchronously, so a waiter almost always finds a usable token here.
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
            // fetchLocked() falls back to the stored token on failure; here that token is the one
            // the server just rejected, so it must not be handed out as a replacement.
            fetchLocked().takeIf { it != rejected }.orEmpty()
        }
    }

    private fun credential(): String = userManager.getUserData()?.baseAuth.orEmpty()

    /**
     * Caller MUST hold [refreshMutex].
     *
     * The credential is re-checked after the round trip: logout can run while the refresh is in
     * flight, and a token minted for the credential that has just been cleared must not be written
     * back into the logged-out state.
     *
     * A failed refresh returns the stored token while it is still valid (see [storedIfStillValid]):
     * inside the expiry margin a transient network error must not turn into an unauthenticated
     * request. Once the stored token has actually expired the caller gets "" as before.
     */
    private suspend fun fetchLocked(): String {
        val sinceFailure = nowMs() - lastFailureAtMs
        if (sinceFailure < REFRESH_FAILURE_COOLDOWN_MS) {
            L.i { "[Auth] refresh suppressed, cooldown ${REFRESH_FAILURE_COOLDOWN_MS - sinceFailure}ms" }
            return peek().orEmpty()
        }
        return try {
            // Captured once, so the request and the post-request check speak about the same credential.
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

    /**
     * What a failed refresh hands out: the stored token while it has not actually expired (a
     * proactive refresh inside the margin failing on a network blip must not strip the request of
     * a still-valid credential), "" once it has — the caller then fails as it did before.
     */
    private fun storedIfStillValid(): String {
        val stored = peek().orEmpty()
        if (stored.isEmpty()) return ""
        val expiresAt = runCatching { JWT(stored).expiresAt!!.time }.getOrNull() ?: return ""
        return if (maxOf(ServerTimeProvider.nowMillis(), nowMs()) < expiresAt) stored else ""
    }

    /**
     * True when [token] is unusable or expires within [TOKEN_EXPIRY_MARGIN_MS]. Refresh-early
     * bias: if either clock says expiring, refresh — this guards the cold-start window where a
     * stale server-time offset could make nowMillis under-read. An unparsable token counts as
     * expiring, so a corrupt stored value is replaced instead of failing every request.
     */
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
        /**
         * Server tokens live 20 minutes (apis/authorization.md), so 120 s is a 10 % margin — the
         * same proportion iOS uses. A lifetime change is a one-constant edit here.
         */
        const val TOKEN_EXPIRY_MARGIN_MS = 120_000L

        /** After a failed refresh, suppress further attempts this long so N callers cost one request. */
        const val REFRESH_FAILURE_COOLDOWN_MS = 3_000L
    }
}
