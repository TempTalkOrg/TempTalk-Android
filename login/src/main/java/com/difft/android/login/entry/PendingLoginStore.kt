package com.difft.android.login.entry

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.SecureConfigStore
import com.difft.android.login.viewmodel.LoginViewModel.PendingSignIn
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton









@Singleton
class PendingLoginStore @Inject constructor(
    private val secureConfig: SecureConfigStore,
) {
    
    internal var clock: () -> Long = System::currentTimeMillis

    private val gson = Gson()

    
    internal data class Record(
        val verificationCode: String,
        val account: String,
        val email: String?,
        val phone: String?,
        val renewToken: String?,
        val savedAt: Long,
    )

    suspend fun save(pending: PendingSignIn) {
        val record = Record(pending.verificationCode, pending.account, pending.email, pending.phone, pending.renewToken, clock())
        secureConfig.savePendingLogin(gson.toJson(record))
        L.i { "[login] held a verified sign-in across the interruption tokenPresent=${pending.renewToken != null}" }
    }

    
    suspend fun consume(): PendingSignIn? {
        val raw = secureConfig.readPendingLogin()
        if (raw.isEmpty()) return null
        secureConfig.clearPendingLogin()
        val record = runCatching { gson.fromJson(raw, Record::class.java) }.getOrNull()
        if (record == null) {
            L.w { "[login] held sign-in unreadable, dropped" }
            return null
        }
        val age = clock() - record.savedAt
        if (age < 0 || age > TTL_MS) {
            L.w { "[login] held sign-in expired ageMs=$age, dropped" }
            return null
        }
        return PendingSignIn(record.verificationCode, record.account, record.email, record.phone, record.renewToken)
    }

    suspend fun clear() = secureConfig.clearPendingLogin()

    companion object {
        
        const val TTL_MS = 30L * 60 * 1000
    }
}
