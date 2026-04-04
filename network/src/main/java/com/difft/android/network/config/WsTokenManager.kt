package com.difft.android.network.config

import com.auth0.android.jwt.JWT
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WsTokenManager @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val chativeHttpClient: ChativeHttpClient,
    private val userManager: UserManager
) {
    private var jsonWebToken: String? = null

    private suspend fun refreshToken(): String = withContext(Dispatchers.IO) {
        try {
            val response = chativeHttpClient.httpService.fetchAuthToken(
                SecureSharedPrefsUtil.getBasicAuth()
            )
            val newToken = response.data?.token ?: ""
            if (newToken.isNotEmpty()) {
                L.i { "[WsTokenManager] refresh token success" }
                setToken(newToken)
                userManager.update {
                    this.microToken = newToken
                }
            }
            newToken
        } catch (e: Exception) {
            L.e(e) { "[WsTokenManager] refresh token failed:" }
            ""
        }
    }


    fun getToken(): String? {
        return jsonWebToken
    }

    private fun setToken(newToken: String) {
        jsonWebToken = newToken
    }

    fun clearToken() {
        jsonWebToken = null
    }

    private val tokenRefreshMutex = Mutex()
    private var isRefreshing = false

    suspend fun refreshTokenIfNeeded() = withContext(Dispatchers.IO) {
        val auth = SecureSharedPrefsUtil.getBasicAuth()
        if (auth.isEmpty()) return@withContext
        tokenRefreshMutex.withLock {
            if (!isRefreshing) {
                try {
                    if (isTokenExpired()) {
                        refreshToken()
                    }
                } catch (e: Exception) {
                    L.i(e) { "[WsTokenManager] refresh tokens error" }
                } finally {
                    isRefreshing = false
                }
            }
        }
    }

    /**
     * Force refresh token regardless of local expiry check.
     * Use this when server returns 401 (token rejected by server).
     *
     * @return New token if refresh succeeded, empty string otherwise
     */
    suspend fun forceRefreshToken(): String = withContext(Dispatchers.IO) {
        val auth = SecureSharedPrefsUtil.getBasicAuth()
        if (auth.isEmpty()) return@withContext ""
        tokenRefreshMutex.withLock {
            try {
                refreshToken()
            } catch (e: Exception) {
                L.e(e) { "[WsTokenManager] force refresh token error:" }
                ""
            }
        }
    }

    private fun isTokenExpired(): Boolean {
        val currentToken = SecureSharedPrefsUtil.getToken()
        if (currentToken.isEmpty()) return true
        val result = kotlin.runCatching {
            val jwt = JWT(currentToken)
            val expireDate = jwt.expiresAt
            val currDate = Date()
            currDate.after(expireDate)
        }.onFailure {
            L.w(it) { "[WsTokenManager] error:" }
        }.getOrDefault(false)
        L.i { "[WsTokenManager] check token expired:$result" }
        return result
    }

}