package com.difft.android.chat.gif.favorite

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
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

/** UI projection of a cached favorite (decrypted). aspectRatio clamped like GifUiItem. */
data class FavoriteGifUiItem(
    val fileHash: String,
    val attachmentId: String?,
    val authorizeId: Long,
    val width: Int,
    val height: Int,
    val pending: Boolean,
    /** Preview URL for a not-yet-uploaded optimistic row (display + send source); null otherwise. */
    val sourceUrl: String? = null
) {
    val aspectRatio: Float
        get() {
            if (width <= 0 || height <= 0) return 1f
            return (width.toFloat() / height.toFloat()).coerceIn(0.5f, 2f)
        }
}

/**
 * Read path of the favorites cache: pull from server, decrypt the blob (when the favKey is
 * available), and recalibrate the local WCDB cache. Also owns the in-process observable cache
 * [StateFlow] (single-device M3: the only writers are these repos in the same process, so an
 * in-memory refresh is sufficient — no DB change-tracker needed). See android-impl-design.md §B4.
 *
 * Holds the latest server version metadata ([cachedListVersion], [serverKeyId], etc.) that the
 * write path reads for CAS.
 */
@Singleton
class FavoriteSyncRepository @Inject constructor(
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    private val urlManager: UrlManager,
    private val keyRepo: FavoriteKeyRepo,
    private val userManager: UserManager
) {
    private val favoritesUrl: String get() = urlManager.gifs + "v1/gifs/favorites"
    // The `/gifs/` route requires token auth (JWT/microToken); the Basic baseAuth fallback gets 401.
    private fun token(): String = userManager.getUserData()?.microToken ?: ""

    private val _favorites = MutableStateFlow<List<FavoriteGifUiItem>>(emptyList())
    /** Observable favorites list, descending by addedListVersion, pending items on top. */
    fun observeFavorites(): StateFlow<List<FavoriteGifUiItem>> = _favorites.asStateFlow()

    /**
     * Serializes a server pull ([pullAndDecrypt]) against an optimistic-favorite backfill
     * (placeholder delete + confirmed upsert in [FavoriteWriteRepository.favoriteOptimistic]) so the
     * two can't interleave — otherwise a pull mid-backfill could snapshot pending rows, then the
     * backfill deletes the placeholder, then the pull re-inserts a now-stale copy. The write path
     * holds this lock around the whole backfill; pulls hold it around the cache replace.
     */
    val syncMutex = Mutex()

    /** Monotonic placeholder seq so optimistic rows sort to the very front (highest addedListVersion). */
    private val optimisticSeq = AtomicLong(0L)

    // Latest server metadata snapshot (updated on every successful pull / PUT).
    @Volatile var cachedListVersion: Long = 0L
        private set
    @Volatile var serverKeyId: String? = null
        private set
    @Volatile var serverEncVersion: Int = 1
        private set

    /** GET the favorites envelope. */
    suspend fun getFavorites(): FavoritesResponse? = withContext(Dispatchers.IO) {
        val resp = httpClient.httpService.getFavorites(token(), favoritesUrl)
        if (!resp.isSuccess()) {
            L.w { "[FavoriteSync] getFavorites non-success status=${resp.status}" }
            return@withContext null
        }
        resp.data
    }

    /**
     * Pull favorites, decrypt the blob with the locally-cached favKey, and recalibrate the cache.
     * v2: the favKey is always locally available by the time this runs (the caller establishes it
     * via ensureFavKey/unwrap first), so there is no "pending decrypt" state — an absent key or an
     * empty blob simply leaves the confirmed cache untouched.
     */
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

    // -- cache write surface (shared with FavoriteWriteRepository) --

    /** Replace all confirmed (non-pending) cache rows with [records], keeping local pending rows.
     *  Serialized against the optimistic backfill via [syncMutex] so a pull can't snapshot pending
     *  rows mid-swap and re-insert a placeholder the backfill just deleted. */
    suspend fun replaceConfirmedCache(records: List<FavoriteRecord>) = withContext(Dispatchers.IO) {
      syncMutex.withLock {
        val pending = wcdb.favoriteGifs.getAllObjects(DBFavoriteGifModel.pending.eq(true))
        // Snapshot old confirmed attachmentIds so we can drop the on-disk ciphertext of favorites the
        // server no longer has (e.g. unfavorited on another device). Diff-based: O(removed), no dir scan.
        val oldConfirmedIds = wcdb.favoriteGifs.getAllObjects(DBFavoriteGifModel.pending.eq(false))
            .mapNotNull { it.attachmentId }
        wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.pending.eq(false))
        records.forEach { wcdb.favoriteGifs.insertOrReplaceObject(it.toModel()) }
        // Preserve any optimistic pending rows that are not yet confirmed by the server.
        pending.filter { p -> records.none { it.attachment.fileHash == p.fileHash } }
            .forEach { wcdb.favoriteGifs.insertOrReplaceObject(it) }
        // Keep = new server records + surviving pending rows: never delete a file a pending optimistic
        // favorite still references (it shares the attachmentId but isn't in the server record set yet).
        val keepIds = (records.map { it.attachment.id } + pending.mapNotNull { it.attachmentId }).toHashSet()
        (oldConfirmedIds - keepIds).forEach { deleteCachedCiphertext(it) }
        refreshObservable()
      }
    }

    /** Upsert a single cache row (optimistic add / confirm). */
    suspend fun upsert(model: FavoriteGifModel) = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.insertOrReplaceObject(model)
        refreshObservable()
    }

    /** Remove a single cache row by fileHash (optimistic unfavorite). */
    suspend fun removeByFileHash(fileHash: String) = withContext(Dispatchers.IO) {
        // Also drop the on-disk ciphertext (local unfavorite / cap-eviction / rollback): the next
        // pull's diff can't catch it (the row is already gone by then), so clean it here.
        wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.fileHash.eq(fileHash))
            ?.attachmentId?.let { deleteCachedCiphertext(it) }
        wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.fileHash.eq(fileHash))
        refreshObservable()
    }

    /**
     * Insert an optimistic placeholder pending row for a panel/search favorite (sorts to the very
     * top). Keyed by [tempHash] (`"giphy:<id>"`) which never matches a server record's real fileHash,
     * so a subsequent pull's insertOrReplace can't overwrite/confirm it and its pending flag makes
     * [replaceConfirmedCache] preserve it. Displayed + sent from [sourceUrl] until the background
     * transStore + CAS PUT confirm it. Returns the placeholder version so the caller can reuse it for
     * the confirmed row (preserving the top position).
     */
    suspend fun upsertPlaceholder(
        tempHash: String,
        sourceUrl: String,
        width: Int,
        height: Int
    ): Long = withContext(Dispatchers.IO) {
        val version = Long.MAX_VALUE - optimisticSeq.getAndIncrement()
        val model = FavoriteGifModel().apply {
            this.fileHash = tempHash
            this.width = width
            this.height = height
            this.addedListVersion = version
            this.pending = true
            this.pendingSince = System.currentTimeMillis()
            this.sourceUrl = sourceUrl
        }
        wcdb.favoriteGifs.insertOrReplaceObject(model)
        refreshObservable()
        version
    }

    /** Remove an optimistic placeholder by its temp fileHash (silent rollback). No ciphertext yet. */
    suspend fun removeByTempHash(tempHash: String) = withContext(Dispatchers.IO) {
        // Serialized against [confirmPlaceholder] via [syncMutex]: the optimistic backfill does its
        // "placeholder still present? -> swap in the confirmed row" check-then-act under the same lock.
        // Without this lock the delete could interleave with that check, letting a just-unfavorited
        // placeholder be resurrected as a confirmed row (and skip the compensating server unfavorite).
        syncMutex.withLock {
            wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.fileHash.eq(tempHash))
            refreshObservable()
        }
    }

    /**
     * Atomically swap an optimistic placeholder for its confirmed row once the background write
     * succeeds: in ONE WCDB transaction delete the temp-hash placeholder and upsert [confirmed]
     * (real fileHash). If a row for the real fileHash already existed (re-favorite / concurrent
     * confirm) insertOrReplace keeps a single copy (dedup). Serialized against pulls via [syncMutex]
     * so the swap and a concurrent [replaceConfirmedCache] can't interleave.
     *
     * Guarded on the placeholder still existing: if the user unfavorited the placeholder while the
     * background write was in flight (its temp-hash row is already gone), the swap is a NO-OP —
     * otherwise the confirmed row would resurrect a favorite the user just removed. Returns true if
     * the swap happened (placeholder still present), false if it was skipped (placeholder gone).
     */
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

    /** Best-effort delete of a favorite's on-disk ciphertext cache, keeping it in sync with the list. */
    private fun deleteCachedCiphertext(attachmentId: String) {
        runCatching { FavoriteEncryptedAttachmentProvider.encryptedFile(attachmentId).delete() }
    }

    /** All cached rows (confirmed + pending), descending by addedListVersion. */
    suspend fun allCached(): List<FavoriteGifModel> = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.allObjects.sortedByDescending { it.addedListVersion }
    }

    /** Confirmed records (pending excluded) — the source of truth for blob re-encryption. */
    suspend fun confirmedRecords(): List<FavoriteRecord> = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.getAllObjects(DBFavoriteGifModel.pending.eq(false))
            .map { it.toRecord() }
    }

    fun applyServerMeta(data: FavoritesResponse) {
        cachedListVersion = data.listVersion
        serverKeyId = data.keyId
        serverEncVersion = data.encVersion
    }

    /**
     * Re-read the cache and push to the observable StateFlow: pending on top, then newest-added
     * first (addedListVersion DESC), with fileHash as a deterministic tiebreaker so rows that
     * share a version (e.g. rapid adds, or legacy data from a pre-fix reset) keep a STABLE order
     * instead of reshuffling on every refresh.
     */
    suspend fun refreshObservable() = withContext(Dispatchers.IO) {
        _favorites.value = wcdb.favoriteGifs.allObjects
            .sortedWith(
                compareByDescending<FavoriteGifModel> { it.pending }
                    .thenByDescending { it.addedListVersion }
                    .thenBy { it.fileHash }
            )
            .map { it.toUiItem() }
    }

    companion object {
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
            sourceUrl = sourceUrl
        )
    }
}
