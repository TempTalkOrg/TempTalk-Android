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
    /** Preview URL for a not-yet-uploaded optimistic Remote row (display + send source); null otherwise. */
    val sourceUrl: String? = null,
    /** Typed pending source so the cell branches on render without leaking DB shape. */
    val pendingSource: PendingSource = PendingSource.None
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

    /** Monotonic seq for optimistic sort keys (placeholder / bump-to-top). */
    private val optimisticSeq = AtomicLong(0L)

    /**
     * Next optimistic `addedListVersion`: a monotonically INCREASING key on a high base, so the most
     * RECENT optimistic add/bump gets the HIGHEST value and sorts to the top (newest-first, matching
     * the descending sort in [refreshObservable]). The high base keeps it above any real server
     * listVersion (which is small); the 1e12 headroom below Long.MAX_VALUE precludes overflow.
     * (The prior `Long.MAX_VALUE - seq` DECREASED per add, so newer rows sorted BELOW older ones.)
     */
    fun nextTopVersion(): Long = TOP_VERSION_BASE + optimisticSeq.incrementAndGet()

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
        // Optimistic-unfavorite tombstones: confirmed rows (pending=false) marked pendingRemoval whose
        // UNFAVORITE CAS hasn't landed yet. They must SURVIVE this replace and their fileHash must be
        // SKIPPED when re-inserting server records — the server still holds the item, so a naive replace
        // would resurrect a favorite the user just removed. Mirrors the pending-add preservation below.
        val tombstones = wcdb.favoriteGifs.getAllObjects(
            DBFavoriteGifModel.pending.eq(false).and(DBFavoriteGifModel.pendingRemoval.eq(true))
        )
        val tombstoneHashes = tombstones.map { it.fileHash }.toHashSet()
        // Pending RE-FAVORITES: a pending row with a REAL (non-temp) fileHash the server ALSO has — its
        // bumped top position hasn't been CAS'd yet, so the server copy carries the STALE pre-bump
        // addedListVersion. Keep the LOCAL pending row (bumped + pending) and SKIP the server record for
        // these hashes, else the pull reverts the re-favorite to its old slot AND drops the pending flag
        // so flushPendingFavorites never retries it. Placeholders don't need this (their temp "giphy:/
        // msg:" fileHash never matches a record); a re-favorite is a pending row with NO pendingSourceJson
        // (markPendingRefavorite kept a confirmed row's real attachment, so it has no pending source).
        val refavoriteHashes = pending.filter { it.pendingSourceJson == null }.map { it.fileHash }.toHashSet()
        // Snapshot old confirmed attachmentIds so we can drop the on-disk ciphertext of favorites the
        // server no longer has (e.g. unfavorited on another device). Diff-based: O(removed), no dir scan.
        // Tombstone ciphertext is preserved (their attachmentIds are kept below) until the CAS hard-deletes.
        val oldConfirmedIds = wcdb.favoriteGifs.getAllObjects(DBFavoriteGifModel.pending.eq(false))
            .filterNot { it.pendingRemoval }
            .mapNotNull { it.attachmentId }
        wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.pending.eq(false))
        // Skip a tombstoned item (server still has it; local removal pending) and a re-favorited item's
        // stale server copy (kept as the bumped local pending row below).
        records.filterNot { it.attachment.fileHash in tombstoneHashes || it.attachment.fileHash in refavoriteHashes }
            .forEach { wcdb.favoriteGifs.insertOrReplaceObject(it.toModel()) }
        // Preserve pending rows the server hasn't confirmed at their local position: placeholders (temp
        // hash, never in records) AND re-favorites (real hash the server has, but the bumped local row wins).
        pending.filter { p -> p.pendingSourceJson == null || records.none { it.attachment.fileHash == p.fileHash } }
            .forEach { wcdb.favoriteGifs.insertOrReplaceObject(it) }
        // Re-insert the surviving tombstones (deleted above with the rest of the confirmed rows).
        tombstones.forEach { wcdb.favoriteGifs.insertOrReplaceObject(it) }
        // Keep = new server records + surviving pending rows + tombstones: never delete a file a pending
        // optimistic favorite or a not-yet-synced tombstone still references.
        val keepIds = (records.map { it.attachment.id } +
            pending.mapNotNull { it.attachmentId } +
            tombstones.mapNotNull { it.attachmentId }).toHashSet()
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
     * Atomic check-and-delete: hard-delete the row (and its ciphertext) ONLY if it is STILL a tombstone
     * (pendingRemoval), holding [syncMutex] across the check AND the delete. syncUnfavorite's post-CAS
     * "remove the tombstone" step used a separate hasTombstone() + removeByFileHash(): a re-favorite
     * ([markPendingRefavorite], which only takes syncMutex, not casMutex) could land in the gap and flip
     * the row to a legit pending re-favorite that the unconditional delete would then destroy (silently
     * losing the re-favorite). Returns true if it deleted. No-op (returns false) if the row was cleared /
     * re-favorited meanwhile — leave it for its own re-favorite CAS.
     */
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

    /**
     * Insert an optimistic placeholder pending row for a panel/search (Remote) favorite (sorts to the
     * very top). Keyed by [tempHash] (`"giphy:<id>"`) which never matches a server record's real
     * fileHash, so a subsequent pull's insertOrReplace can't overwrite/confirm it and its pending flag
     * makes [replaceConfirmedCache] preserve it. Displayed + sent from [sourceUrl] until the background
     * transStore + CAS PUT confirm it. Returns the placeholder version so the caller can reuse it for
     * the confirmed row (preserving the top position).
     */
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

    /**
     * Insert an optimistic placeholder pending row for a MESSAGE gif favorite (favorite-without-
     * download). Keyed by [tempHash] (`"msg:<accountFileHash>"`) — the `"msg:"` prefix keeps it
     * distinct from the eventual confirmed bare-fileHash row so a concurrent server pull can't
     * overwrite/confirm it. The message ref columns carry everything the background resolve needs
     * (isExist fast-pass key = accountFileHash, download-on-miss pointer, local render base path).
     * Returns the placeholder version so the caller can reuse it for the confirmed row.
     */
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

    /**
     * Re-favorite of an already-present favorite: turn the confirmed row (or a pending-removal tombstone
     * for the same content hash) into a RETRYABLE pending re-favorite — set pending=true + a fresh top
     * sort key and clear any removal tombstone, keeping its attachment (id/key/digest). Unlike a
     * local-only bump, a pending row is re-driven by [FavoriteWriteRepository.flushPendingFavorites]
     * (None-source branch → putWithCas FAVORITE), so an offline re-favorite survives and syncs on
     * reconnect instead of being reverted to the server order by the next pull. pendingSince stays 0 so
     * the TTL never reaps a real, already-synced favorite. No-op if the confirmed row is absent.
     */
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

    /**
     * Mark a row that LIVES ON THE SERVER as an optimistic-unfavorite tombstone (instant UI hide +
     * excluded from cap and blob). Matches a CONFIRMED row OR a pending RE-FAVORITE (pending=true with a
     * real attachment) — both exist server-side, so unfavoriting must tombstone + CAS-remove, not
     * local-drop. Resets pending=false so a pending re-favorite becomes a plain tombstone (cancels its
     * in-flight FAVORITE intent). SKIPS a pending-ADD placeholder (empty attachmentId, never reached the
     * server — the caller local-drops that). The row stays in the cache so a concurrent server pull can't
     * resurrect it; the UNFAVORITE CAS + hard-delete are deferred to the background / flushPendingFavorites.
     * No-op if the row is absent.
     */
    suspend fun markPendingRemoval(fileHash: String) = withContext(Dispatchers.IO) {
        // syncMutex: serialize the tombstone flag against a concurrent pull + the other flag mutators so
        // the background syncUnfavorite re-check reads a consistent flag (no interleave with a pull that
        // could re-add or a re-favorite that could clear it mid-mutation).
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

    /**
     * True when a tombstoned (pendingRemoval) row exists for [fileHash] (re-favorite must cancel it; the
     * background syncUnfavorite re-derives its delete intent from this). Read under [syncMutex] so it
     * reads a consistent flag against the mark/clear mutators — callers already hold casMutex, and this
     * takes ONLY syncMutex (casMutex -> syncMutex order), so no deadlock.
     */
    suspend fun hasTombstone(fileHash: String): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            wcdb.favoriteGifs.getFirstObject(
                DBFavoriteGifModel.fileHash.eq(fileHash).and(DBFavoriteGifModel.pendingRemoval.eq(true))
            ) != null
        }
    }

    /** True when a CONFIRMED (non-pending) row already exists for [fileHash] (re-favorite dedup). */
    suspend fun hasConfirmed(fileHash: String): Boolean = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.getFirstObject(
            DBFavoriteGifModel.fileHash.eq(fileHash).and(DBFavoriteGifModel.pending.eq(false))
        ) != null
    }

    /** The row for [fileHash] (a pending tempHash or a real fileHash), or null if absent. */
    suspend fun firstByFileHash(fileHash: String): FavoriteGifModel? = withContext(Dispatchers.IO) {
        wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.fileHash.eq(fileHash))
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
     *
     * The upserted [confirmed] row has pendingRemoval=false (fresh model default), so replacing a
     * bare-hash row that a concurrent re-favorite left as a tombstone also CLEARS that tombstone
     * (design §12.1(4)) — the just-confirmed row must be visible, not hidden by a stale removal flag.
     */
    suspend fun confirmPlaceholder(tempHash: String, confirmed: FavoriteGifModel): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val swapped = wcdb.favoriteGifs.getFirstObject(DBFavoriteGifModel.fileHash.eq(tempHash)) != null
            if (swapped) {
                wcdb.db.runTransaction {
                    wcdb.favoriteGifs.deleteObjects(DBFavoriteGifModel.fileHash.eq(tempHash))
                    // insertOrReplace on the bare fileHash overwrites any existing tombstone row for the
                    // same hash with this pendingRemoval=false confirmed row (tombstone-clear on confirm).
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

    /**
     * Confirmed records — the source of truth for the cap count AND the re-encrypted CAS blob. Excludes
     * both pending placeholders (not yet on the server) and pendingRemoval tombstones (optimistically
     * unfavorited, awaiting their UNFAVORITE CAS): a tombstone must not be counted toward the cap nor
     * re-included in the blob (that would resurrect it). Dropping it from the blob is safe because
     * applyAction(UNFAVORITE) is idempotent (design §11.1).
     */
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

    /**
     * Re-read the cache and push to the observable StateFlow: pending on top, then newest-added
     * first (addedListVersion DESC), with fileHash as a deterministic tiebreaker so rows that
     * share a version (e.g. rapid adds, or legacy data from a pre-fix reset) keep a STABLE order
     * instead of reshuffling on every refresh.
     */
    suspend fun refreshObservable() = withContext(Dispatchers.IO) {
        _favorites.value = wcdb.favoriteGifs.allObjects
            // Hide optimistic-unfavorite tombstones immediately (they still exist locally so a pull
            // can't resurrect them, but the user has removed them so they must not show).
            .filterNot { it.pendingRemoval }
            // Order purely by addedListVersion (desc), NOT by pending-first. Optimistic rows —
            // placeholders AND re-favorite bumps — all carry nextTopVersion() (a single monotonic
            // counter, far above any server listVersion), so the item the user acted on LAST has the
            // highest value and sorts to the top regardless of pending/confirmed state. A pending-first
            // primary key (the old behavior) forced every placeholder above every confirmed row, so a
            // re-favorite of an already-confirmed item could never rank above still-pending adds and
            // "last action on top" broke in the mixed case. fileHash is a deterministic tiebreaker.
            .sortedWith(
                compareByDescending<FavoriteGifModel> { it.addedListVersion }
                    .thenBy { it.fileHash }
            )
            .map { it.toUiItem() }
    }

    companion object {
        /** tempHash prefixes: keep a pending placeholder key distinct from the real server fileHash. */
        const val REMOTE_TEMP_PREFIX = "giphy:"
        const val MSG_TEMP_PREFIX = "msg:"

        /**
         * Base for optimistic `addedListVersion` keys (add / bump-to-top). It sits far above any real
         * server listVersion (which is small) so optimistic rows always sort on top, and leaves 1e12
         * headroom below Long.MAX_VALUE so [nextTopVersion]'s incrementing seq can never overflow.
         */
        const val TOP_VERSION_BASE = Long.MAX_VALUE - 1_000_000_000_000L

        /**
         * Rebuild a [PendingSource] from the persisted JSON column. Confirmed rows -> None; otherwise
         * parse [FavoriteGifModel.pendingSourceJson]; legacy #999 pending rows (pre-JSON) have it null
         * but [FavoriteGifModel.sourceUrl] set -> Remote (backward-compat, design §7).
         */
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
