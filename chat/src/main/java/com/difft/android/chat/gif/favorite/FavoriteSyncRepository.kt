package com.difft.android.chat.gif.favorite

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.FavoriteEncryptedAttachmentProvider
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.FavoritesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBFavoriteGifModel
import org.difft.app.database.models.FavoriteGifModel
import org.difft.app.database.wcdb
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton


data class FavoriteGifUiItem(
    val fileHash: String,
    val attachmentId: String?,
    val authorizeId: Long,
    val width: Int,
    val height: Int,
    val pending: Boolean,
    
    val sourceUrl: String? = null,
    
    val pendingSource: PendingSource = PendingSource.None
) {
    val aspectRatio: Float
        get() {
            if (width <= 0 || height <= 0) return 1f
            return (width.toFloat() / height.toFloat()).coerceIn(0.5f, 2f)
        }
}




@Singleton
class FavoriteSyncRepository @Inject constructor(
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    private val urlManager: UrlManager,
    private val keyRepo: FavoriteKeyRepo
) {
    private val favoritesUrl: String get() = urlManager.gifs + "v1/gifs/favorites"

    private val _favorites = MutableStateFlow<List<FavoriteGifUiItem>>(emptyList())
    
    fun observeFavorites(): StateFlow<List<FavoriteGifUiItem>> = _favorites.asStateFlow()

    






    val syncMutex = Mutex()

    
    private val optimisticSeq = AtomicLong(0L)

    

    fun nextTopVersion(): Long = TOP_VERSION_BASE + optimisticSeq.incrementAndGet()

    
    @Volatile var cachedListVersion: Long = 0L
        private set
    @Volatile var serverKeyId: String? = null
        private set
    @Volatile var serverEncVersion: Int = 1
        private set

    
    suspend fun getFavorites(): FavoritesResponse? = withContext(Dispatchers.IO) {
        val resp = httpClient.httpService.getFavorites(favoritesUrl)
        if (!resp.isSuccess()) {
            L.w { "[FavoriteSync] getFavorites non-success status=${resp.status}" }
            return@withContext null
        }
        resp.data
    }

    





    suspend fun pullAndDecrypt() {
        val key = keyRepo.getFavKey()
        val data = getFavorites() ?: return
        applyServerMeta(data)
        val blob = data.blob
        if (key == null || blob.isNullOrEmpty()) {
            refreshObservable()
            L.i { "[FavoriteSync] pulled meta listVersion=${data.listVersion} keyId=${data.keyId != null} hasBlob=${!blob.isNullOrEmpty()}" }
            return
        }
        val plain = FavoriteCrypto.decrypt(key.favKey, blob)
        if (plain == null) {
            refreshObservable()
            L.w { "[FavoriteSync] blob decrypt failed (key mismatch?), keeping local cache" }
            return
        }
        replaceConfirmedCache(plain.records)
        L.i { "[FavoriteSync] decrypted ${plain.records.size} favorites" }
    }

    

    


    suspend fun replaceConfirmedCache(records: List<FavoriteRecord>) = withContext(Dispatchers.IO) {
      syncMutex.withLock {
        val pending = wcdb.favoriteGifs.getAllObjects(DBFavoriteGifModel.pending.eq(true))
        
        
        
        
        val tombstones = wcdb.favoriteGifs.getAllObjects(
            DBFavoriteGifModel.pending.eq(false).and(DBFavoriteGifModel.pendingRemoval.eq(true))
        )
        val tombstoneHashes = tombstones.map { it.fileHash }.toHashSet()
        
        
        
        
        
        
        
        val refavoriteHashes = pending.filter { it.pendingSourceJson == null }.map { it.fileHash }.toHashSet()
        
        
        
        val oldConfirmedIds = wcdb.favoriteGifs.getAllObjects(DBFavoriteGifModel.pending.eq(false))
            .filterNot { it.pendingRemoval }
            .mapNotNull { it.attachmentId }
        wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.pending.eq(false))
        
        
        records.filterNot { it.attachment.fileHash in tombstoneHashes || it.attachment.fileHash in refavoriteHashes }
            .forEach { wcdb.favoriteGifs.insertOrReplaceObject(it.toModel()) }
        
        
        pending.filter { p -> p.pendingSourceJson == null || records.none { it.attachment.fileHash == p.fileHash } }
            .forEach { wcdb.favoriteGifs.insertOrReplaceObject(it) }
        
        tombstones.forEach { wcdb.favoriteGifs.insertOrReplaceObject(it) }
        
        
        val keepIds = (records.map { it.attachment.id } +
            pending.mapNotNull { it.attachmentId } +
            tombstones.mapNotNull { it.attachmentId }).toHashSet()
        (oldConfirmedIds - keepIds).forEach { deleteCachedCiphertext(it) }
        refreshObservable()
      }
    }

    
    suspend fun upsert(model: FavoriteGifModel) = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.insertOrReplaceObject(model)
        refreshObservable()
    }

    
    suspend fun removeByFileHash(fileHash: String) = withContext(Dispatchers.IO) {
        
        
        wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.fileHash.eq(fileHash))
            ?.attachmentId?.let { deleteCachedCiphertext(it) }
        wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.fileHash.eq(fileHash))
        refreshObservable()
    }

    

    suspend fun removeIfTombstone(fileHash: String): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val row = wcdb.favoriteGifs.getFirstObject(
                DBFavoriteGifModel.fileHash.eq(fileHash).and(DBFavoriteGifModel.pendingRemoval.eq(true))
            ) ?: return@withLock false
            row.attachmentId?.let { deleteCachedCiphertext(it) }
            wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.fileHash.eq(fileHash))
            refreshObservable()
            true
        }
    }

    







    suspend fun upsertRemotePlaceholder(
        tempHash: String,
        sourceUrl: String,
        width: Int,
        height: Int
    ): Long = withContext(Dispatchers.IO) {
        val version = nextTopVersion()
        val model = FavoriteGifModel().apply {
            this.fileHash = tempHash
            this.width = width
            this.height = height
            this.addedListVersion = version
            this.pending = true
            this.pendingSince = System.currentTimeMillis()
            this.pendingSourceJson = PendingSource.Remote(sourceUrl).toJson()
        }
        wcdb.favoriteGifs.insertOrReplaceObject(model)
        refreshObservable()
        version
    }

    







    suspend fun upsertMessagePlaceholder(
        tempHash: String,
        ref: PendingSource.Message,
        width: Int,
        height: Int,
        size: Int,
        contentType: String
    ): Long = withContext(Dispatchers.IO) {
        val version = nextTopVersion()
        val model = FavoriteGifModel().apply {
            this.fileHash = tempHash
            this.width = width
            this.height = height
            this.size = size
            this.contentType = contentType
            this.addedListVersion = version
            this.pending = true
            this.pendingSince = System.currentTimeMillis()
            this.pendingSourceJson = ref.toJson()
        }
        wcdb.favoriteGifs.insertOrReplaceObject(model)
        refreshObservable()
        version
    }

    








    suspend fun markPendingRefavorite(fileHash: String) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val row = wcdb.favoriteGifs.getFirstObject(
                DBFavoriteGifModel.fileHash.eq(fileHash).and(DBFavoriteGifModel.pending.eq(false))
            ) ?: return@withLock
            row.pendingRemoval = false
            row.pending = true
            row.addedListVersion = nextTopVersion()
            wcdb.favoriteGifs.insertOrReplaceObject(row)
            refreshObservable()
        }
    }

    









    suspend fun markPendingRemoval(fileHash: String) = withContext(Dispatchers.IO) {
        
        
        
        syncMutex.withLock {
            val row = wcdb.favoriteGifs.getFirstObject(
                DBFavoriteGifModel.fileHash.eq(fileHash).and(DBFavoriteGifModel.pendingRemoval.eq(false))
            )?.takeIf { !it.attachmentId.isNullOrEmpty() } ?: return@withLock
            row.pending = false
            row.pendingRemoval = true
            wcdb.favoriteGifs.insertOrReplaceObject(row)
            refreshObservable()
        }
    }

    





    suspend fun hasTombstone(fileHash: String): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            wcdb.favoriteGifs.getFirstObject(
                DBFavoriteGifModel.fileHash.eq(fileHash).and(DBFavoriteGifModel.pendingRemoval.eq(true))
            ) != null
        }
    }

    
    suspend fun hasConfirmed(fileHash: String): Boolean = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.getFirstObject(
            DBFavoriteGifModel.fileHash.eq(fileHash).and(DBFavoriteGifModel.pending.eq(false))
        ) != null
    }

    
    suspend fun firstByFileHash(fileHash: String): FavoriteGifModel? = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.fileHash.eq(fileHash))
    }

    
    suspend fun removeByTempHash(tempHash: String) = withContext(Dispatchers.IO) {
        
        
        
        
        syncMutex.withLock {
            wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.fileHash.eq(tempHash))
            refreshObservable()
        }
    }

    



    suspend fun confirmPlaceholder(tempHash: String, confirmed: FavoriteGifModel): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val swapped = wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.fileHash.eq(tempHash)) != null
            if (swapped) {
                wcdb.db.runTransaction {
                    wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.fileHash.eq(tempHash))
                    
                    
                    wcdb.favoriteGifs.insertOrReplaceObject(confirmed)
                    true
                }
                refreshObservable()
            }
            swapped
        }
    }

    
    private fun deleteCachedCiphertext(attachmentId: String) {
        runCatching { FavoriteEncryptedAttachmentProvider.encryptedFile(attachmentId).delete() }
    }

    
    suspend fun allCached(): List<FavoriteGifModel> = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.allObjects.sortedByDescending { it.addedListVersion }
    }

    

    suspend fun confirmedRecords(): List<FavoriteRecord> = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.getAllObjects(DBFavoriteGifModel.pending.eq(false))
            .filterNot { it.pendingRemoval }
            .map { it.toRecord() }
    }

    fun applyServerMeta(data: FavoritesResponse) {
        cachedListVersion = data.listVersion
        serverKeyId = data.keyId
        serverEncVersion = data.encVersion
    }

    
    suspend fun refreshObservable() = withContext(Dispatchers.IO) {
        _favorites.value = wcdb.favoriteGifs.allObjects
            
            
            .filterNot { it.pendingRemoval }
            
            .sortedWith(
                compareByDescending<FavoriteGifModel> { it.addedListVersion }
                    .thenBy { it.fileHash }
            )
            .map { it.toUiItem() }
    }

    companion object {
        
        const val REMOTE_TEMP_PREFIX = "giphy:"
        const val MSG_TEMP_PREFIX = "msg:"

        




        const val TOP_VERSION_BASE = Long.MAX_VALUE - 1_000_000_000_000L

        




        fun FavoriteGifModel.toPendingSource(): PendingSource = when {
            !pending -> PendingSource.None
            else -> pendingSourceFromJson(pendingSourceJson)
                ?: sourceUrl?.let { PendingSource.Remote(it) }
                ?: PendingSource.None
        }

        fun FavoriteRecord.toModel(): FavoriteGifModel = FavoriteGifModel().apply {
            fileHash = attachment.fileHash
            attachmentId = attachment.id
            authorizeId = attachment.authorizeId
            encKey = Base64.encodeToString(attachment.key, Base64.NO_WRAP)
            digest = Base64.encodeToString(attachment.digest, Base64.NO_WRAP)
            contentType = attachment.contentType
            width = attachment.width
            height = attachment.height
            size = attachment.size
            addedListVersion = this@toModel.addedListVersion
            pending = false
        }

        fun FavoriteGifModel.toRecord(): FavoriteRecord = FavoriteRecord(
            attachment = FavoriteAttachmentPointer(
                id = attachmentId ?: "",
                authorizeId = authorizeId,
                key = encKey?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
                digest = digest?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
                fileHash = fileHash,
                contentType = contentType,
                width = width,
                height = height,
                size = size
            ),
            addedListVersion = addedListVersion
        )

        fun FavoriteGifModel.toUiItem(): FavoriteGifUiItem = FavoriteGifUiItem(
            fileHash = fileHash,
            attachmentId = attachmentId,
            authorizeId = authorizeId,
            width = width,
            height = height,
            pending = pending,
            sourceUrl = sourceUrl,
            pendingSource = toPendingSource()
        )
    }
}
