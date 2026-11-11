package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toModel
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toRecord
import com.difft.android.network.responses.FavoriteAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton




@Singleton
class FavoriteKeyLifecycle @Inject constructor(
    private val syncRepo: FavoriteSyncRepository,
    private val keyRepo: FavoriteKeyRepo,
    private val encryptionDataManager: EncryptionDataManager,
    
    
    private val writeRepo: dagger.Lazy<FavoriteWriteRepository>,
) {
    



    suspend fun ensureFavKey(): Boolean {
        if (keyRepo.hasKey()) return false

        val data = syncRepo.getFavorites()
        if (data != null) syncRepo.applyServerMeta(data)
        val wrapped = data?.wrappedFavKey

        if (!wrapped.isNullOrEmpty()) {
            
            val favKey = withContext(Dispatchers.IO) {
                val kek = deriveCurrentKek() ?: return@withContext null
                FavoriteCrypto.unwrapFavKey(kek, wrapped)
            }
            if (favKey != null) {
                keyRepo.save(FavoriteCrypto.keyId(favKey), favKey)
                L.i { "[FavoriteKey] favKey unwrapped from server" }
            } else {
                
                L.w { "[FavoriteKey] wrappedFavKey present but current KEK cannot unwrap (identity changed)" }
            }
            return false
        }

        
        firstCreateKey()
        return true
    }

    
    suspend fun firstCreateKey() = withContext(Dispatchers.IO) {
        val favKey = FavoriteCrypto.generateFavKey()
        keyRepo.save(FavoriteCrypto.keyId(favKey), favKey)
    }

    
    fun deriveCurrentKek(): ByteArray? = try {
        val priv = encryptionDataManager.getAciIdentityKey().privateKey.serialize()
        FavoriteCrypto.deriveKek(priv)
    } catch (e: Exception) {
        L.w { "[FavoriteKey] cannot derive KEK: ${e.message}" }
        null
    }

    
    suspend fun currentWrappedFavKey(): String? = withContext(Dispatchers.IO) {
        val key = keyRepo.getFavKey() ?: return@withContext null
        val kek = deriveCurrentKek() ?: return@withContext null
        FavoriteCrypto.wrapFavKey(kek, key.favKey)
    }

    






    suspend fun wrappedFavKeyForPut(firstCreate: Boolean = false): String? =
        if (firstCreate || syncRepo.serverKeyId.isNullOrEmpty()) currentWrappedFavKey() else null

    






    suspend fun resetFavorites() {
        firstCreateKey() 
        
        
        
        val seed = syncRepo.allCached() 
            .reversed()                 
            .mapIndexed { index, row -> FavoriteRecord(row.toRecord().attachment, index.toLong()) }
        try {
            writeRepo.get().putWholeList(seed, FavoriteAction.RESET, wrappedFavKey = currentWrappedFavKey())
            seed.forEach { syncRepo.upsert(FavoriteRecord(it.attachment, it.addedListVersion).toModel()) }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteKey] resetFavorites CAS exhausted" }
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteKey] resetFavorites permanently rejected: ${e.message}" }
        }
        L.i { "[FavoriteKey] reset favorites seed=${seed.size}" }
    }

    









    suspend fun rewrapOnMasterKeyRotation(oldPriv: ByteArray?, newPriv: ByteArray) =
        rewrap(oldPriv, newPriv, null, null)

    
    suspend fun rewrapOnMasterKeyRotation(
        oldPriv: ByteArray?, newPriv: ByteArray, expectedAccount: String, expectedAuthorization: String?,
    ) = rewrap(oldPriv, newPriv, expectedAccount, expectedAuthorization)

    private suspend fun rewrap(oldPriv: ByteArray?, newPriv: ByteArray, account: String?, authorization: String?) {
        fun current(): Boolean {
            if (account == null || keyRepo.matchesSession(account, authorization)) return true
            L.w { "[FavoriteKey] rewrap_aborted reason=session_changed" }
            return false
        }
        if (!current()) return
        val data = syncRepo.getFavorites()
        if (!current()) return
        if (data != null) syncRepo.applyServerMeta(data)
        val wrapped = data?.wrappedFavKey
        if (wrapped.isNullOrEmpty()) {
            L.i { "[FavoriteKey] rewrap_skipped reason=no_wrapped_key" }
            return
        }
        val rewrapped = withContext(Dispatchers.IO) {
            if (!current()) return@withContext null
            val favKey = keyRepo.getFavKey()?.favKey
                ?: oldPriv?.let { FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(it), wrapped) }
            if (favKey == null) {
                L.w { "[FavoriteKey] rewrap_aborted reason=no_fav_key" }
                return@withContext null
            }
            if (account == null) keyRepo.save(FavoriteCrypto.keyId(favKey), favKey)
            else if (!keyRepo.saveForSession(account, authorization, FavoriteCrypto.keyId(favKey), favKey)) {
                L.w { "[FavoriteKey] rewrap_aborted reason=session_changed" }
                return@withContext null
            }
            FavoriteCrypto.wrapFavKey(FavoriteCrypto.deriveKek(newPriv), favKey)
        } ?: return
        if (!current()) return
        try {
            if (account == null) writeRepo.get().putRewrap(rewrapped)
            else writeRepo.get().putRewrap(rewrapped, account, authorization)
            L.i { "[FavoriteKey] rewrap_attempted" }
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteKey] rewrap_rejected" }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteKey] rewrap_conflict" }
        }
    }

    













    suspend fun onPrimaryLogin(oldPriv: ByteArray?, newPriv: ByteArray) {
        val data = syncRepo.getFavorites()
        if (data != null) syncRepo.applyServerMeta(data)
        val wrapped = data?.wrappedFavKey
        if (wrapped.isNullOrEmpty()) {
            L.i { "[FavoriteKey] primary login: server has no favorites, nothing to do" }
            return
        }
        val decision = withContext(Dispatchers.IO) {
            
            if (FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(newPriv), wrapped) != null) {
                return@withContext Decision.NONE
            }
            val recoverable = keyRepo.hasKey() ||
                (oldPriv != null && FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(oldPriv), wrapped) != null)
            if (recoverable) Decision.REWRAP else Decision.RESET
        }
        when (decision) {
            Decision.NONE -> L.i { "[FavoriteKey] primary login: new KEK already unwraps, no action" }
            Decision.REWRAP -> {
                L.i { "[FavoriteKey] primary login: favKey recoverable -> rewrap (list preserved)" }
                rewrapOnMasterKeyRotation(oldPriv, newPriv)
            }
            Decision.RESET -> {
                L.i { "[FavoriteKey] primary login: favKey unrecoverable -> reset (list cleared)" }
                resetFavorites()
            }
        }
    }

    private enum class Decision { NONE, REWRAP, RESET }
}
