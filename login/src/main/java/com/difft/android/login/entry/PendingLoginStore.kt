package com.difft.android.login.entry

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.SecureConfigStore
import com.difft.android.login.viewmodel.LoginViewModel.PendingSignIn
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one verified sign-in this device holds across an interruption it cannot survive in memory: the
 * wipe of another account's data, and the restart that follows a promoted import. It spares the user
 * a second verification and keeps the account's other device signed in meanwhile — the transfer
 * token inside is redeemed only when the sign-in completes. Lives in the encrypted `secure_config`
 * store, the one store a full clear leaves alone (the DataStore clear and the `FileUtil` sweep both
 * skip it and its keyset). Read once — [consume] deletes before returning — and expires at [TTL_MS].
 */
@Singleton
class PendingLoginStore @Inject constructor(
    private val secureConfig: SecureConfigStore,
) {
    /** Test seam; production reads the wall clock. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val gson = Gson()

    /** On-disk shape, kept apart from the ViewModel's Parcelable so either can change alone. */
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

    /** Returns the held sign-in once and forgets it; null when there is none, or it is too old to trust. */
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
        /** How long a held sign-in stays usable. */
        const val TTL_MS = 30L * 60 * 1000
    }
}
