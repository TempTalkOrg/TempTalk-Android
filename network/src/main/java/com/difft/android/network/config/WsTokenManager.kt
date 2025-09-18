package com.difft.android.network.config

import com.auth0.android.jwt.JWT
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GetTokenRequestBody
import io.reactivex.rxjava3.core.Completable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WsTokenManager @Inject constructor(
    @ChativeHttpClientModule.Chat
    private val chativeHttpClient: ChativeHttpClient,
    private val userManager: UserManager
) {
    private var jsonWebToken: String? = null

    private var job: Job? = null

    @Inject
    fun initialize() {
        startTokenRefreshTask()
    }

    private fun startTokenRefreshTask() {
        job = CoroutineScope(Dispatchers.IO).launch {
            refreshTokenIfNeeded()
            while (isActive) {
                delay(5 * 60 * 1000)
                refreshTokenIfNeeded()
            }
        }
    }

    fun requestNewToken(appID: String? = null, scope: String? = null) = chativeHttpClient
        .httpService
        .fetchAuthToken(SecureSharedPrefsUtil.getBasicAuth(), GetTokenRequestBody(appID, scope))
        .map { it.data?.token ?: "" }


    private suspend fun refreshToken(): String = withContext(Dispatchers.IO) {
        requestNewToken().map { newToken ->
            if (newToken.isNotEmpty()) {
                L.i { "[WsTokenManager] refresh token success" }
                setToken(newToken)
                userManager.update {
                    this.microToken = newToken
                }
            }
            newToken
        }.blockingGet()
    }


    fun getToken(): String? {
        return jsonWebToken
    }

    private fun setToken(newToken: String) {
        jsonWebToken = newToken
    }

    fun clearToken() {
        jsonWebToken = null
        job?.cancel()
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
                    L.i { "[WsTokenManager] refresh tokens error" + e.stackTraceToString() }
                } finally {
                    isRefreshing = false
                }
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
            it.printStackTrace()
        }.getOrDefault(false)
        L.i { "[WsTokenManager] check token expired:$result" }
        return result
    }

}