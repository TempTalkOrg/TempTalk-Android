package com.difft.android.chat.gif.favorite

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import javax.inject.Inject
import javax.inject.Singleton

/** A stored favKey snapshot: (keyId fingerprint, raw 32-byte key). */
data class FavKeyEntry(val keyId: String, val favKey: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as FavKeyEntry
        return keyId == other.keyId && favKey.contentEquals(other.favKey)
    }

    override fun hashCode(): Int = keyId.hashCode() * 31 + favKey.contentHashCode()
}

/**
 * Single point of access for the account favKey. Backed by the encrypted secure_user store
 * ([UserManager] → UserData.favKey/favKeyId), NOT WCDB: favKey is account-level secret material
 * (like baseAuth/identity keys), and keeping it out of WCDB means a DB corruption-recovery RESET
 * no longer loses it — the server-held blob is re-pullable and re-decryptable with the surviving
 * key.
 *
 * v2 holds the unwrapped favKey as a stable local cache: it is written on first-create / unwrap /
 * reset, and read for encrypt/decrypt + PUT. There is no version gate — favKey is a stable DEK,
 * re-wrapped (not regenerated) on identity rotation, so no distribution / version comparison.
 * favKey is stored Base64.NO_WRAP; secure_user is Tink-AEAD encrypted.
 */
@Singleton
class FavoriteKeyRepo @Inject constructor(
    private val userManager: UserManager
) {

    /** Read the stored favKey snapshot, or null if none. `getUserData()` is an in-memory snapshot (no IO). */
    fun getFavKey(): FavKeyEntry? {
        val data = userManager.getUserData() ?: return null
        val keyId = data.favKeyId
        val encoded = data.favKey
        if (keyId.isNullOrEmpty() || encoded.isNullOrEmpty()) return null
        val raw = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            L.e { "[FavoriteKeyRepo] failed to decode favKey: ${e.message}" }
            return null
        }
        return FavKeyEntry(keyId, raw)
    }

    /** True if a usable favKey is cached locally. */
    fun hasKey(): Boolean = getFavKey() != null

    /**
     * Cache the unwrapped favKey locally (first-create / unwrap / reset). `@Synchronized`
     * serializes the copy-then-set funnel across in-process callers (this is @Singleton, so the
     * monitor is a single process-wide lock).
     */
    @Synchronized
    fun save(keyId: String, favKey: ByteArray) {
        require(keyId.isNotEmpty()) { "keyId must not be empty" }
        require(favKey.size == FavoriteCrypto.FAV_KEY_SIZE) { "favKey must be ${FavoriteCrypto.FAV_KEY_SIZE} bytes" }
        userManager.update(commit = true) {
            this.favKeyId = keyId
            this.favKey = Base64.encodeToString(favKey, Base64.NO_WRAP)
        }
        L.i { "[FavoriteKeyRepo] cached favKey keyId present" }
    }
}
