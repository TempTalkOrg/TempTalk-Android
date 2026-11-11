package com.difft.android.chat.gif.favorite

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import javax.inject.Inject
import javax.inject.Singleton


data class FavKeyEntry(val keyId: String, val favKey: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as FavKeyEntry
        return keyId == other.keyId && favKey.contentEquals(other.favKey)
    }

    override fun hashCode(): Int = keyId.hashCode() * 31 + favKey.contentHashCode()
}




@Singleton
class FavoriteKeyRepo @Inject constructor(
    private val userManager: UserManager
) {

    
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

    internal fun matchesSession(accountId: String, authorization: String?): Boolean =
        userManager.getUserData()?.let { it.account == accountId && it.baseAuth == authorization } == true

    
    internal fun saveForSession(accountId: String, authorization: String?, keyId: String, favKey: ByteArray): Boolean {
        require(keyId.isNotEmpty())
        require(favKey.size == FavoriteCrypto.FAV_KEY_SIZE)
        var applied = false
        userManager.update(commit = true) {
            if (account == accountId && baseAuth == authorization) {
                favKeyId = keyId
                this.favKey = Base64.encodeToString(favKey, Base64.NO_WRAP)
                applied = true
            }
        }
        return applied
    }

    
    fun hasKey(): Boolean = getFavKey() != null

    




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
