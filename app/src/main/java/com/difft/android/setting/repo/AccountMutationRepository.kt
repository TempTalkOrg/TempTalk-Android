package com.difft.android.setting.repo

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.pane.PageOperations
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed interface AccountMutationResult {
    data object Applied : AccountMutationResult
    data object StaleAccount : AccountMutationResult
    data object Busy : AccountMutationResult
    data class Rejected(val message: String?) : AccountMutationResult
    data class AcceptedLocalCommitFailed(val cause: Exception) : AccountMutationResult
}

@Singleton
class AccountMutationRepository @Inject constructor(
    private val settings: SettingRepo,
    private val users: com.difft.android.base.storage.user.StorageBoundUserManager,
    @ChativeHttpClientModule.Chat private val client: ChativeHttpClient,
    private val logout: LogoutManager,
    @Named("application") private val scope: CoroutineScope,
) {
    private val searchLock = Mutex()
    private val logoutLock = Mutex()
    private val deleteLock = Mutex()
    private var searchGeneration = 0L

    suspend fun refresh() {
        val account = globalServices.myId
        val authorization = users.getUserData()?.baseAuth
        val generation = searchGeneration
        val profile = withContext(Dispatchers.IO) { settings.getProfile() }
        if (account != globalServices.myId || users.getUserData()?.baseAuth != authorization || profile.status != 0) return
        profile.data?.let { info ->
            users.update {
                if (this.account != account || baseAuth != authorization) return@update
                email = info.emailMasked
                phoneNumber = info.phoneMasked
                customUid = info.customUid
                if (generation == searchGeneration && !searchLock.isLocked) searchByCustomUid = info.searchByCustomUid
            }
        }
    }

    suspend fun setSearch(enabled: Boolean): AccountMutationResult {
        val account = globalServices.myId
        val authorization = users.getUserData()?.baseAuth
        if (!searchLock.tryLock()) return AccountMutationResult.Busy
        return scope.async(Dispatchers.Main.immediate) {
            try {
                PageOperations.track("account-search:$account") {
                    val response = withContext(Dispatchers.IO) { settings.setProfile(searchByCustomUid = if (enabled) 1 else 0) }
                    if (response.status != 0) {
                        L.w { "[Account] mutation_rejected op=search status=${response.status}" }
                        return@track AccountMutationResult.Rejected(response.reason)
                    }
                    if (account != globalServices.myId || users.getUserData()?.baseAuth != authorization) return@track AccountMutationResult.StaleAccount
                    searchGeneration++
                    try {
                        users.commitStrict(account) {
                            check(baseAuth == authorization) { "Session changed before settings commit" }
                            searchByCustomUid = if (enabled) 1 else 0
                        }
                        AccountMutationResult.Applied
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        L.e { "[Account] accepted_local_commit_failed op=search ${e.stackTraceToString()}" }
                        AccountMutationResult.AcceptedLocalCommitFailed(e)
                    }
                }
            } finally { searchLock.unlock() }
        }.await()
    }

    
    suspend fun logout(beforeLogout: () -> Unit): AccountMutationResult =
        endSession(deleteAccount = false, beforeLogout = beforeLogout)

    suspend fun deleteAccount(beforeLogout: () -> Unit): AccountMutationResult =
        endSession(deleteAccount = true, beforeLogout = beforeLogout)

    private suspend fun endSession(deleteAccount: Boolean, beforeLogout: () -> Unit): AccountMutationResult {
        val account = globalServices.myId
        val authorization = users.getUserData()?.baseAuth
        val lock = if (deleteAccount) deleteLock else logoutLock
        if (!lock.tryLock()) return AccountMutationResult.Busy
        return scope.async(Dispatchers.Main.immediate) {
            try {
                PageOperations.track("${if (deleteAccount) "delete-account" else "account-logout"}:$account") {
                    val response = try {
                        withContext(Dispatchers.IO) {
                            if (deleteAccount) client.httpService.fetchDeleteAccount() else client.httpService.fetchLogout()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        if (deleteAccount) throw e
                        L.w { "[Account] remote_logout_failed error=${e.javaClass.simpleName}" }
                        null
                    }
                    if (deleteAccount && response?.status != 0) {
                        L.w { "[Account] mutation_rejected op=delete_account status=${response?.status}" }
                        return@track AccountMutationResult.Rejected(response?.reason)
                    }
                    if (account != globalServices.myId || users.getUserData()?.baseAuth != authorization) return@track AccountMutationResult.StaleAccount
                    beforeLogout()
                    logout.doLogout()
                    AccountMutationResult.Applied
                }
            } finally { lock.unlock() }
        }.await()
    }
}
