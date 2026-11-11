@file:Suppress("DEPRECATION") 
                              

package com.difft.android.base.storage.user

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.storage.di.SecureUserDataStore
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.storage.schema.UserAuthDataMapper
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton









@Singleton
class StorageBoundUserManagerImpl @Inject constructor(
    @param:SecureUserDataStore
    private val secureUserStore: DataStore<UserAuthData>,
    @param:AppStateDataStore
    private val appStateStore: DataStore<Preferences>,
    @param:ApplicationContext
    private val context: Context,
    private val gson: Gson,
    private val credentialSignal: CredentialChangeSignal = CredentialChangeSignal(),
) : StorageBoundUserManager {

    private var sessionEpoch = 0L

    
    @Volatile
    private var inMemorySnapshot: UserData? = null
        set(value) {
            if (field?.account != value?.account || field?.baseAuth != value?.baseAuth) sessionEpoch++
            field = value
            credentialSignal.publish(value?.baseAuth, loaded = value != null)
        }

    





    private val memLock = Any()

    




    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val writes = OrderedUserWrites(secureUserStore, appStateStore, writeDispatcher)

    override suspend fun commitStrict(expectedAccount: String, change: UserData.() -> Unit) {
        val completion = synchronized(memLock) {
            val current = inMemorySnapshot
            check(current?.account == expectedAccount) { "Account changed before local commit" }
            val snapshot = requireNotNull(current).copy().apply(change)
            require(snapshot.account == expectedAccount && snapshot.baseAuth == current.baseAuth) {
                "Strict completion cannot change the session"
            }
            inMemorySnapshot = snapshot
            val epoch = sessionEpoch
            writes.enqueueStrict(snapshot) { synchronized(memLock) { sessionEpoch == epoch } }
        }
        completion.await()
    }

    

    




    override fun getUserData(): UserData? = inMemorySnapshot

    



    override fun setUserData(userData: UserData, commit: Boolean) {
        
        
        
        val job: Job = synchronized(memLock) {
            val previous = inMemorySnapshot
            inMemorySnapshot = userData
            writes.enqueue {
                writeAll(previous, userData)
            }
        }
        if (commit) {
            
            
            
            @Suppress("BanRunBlockingOutsideTests")
            runBlocking { job.join() }
        }
    }

    






    override fun update(commit: Boolean, config: UserData.() -> Unit) {
        
        
        val job: Job = synchronized(memLock) {
            val current = inMemorySnapshot ?: UserData()
            val copied = current.copy().apply(config)
            if (copied == current) return  
            inMemorySnapshot = copied
            writes.enqueue {
                writeAll(current, copied)
            }
        }
        if (commit) {
            
            @Suppress("BanRunBlockingOutsideTests")
            runBlocking { job.join() }
        }
    }

    

    

    override suspend fun updateAuth(block: UserAuthData.() -> UserAuthData) {
        
        
        
        
        
        val job: Job = synchronized(memLock) {
            val previous = inMemorySnapshot ?: UserData()
            val authView = UserAuthDataMapper.fromUserData(previous)
            val updated = authView.block()
            if (updated == authView) return  
            inMemorySnapshot = UserAuthDataMapper.toUserData(updated, previous)
            writes.enqueue {
                runCatching {
                    
                    
                    
                    
                    secureUserStore.updateData { current ->
                        updated.copy(migrationV1Completed = current.migrationV1Completed)
                    }
                }.onFailure {
                    L.e {
                        "[Storage][UserManager] updateAuth secureUserStore write failed: " +
                            it.stackTraceToString()
                    }
                }
            }
        }
        job.join()
    }

    override suspend fun <T> updateAppState(key: Preferences.Key<T>, value: T) {
        
        val job: Job = synchronized(memLock) {
            val previous = inMemorySnapshot ?: UserData()
            val updated = UserDataFieldRouter.applyAppStateChangeToSnapshot(previous, key, value)
            if (updated != previous) {
                inMemorySnapshot = updated
            }
            writes.enqueue {
                runCatching {
                    appStateStore.edit { it[key] = value }
                }.onFailure {
                    L.e {
                        "[Storage][UserManager] updateAppState ${key.name} write failed: " +
                            it.stackTraceToString()
                    }
                }
            }
        }
        job.join()
    }

    override suspend fun warmUp() {
        val (auth, needsWriteBack) = readAuthDataOrRecover()
        val appState = readAppStateOrRecover()
        val combined = UserDataFieldRouter.compose(auth, appState, fallback = null)

        
        
        val writeBackJob: Job? = synchronized(memLock) {
            
            
            
            if (inMemorySnapshot != null) return@synchronized null
            inMemorySnapshot = combined
            if (!needsWriteBack) return@synchronized null
            writes.enqueue {
                runCatching { secureUserStore.updateData { auth } }
                    .onFailure {
                        L.e {
                            "[Storage][UserManager][R3] write-back failed: " +
                                it.stackTraceToString()
                        }
                    }
            }
        }

        writeBackJob?.join()

        L.i {
            "[Storage][UserManager] warmUp complete " +
                "hasAuth=${!combined.baseAuth.isNullOrEmpty()} " +
                "appStateKeys=${appState.asMap().size}"
        }
    }

    

    

    override suspend fun clearAuthOnly() {
        
        
        val job: Job = synchronized(memLock) {
            val previous = inMemorySnapshot ?: UserData()
            inMemorySnapshot = previous.copy(
                
                baseAuth = null,
                microToken = null,
                passcode = null,
                passcodeAttempts = 0,
                passcodeAutoMisses = 0,
                pattern = null,
                patternAttempts = 0,
                signalingKey = null,
                contactRequestStatus = null,
                aciIdentityPublicKey = null,
                aciIdentityPrivateKey = null,
                aciIdentityOldPublicKey = null,
                aciIdentityOldPrivateKey = null,
                aciIdentityKeyGenTime = 0L,
                
                
                
                proxyShareLink = null,
                proxyEnabled = false,
                proxyProtectCallIp = false,
                
                
            )
            writes.enqueue {
                runCatching {
                    secureUserStore.updateData { current ->
                        
                        
                        
                        current.copy(
                            baseAuth = "",
                            microToken = "",
                            passcode = "",
                            pattern = "",
                            signalingKey = "",
                            contactRequestStatus = "",
                            aciIdentityPublicKey = "",
                            aciIdentityPrivateKey = "",
                            aciIdentityOldPublicKey = "",
                            aciIdentityOldPrivateKey = "",
                            aciIdentityKeyGenTime = 0L,
                            
                            
                            proxyShareLink = "",
                            proxyEnabled = false,
                            proxyProtectCallIp = false,
                        )
                    }
                }.onFailure {
                    L.e { "[Logout][Island1A] secureUser clear failed: ${it.stackTraceToString()}" }
                }
            }
        }

        
        job.join()

        L.i { "[Logout][Island1A] clearAuthOnly complete" }
    }

    override suspend fun clearAll() {
        
        
        val job: Job = synchronized(memLock) {
            inMemorySnapshot = UserData()
            
            
            
            
            writes.enqueue {
                coroutineScope {
                    launch {
                        runCatching {
                            
                            
                            secureUserStore.updateData { UserAuthData(migrationV1Completed = true) }
                        }.onFailure {
                            L.e { "[Logout][Island1B] secureUser clear failed: ${it.stackTraceToString()}" }
                        }
                    }
                    launch {
                        runCatching {
                            appStateStore.edit { mut ->
                                
                                
                                val version = mut[AppStateKeys.MIGRATION_VERSION]
                                mut.clear()
                                if (version != null) mut[AppStateKeys.MIGRATION_VERSION] = version
                            }
                        }.onFailure {
                            L.e { "[Logout][Island1B] appState clear failed: ${it.stackTraceToString()}" }
                        }
                    }
                }
            }
        }

        
        job.join()

        L.i { "[Logout][Island1B] clearAll complete" }
    }

    

    

    




    private suspend fun writeAll(previous: UserData?, updated: UserData) {
        val diff = UserDataFieldRouter.diff(previous, updated)

        diff.newAuth?.let { newAuth ->
            
            
            runCatching {
                secureUserStore.updateData { current ->
                    newAuth.copy(migrationV1Completed = current.migrationV1Completed)
                }
            }.onFailure {
                L.e {
                    "[Storage][UserManager] secureUserStore write failed: " +
                        it.stackTraceToString()
                }
            }
        }

        if (diff.appStateChanges.isNotEmpty()) {
            runCatching {
                appStateStore.edit { mut ->
                    UserDataFieldRouter.applyAppStateChanges(mut, diff.appStateChanges)
                }
            }.onFailure {
                L.e {
                    "[Storage][UserManager] appStateStore write failed (${diff.appStateChanges.size} keys): " +
                        it.stackTraceToString()
                }
            }
        }
    }

    




    private fun readLegacyUserDataBlob(): UserData? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val key = "com.difft.chative.base.user.SimpleUserManager\$Companion.SHARED_PREFERENCES_KEY_USERDATA"
            val json = prefs.getString(key, null) ?: return null
            gson.fromJson(json, UserData::class.java)
        } catch (e: Throwable) {
            L.e { "[StorageBoundUserManagerImpl][R3] legacy SP read failed: ${e.stackTraceToString()}" }
            null
        }
    }

    




    private suspend fun readAuthDataOrRecover(): Pair<UserAuthData, Boolean> {
        return try {
            secureUserStore.data.first() to false
        } catch (e: CorruptionException) {
            L.e {
                "[Storage][UserManager][R3] secure_user CorruptionException: " +
                    e.stackTraceToString()
            }
            
            
            runCatching {
                secureUserStore.updateData { UserAuthData(migrationV1Completed = true) }
            }

            val legacyAuth = runCatching {
                readLegacyUserDataBlob()?.let { UserAuthDataMapper.fromLegacyComplete(it) }
            }.getOrNull() ?: UserAuthData(migrationV1Completed = true)

            val recovered = legacyAuth.account.isNotEmpty()
            if (recovered) {
                L.i { "[Storage][UserManager][R3] secure_user recovered from legacy SP" }
            } else {
                L.w { "[Storage][UserManager][R3] secure_user no legacy data — user will re-login" }
            }
            legacyAuth to recovered
        } catch (e: Throwable) {
            L.e {
                "[Storage][UserManager] secure_user read failed: " +
                    e.stackTraceToString()
            }
            UserAuthData.EMPTY to false
        }
    }

    
    private suspend fun readAppStateOrRecover(): Preferences {
        return try {
            appStateStore.data.first()
        } catch (e: CorruptionException) {
            L.e {
                "[Storage][UserManager][R3] app_state CorruptionException: " +
                    e.stackTraceToString()
            }
            runCatching { appStateStore.edit { it.clear() } }
            runCatching { appStateStore.data.first() }.getOrDefault(emptyPreferences())
        } catch (e: Throwable) {
            L.e {
                "[Storage][UserManager] app_state read failed: " +
                    e.stackTraceToString()
            }
            emptyPreferences()
        }
    }

    
}
