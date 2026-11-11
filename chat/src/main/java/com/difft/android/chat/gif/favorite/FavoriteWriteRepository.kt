package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.FavoriteEncryptedAttachmentProvider
import com.difft.android.chat.util.FileEncryptionUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.BaseResponse
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.FavoriteAction
import com.difft.android.network.responses.FavoriteItemMeta
import com.difft.android.network.responses.FavoritesPutRequest
import com.difft.android.network.responses.FavoritesResponse
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toModel
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toPendingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.difft.app.database.models.FavoriteGifModel
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton


sealed interface FavResult {
    data object Ok : FavResult
    data class CapReached(val onEvictOldest: suspend () -> Unit) : FavResult
    
    data object SyncDeferred : FavResult
    
    data object Rejected : FavResult
}


class CasExhaustedException : RuntimeException("favorites CAS retries exhausted")






class FavoritePermanentRejectException(status: Int) : RuntimeException("favorites PUT rejected status=$status")




@Singleton
class FavoriteWriteRepository @Inject constructor(
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    private val urlManager: UrlManager,
    private val syncRepo: FavoriteSyncRepository,
    private val keyRepo: FavoriteKeyRepo,
    private val assetUploader: FavoriteAssetUploader,
    private val keyLifecycle: FavoriteKeyLifecycle,
    
    
    private val optimisticWriter: dagger.Lazy<FavoriteOptimisticWriter>
) {
    private val favoritesUrl: String get() = urlManager.gifs + "v1/gifs/favorites"

    




    suspend fun favorite(file: File, width: Int, height: Int): FavResult {
        
        
        
        val confirmed = syncRepo.confirmedRecords()
        if (confirmed.size >= MAX_FAVORITES) {
            val oldestFileHash = confirmed.minByOrNull { it.addedListVersion }?.attachment?.fileHash
            return FavResult.CapReached(onEvictOldest = {
                
                
                oldestFileHash?.let { optimisticWriter.get().unfavorite(it) }
            })
        }

        
        val pointer = assetUploader.transStore(file, width, height)

        
        
        seedFavoritesCache(file, pointer.id, pointer.key)

        
        
        val placeholderVersion = syncRepo.nextTopVersion()
        syncRepo.upsert(pointer.toPendingModel(placeholderVersion))

        
        
        val firstCreate = keyLifecycle.ensureFavKey()
        if (!keyRepo.hasKey()) {
            L.i { "[FavoriteWrite] favorite kept pending (no favKey) fileHash=${pointer.fileHash}" }
            return FavResult.SyncDeferred
        }

        
        
        
        
        
        
        
        if (!firstCreate) syncRepo.pullAndDecrypt()

        
        
        val record = FavoriteRecord(pointer, syncRepo.cachedListVersion)
        return try {
            val newVersion = putWithCas(FavoriteAction.FAVORITE, record, wrappedFavKey = keyLifecycle.wrappedFavKeyForPut(firstCreate))
            
            syncRepo.upsert(pointer.toConfirmedModel(newVersion))
            FavResult.Ok
        } catch (e: FavoritePermanentRejectException) {
            
            
            L.w { "[FavoriteWrite] favorite permanently rejected, rolled back fileHash=${pointer.fileHash}: ${e.message}" }
            syncRepo.removeByFileHash(pointer.fileHash)
            FavResult.Rejected
        } catch (e: CasExhaustedException) {
            
            L.w { "[FavoriteWrite] favorite CAS exhausted, kept optimistic fileHash=${pointer.fileHash}" }
            FavResult.SyncDeferred
        }
    }

    






    suspend fun reconfirm(fileHash: String) {
        if (!keyRepo.hasKey()) return 
        
        val row = syncRepo.firstByFileHash(fileHash)?.takeIf { it.pending && !it.attachmentId.isNullOrEmpty() } ?: return
        val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
        
        
        syncRepo.pullAndDecrypt()
        try {
            val newVersion = putWithCas(FavoriteAction.FAVORITE, record, wrappedFavKey = keyLifecycle.wrappedFavKeyForPut())
            syncRepo.upsert(record.attachment.toConfirmedModel(newVersion))
        } catch (e: FavoritePermanentRejectException) {
            
            
            
            L.w { "[FavoriteWrite] reconfirm permanently rejected fileHash=$fileHash: ${e.message}" }
            syncRepo.upsert(record.attachment.toConfirmedModel(syncRepo.cachedListVersion))
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteWrite] reconfirm CAS exhausted, kept pending fileHash=$fileHash" }
        } catch (e: IOException) {
            
            L.w { "[FavoriteWrite] reconfirm transient failure, kept pending fileHash=$fileHash: ${e.message}" }
        }
    }

    


    suspend fun syncUnfavorite(record: FavoriteRecord) {
        val fileHash = record.attachment.fileHash
        
        
        
        if (!keyRepo.hasKey()) {
            L.i { "[FavoriteWrite] syncUnfavorite kept tombstone (no favKey) fileHash=$fileHash" }
            return
        }
        
        
        
        if (!syncRepo.hasTombstone(fileHash)) {
            L.i { "[FavoriteWrite] syncUnfavorite aborted, tombstone cleared (re-favorited) fileHash=$fileHash" }
            return
        }
        
        
        
        syncRepo.pullAndDecrypt()
        try {
            putWithCas(FavoriteAction.UNFAVORITE, record, wrappedFavKey = null)
            
            
            
            
            if (syncRepo.removeIfTombstone(fileHash)) {
                L.i { "[FavoriteWrite] syncUnfavorite confirmed, tombstone removed fileHash=$fileHash" }
            } else {
                L.i { "[FavoriteWrite] syncUnfavorite CAS ok but re-favorited mid-flight, kept row fileHash=$fileHash" }
            }
        } catch (e: FavoritePermanentRejectException) {
            
            
            
            L.w { "[FavoriteWrite] syncUnfavorite permanently rejected, dropping tombstone fileHash=$fileHash: ${e.message}" }
            syncRepo.removeIfTombstone(fileHash)
        } catch (e: CasExhaustedException) {
            
            L.w { "[FavoriteWrite] syncUnfavorite CAS exhausted, kept tombstone fileHash=$fileHash" }
        } catch (e: IOException) {
            
            L.w { "[FavoriteWrite] syncUnfavorite transient failure, kept tombstone fileHash=$fileHash: ${e.message}" }
        }
    }

    





    suspend fun flushPendingFavorites() {
        if (!keyRepo.hasKey()) return
        val cached = syncRepo.allCached()
        
        
        
        val tombstones = cached.filter { it.pendingRemoval }
        if (tombstones.isNotEmpty()) {
            
            runCatching { syncRepo.pullAndDecrypt() }
                .onFailure { L.w { "[FavoriteWrite] flush: pre-loop pull failed (continuing): ${it.message}" } }
        }
        for (row in tombstones) {
            val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
            
            
            
            runCatching { optimisticWriter.get().runGuarded { syncUnfavorite(record) } }
                .onFailure { L.w { "[FavoriteWrite] flush: unfavorite tombstone deferred fileHash=${row.fileHash}: ${it.message}" } }
        }
        
        
        
        
        
        val pending = cached.filter { it.pending }.sortedBy { it.addedListVersion }
        if (pending.isEmpty()) return
        L.i { "[FavoriteWrite] flushPendingFavorites count=${pending.size}" }
        val now = System.currentTimeMillis()
        for (row in pending) {
            
            
            
            
            if (row.pendingSince > 0 && now - row.pendingSince > MAX_PENDING_AGE_MS) {
                L.w { "[FavoriteWrite] flush: pending row expired (TTL), giving up fileHash=${row.fileHash} ageMs=${now - row.pendingSince}" }
                syncRepo.removeByFileHash(row.fileHash)
                continue
            }
            
            
            when (row.toPendingSource()) {
                is PendingSource.Remote, is PendingSource.Message -> {
                    optimisticWriter.get().resolvePending(row.fileHash)
                    continue
                }
                
                
                
                PendingSource.None -> {  }
            }
            val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
            try {
                
                
                val newVersion = optimisticWriter.get().runGuarded {
                    putWithCas(FavoriteAction.FAVORITE, record, wrappedFavKey = keyLifecycle.wrappedFavKeyForPut())
                }
                syncRepo.upsert(record.attachment.toConfirmedModel(newVersion))
            } catch (e: FavoritePermanentRejectException) {
                L.w { "[FavoriteWrite] flush: permanent reject, rolled back fileHash=${row.fileHash}: ${e.message}" }
                syncRepo.removeByFileHash(row.fileHash)
            } catch (e: CasExhaustedException) {
                L.w { "[FavoriteWrite] flush: still deferred (kept pending) fileHash=${row.fileHash}" }
            }
        }
    }

    




    suspend fun ensureFavKey(): Boolean = keyLifecycle.ensureFavKey()

    



    suspend fun resetFavorites() = keyLifecycle.resetFavorites()

    



    suspend fun rewrapOnMasterKeyRotation(oldPriv: ByteArray?, newPriv: ByteArray) =
        keyLifecycle.rewrapOnMasterKeyRotation(oldPriv, newPriv)

    suspend fun rewrapOnMasterKeyRotation(
        oldPriv: ByteArray?, newPriv: ByteArray, expectedAccount: String, expectedAuthorization: String?,
    ) = keyLifecycle.rewrapOnMasterKeyRotation(oldPriv, newPriv, expectedAccount, expectedAuthorization)

    



    suspend fun onPrimaryLogin(oldPriv: ByteArray?, newPriv: ByteArray) =
        keyLifecycle.onPrimaryLogin(oldPriv, newPriv)

    





    suspend fun putWithCas(action: String, item: FavoriteRecord, wrappedFavKey: String?): Long {
        var listVersion = syncRepo.cachedListVersion
        var records = syncRepo.confirmedRecords()
        repeat(MAX_CAS_RETRIES) { attempt ->
            val merged = applyAction(action, records, item)
            val capped = enforceCapInternal(merged)
            
            val resp = putRequest(capped.records, listOf(item), listVersion, action, wrappedFavKey)
            when {
                resp.isSuccess() -> {
                    val data = resp.data
                    if (data != null) syncRepo.applyServerMeta(data)
                    return data?.listVersion ?: (listVersion + 1)
                }
                isConflict(resp) -> {
                    if (attempt < MAX_CAS_RETRIES - 1) {
                        
                        syncRepo.pullAndDecrypt()
                        listVersion = syncRepo.cachedListVersion
                        records = syncRepo.confirmedRecords()
                        delay(BACKOFF_BASE_MS * (1L shl attempt)) 
                    }
                }
                else -> {
                    
                    
                    
                    L.w { "[FavoriteWrite] putWithCas permanent reject status=${resp.status}" }
                    throw FavoritePermanentRejectException(resp.status)
                }
            }
        }
        throw CasExhaustedException()
    }

    





    internal suspend fun putWholeList(records: List<FavoriteRecord>, action: String, wrappedFavKey: String?): Long {
        repeat(MAX_CAS_RETRIES) { attempt ->
            
            val resp = putRequest(records, records, syncRepo.cachedListVersion, action, wrappedFavKey)
            when {
                resp.isSuccess() -> {
                    resp.data?.let { syncRepo.applyServerMeta(it) }
                    return resp.data?.listVersion ?: syncRepo.cachedListVersion
                }
                isConflict(resp) -> {
                    if (attempt < MAX_CAS_RETRIES - 1) {
                        syncRepo.getFavorites()?.let { syncRepo.applyServerMeta(it) } 
                        delay(BACKOFF_BASE_MS * (1L shl attempt))
                    }
                }
                else -> {
                    L.w { "[FavoriteWrite] putWholeList permanent reject status=${resp.status}" }
                    throw FavoritePermanentRejectException(resp.status)
                }
            }
        }
        throw CasExhaustedException()
    }

    

    internal suspend fun putRewrap(
        wrappedFavKey: String, expectedAccount: String? = null, expectedAuthorization: String? = null,
    ) = withContext(Dispatchers.IO) {
        if (expectedAccount != null && !keyRepo.matchesSession(expectedAccount, expectedAuthorization)) return@withContext
        val resp = httpClient.httpService.putFavorites(
            favoritesUrl,
            FavoritesPutRequest(
                encVersion = FavoriteCrypto.ENC_VERSION,
                action = FavoriteAction.REWRAP,
                wrappedFavKey = wrappedFavKey
            )
        )
        if (!resp.isSuccess()) throw FavoritePermanentRejectException(resp.status)
    }

    private suspend fun putRequest(
        records: List<FavoriteRecord>,
        itemRecords: List<FavoriteRecord>,
        listVersion: Long,
        action: String,
        wrappedFavKey: String?
    ): BaseResponse<FavoritesResponse> = withContext(Dispatchers.IO) {
        val key = keyRepo.getFavKey() ?: throw CasExhaustedException()
        
        val blob = FavoriteCrypto.encrypt(key.favKey, FavoriteListPlain(records))
        val items = itemRecords.map {
            FavoriteItemMeta(it.attachment.id, it.attachment.authorizeId.toString(), it.attachment.fileHash)
        }
        
        val casVersion = if (action == FavoriteAction.RESET) null else listVersion
        httpClient.httpService.putFavorites(
            favoritesUrl,
            FavoritesPutRequest(
                
                
                
                encVersion = FavoriteCrypto.ENC_VERSION,
                action = action,
                listVersion = casVersion,
                keyId = key.keyId,
                blob = blob,
                items = items,
                wrappedFavKey = wrappedFavKey
            )
        )
    }

    
    private fun applyAction(action: String, records: List<FavoriteRecord>, item: FavoriteRecord): List<FavoriteRecord> =
        if (action == FavoriteAction.FAVORITE) {
            
            
            
            val bumped = item.copy(addedListVersion = nextAddedVersion(records))
            records.filterNot { it.attachment.fileHash == bumped.attachment.fileHash } + bumped
        } else {
            records.filterNot { it.attachment.fileHash == item.attachment.fileHash }
        }

    private fun nextAddedVersion(records: List<FavoriteRecord>): Long =
        (records.maxOfOrNull { it.addedListVersion } ?: syncRepo.cachedListVersion) + 1

    
    private data class CapResult(val records: List<FavoriteRecord>, val dropped: Int)

    




    private fun enforceCapInternal(records: List<FavoriteRecord>): CapResult {
        if (records.size <= MAX_FAVORITES) return CapResult(records, 0)
        val kept = records.sortedByDescending { it.addedListVersion }.take(MAX_FAVORITES)
        return CapResult(kept, records.size - kept.size)
    }

    private fun isConflict(resp: BaseResponse<FavoritesResponse>): Boolean =
        resp.status == FAVORITES_STATUS_CAS_CONFLICT

    





    internal suspend fun seedFavoritesCache(file: File, attachmentId: String, key: ByteArray) = withContext(Dispatchers.IO) {
        if (attachmentId.isEmpty() || key.size < 64) return@withContext
        try {
            val encFile = FavoriteEncryptedAttachmentProvider.encryptedFile(attachmentId)
            if (encFile.exists() && encFile.length() > 0) return@withContext
            if (file.exists()) FileEncryptionUtil.encryptFile(file, encFile, key)
        } catch (e: Exception) {
            L.w { "[FavoriteWrite] seed favorites cache failed attachmentId=$attachmentId: ${e.message}" }
        }
    }

    

    private fun FavoriteAttachmentPointer.toPendingModel(version: Long): FavoriteGifModel =
        FavoriteRecord(this, version).toModel().apply {
            this.pending = true
            this.pendingSince = System.currentTimeMillis()
        }

    private fun FavoriteAttachmentPointer.toConfirmedModel(version: Long): FavoriteGifModel =
        FavoriteRecord(this, version).toModel().apply {
            this.pending = false
        }

    companion object {
        const val MAX_FAVORITES = 200
        const val MAX_CAS_RETRIES = 3
        const val BACKOFF_BASE_MS = 100L

        

        const val MAX_PENDING_AGE_MS = 3L * 24 * 60 * 60 * 1000

        





        const val FAVORITES_STATUS_CAS_CONFLICT = 9
    }
}
