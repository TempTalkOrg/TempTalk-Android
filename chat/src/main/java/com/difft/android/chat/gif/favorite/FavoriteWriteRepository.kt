package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.FilePathManager
import com.difft.android.chat.media.FavoriteEncryptedAttachmentProvider
import com.difft.android.chat.util.FileEncryptionUtil
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.BaseResponse
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.FavoriteAction
import com.difft.android.network.responses.FavoriteItemMeta
import com.difft.android.network.responses.FavoritesPutRequest
import com.difft.android.network.responses.FavoritesResponse
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toModel
import com.difft.android.chat.gif.GifSendInput
import com.difft.android.chat.gif.GifSendUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.FavoriteGifModel
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a favorite() call surfaced to the ViewModel. */
sealed interface FavResult {
    data object Ok : FavResult
    data class CapReached(val onEvictOldest: suspend () -> Unit) : FavResult
    /** CAS exhausted: optimistic row kept (pending), will sync once back online. */
    data object SyncDeferred : FavResult
    /** Server permanently rejected the write (e.g. invalid param): optimistic row rolled back. */
    data object Rejected : FavResult
}

/** Thrown when putWithCas exhausts its bounded retries on persistent CAS conflict (transient — keep pending). */
class CasExhaustedException : RuntimeException("favorites CAS retries exhausted")

/**
 * Thrown when the server permanently rejects the PUT (non-conflict non-success, e.g. status=1
 * invalid param). PERMANENT — the optimistic row must be rolled back, not kept pending (otherwise
 * it leaks as a stale "pending" residual that never syncs).
 */
class FavoritePermanentRejectException(status: Int) : RuntimeException("favorites PUT rejected status=$status")

/**
 * Write path of favorites: favorite / unfavorite / ensureFavKey / putWithCas /
 * reset / rewrap. Split from the read path to stay under the 500-line limit.
 *
 * v2 key model: favKey is a stable random DEK wrapped under KEK=HKDF(aci identity private key) and
 * stored server-side as wrappedFavKey. Any device holding the account identity derives the same KEK
 * and unwraps favKey — no per-device distribution. ensureFavKey is "unwrap-first": local cache →
 * else GET wrappedFavKey and unwrap → else first-create. reset (identity re-registration) and
 * rewrap (identity rotation) are primary-device only; both are driven by the identity layer.
 */
@Singleton
class FavoriteWriteRepository @Inject constructor(
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    private val urlManager: UrlManager,
    private val syncRepo: FavoriteSyncRepository,
    private val keyRepo: FavoriteKeyRepo,
    private val assetUploader: FavoriteAssetUploader,
    private val encryptionDataManager: EncryptionDataManager,
    private val userManager: UserManager,
    private val gifSendUseCase: GifSendUseCase,
    @param:AppCoroutineScope private val appScope: CoroutineScope
) {
    private val favoritesUrl: String get() = urlManager.gifs + "v1/gifs/favorites"
    // The `/gifs/` route requires token auth (JWT/microToken); the Basic baseAuth fallback gets 401.
    private fun token(): String = userManager.getUserData()?.microToken ?: ""

    // Monotonic placeholder seq so optimistic items sort to the very front (highest first).
    private val optimisticSeq = AtomicLong(0L)

    // tempHashes with a resolveAndConfirm in flight. favoriteOptimistic launches one on appScope;
    // flushPendingFavorites (on a fast favorites-tab open) sees the still-pending placeholder and
    // would launch a SECOND for the same tempHash — duplicate download + trans-store + CAS PUT (and a
    // possible CAS conflict/retry) for the same item. A concurrent set skips the redundant second run.
    private val resolvingTempHashes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Favorite a gif. Optimistically caches the item (pending=true, shown immediately), trans-stores
     * the asset, ensures the favKey, appends to the list, encrypts, and CAS-PUTs. On success the
     * optimistic row is backfilled with the server listVersion and pending=false.
     */
    suspend fun favorite(file: File, width: Int, height: Int): FavResult {
        // 0. Cap pre-check (confirm-first): if already at the limit, surface CapReached so the UI can
        //    ask the user before evicting. Done BEFORE trans-store to avoid a wasted upload on cancel.
        //    onEvictOldest only removes the oldest; the ViewModel re-runs the favorite afterwards.
        if (syncRepo.confirmedRecords().size >= MAX_FAVORITES) {
            return FavResult.CapReached(onEvictOldest = {
                syncRepo.confirmedRecords().minByOrNull { it.addedListVersion }
                    ?.let { unfavorite(it.attachment.fileHash) }
            })
        }

        // 1. Trans-store the asset (account-level, fast-pass on hit).
        val pointer = assetUploader.transStore(file, width, height)

        // 1b. Seed the favorites ciphertext cache so the just-favorited item renders instantly without
        //     a download round-trip (FavoriteGifLoader keys the cache by attachmentId).
        seedFavoritesCache(file, pointer.id, pointer.key)

        // 2. Optimistic cache row (sorts to the top; pending until CAS confirms).
        val placeholderVersion = Long.MAX_VALUE - optimisticSeq.getAndIncrement()
        syncRepo.upsert(pointer.toPendingModel(placeholderVersion))

        // 3. Ensure favKey (unwrap-first). If it cannot be established (e.g. identity not ready),
        //    keep the optimistic row pending and let a later favorites-tab open flush it.
        val firstCreate = ensureFavKey()
        if (!keyRepo.hasKey()) {
            L.i { "[FavoriteWrite] favorite kept pending (no favKey) fileHash=${pointer.fileHash}" }
            return FavResult.SyncDeferred
        }

        // 3b. Pull the current server list + version right before the CAS. We do NOT trust an in-memory
        //     listVersion across time (it defaults to 0 and is only set by a prior reconcile / PUT), so
        //     a favorite from a path that never opened the favorites tab (e.g. a chat message) would
        //     otherwise CAS-PUT the stale listVersion=0 and be rejected "invalid param". Pulling the
        //     whole list (not just the version) also keeps the PUT blob from clobbering concurrent
        //     cross-device edits. (Skipped after first-create: ensureFavKey already fetched the meta and
        //     the list is new.)
        if (!firstCreate) syncRepo.pullAndDecrypt()

        // 4. CAS PUT with the new record appended. Carry wrappedFavKey whenever the server still lacks
        //    it (first-create, or a prior first-create whose upload failed), so it self-heals.
        val record = FavoriteRecord(pointer, syncRepo.cachedListVersion)
        return try {
            val newVersion = putWithCas(FavoriteAction.FAVORITE, record, wrappedFavKey = wrappedFavKeyForPut(firstCreate))
            // Backfill the optimistic row with the server-arbitrated listVersion + confirm.
            syncRepo.upsert(pointer.toConfirmedModel(newVersion))
            FavResult.Ok
        } catch (e: FavoritePermanentRejectException) {
            // Permanent server reject (will never succeed) — roll back the optimistic row so it
            // doesn't leak as a stale pending residual that pollutes the list.
            L.w { "[FavoriteWrite] favorite permanently rejected, rolled back fileHash=${pointer.fileHash}: ${e.message}" }
            syncRepo.removeByFileHash(pointer.fileHash)
            FavResult.Rejected
        } catch (e: CasExhaustedException) {
            // Keep the optimistic row (pending); it re-syncs on the next pull / panel-open.
            L.w { "[FavoriteWrite] favorite CAS exhausted, kept optimistic fileHash=${pointer.fileHash}" }
            FavResult.SyncDeferred
        }
    }

    /**
     * Optimistic panel/search favorite. Returns immediately after inserting a placeholder pending row
     * (top of the list, shown instantly + toast). The real work — download the preview, trans-store,
     * CAS PUT — runs on [appScope] so it survives the panel/fragment closing.
     *
     * This OWNS the placeholder lifecycle (it does NOT call [favorite], which would insert a SECOND
     * pending row keyed by the real fileHash → a duplicate). On success it atomically swaps the
     * placeholder for the confirmed real-fileHash row; on permanent reject / error it silently rolls
     * the placeholder back; on transient CAS exhaustion it leaves the placeholder pending so
     * [flushPendingFavorites] retries it.
     */
    suspend fun favoriteOptimistic(giphyId: String, previewUrl: String, width: Int, height: Int) {
        val tempHash = "giphy:$giphyId"
        // 1. Placeholder shown immediately (survives page close + refresh; never wiped by a pull).
        syncRepo.upsertPlaceholder(tempHash, previewUrl, width, height)
        // 2. Real work in the background (app-scoped, so closing the panel doesn't cancel it).
        appScope.launch {
            resolveAndConfirm(tempHash, previewUrl, width, height)
        }
    }

    /**
     * Background body of an optimistic favorite (also reused to flush a placeholder pending row):
     * resolve the preview (cache hit or download) → trans-store → ensure favKey → reconcile →
     * CAS PUT. On success swap the placeholder for the confirmed row; on permanent reject / error
     * roll it back; on CAS exhaustion leave it pending for a later flush.
     */
    private suspend fun resolveAndConfirm(
        tempHash: String,
        previewUrl: String,
        width: Int,
        height: Int
    ) {
        // Coalesce concurrent runs for the same placeholder: favoriteOptimistic's appScope task and a
        // flushPendingFavorites on a fast favorites-tab open would otherwise both resolve/upload/PUT
        // the same tempHash. add() returns false if one is already in flight — skip the duplicate.
        if (!resolvingTempHashes.add(tempHash)) {
            L.i { "[FavoriteWrite] optimistic resolve already in flight, skipping duplicate tempHash=$tempHash" }
            return
        }
        try {
            val uri = gifSendUseCase.resolveSendable(GifSendInput.FromUrl(previewUrl, width, height))
            val realFile = File(uri.path ?: throw IllegalStateException("resolved uri has no path"))
            // Trans-store (account-level encrypted attachment) + seed its ciphertext cache so the
            // confirmed cell renders without a re-download.
            val pointer = assetUploader.transStore(realFile, width, height)
            seedFavoritesCache(realFile, pointer.id, pointer.key)
            // Ensure the favKey (unwrap-first / first-create). If it can't be established, leave the
            // placeholder pending — a later favorites-tab open flushes it.
            val firstCreate = ensureFavKey()
            if (!keyRepo.hasKey()) {
                L.i { "[FavoriteWrite] optimistic kept pending (no favKey) tempHash=$tempHash" }
                return
            }
            // Pull the current server list + version right before the CAS (see favorite()).
            if (!firstCreate) syncRepo.pullAndDecrypt()
            val record = FavoriteRecord(pointer, syncRepo.cachedListVersion)
            try {
                val newVersion = putWithCas(FavoriteAction.FAVORITE, record, wrappedFavKey = wrappedFavKeyForPut(firstCreate))
                // Swap: delete placeholder + upsert the confirmed real-fileHash row atomically. The
                // server-arbitrated newVersion is the highest (re-favorite bumps to the top via
                // applyAction), so the row stays on top and won't reorder on the next pull. dedup: if a
                // row for this real fileHash already existed, insertOrReplace keeps a single copy.
                val confirmed = FavoriteRecord(pointer, newVersion).toModel().apply { pending = false }
                if (!syncRepo.confirmPlaceholder(tempHash, confirmed)) {
                    // The user unfavorited the placeholder while this background write was in flight, so
                    // the swap was skipped (no local resurrection). The server FAVORITE PUT above already
                    // landed, though, so issue a compensating server-side unfavorite — otherwise the
                    // removed gif reappears on the next pull / on other devices. Driven straight from the
                    // pointer (there is no local cached row to look up: the placeholder is gone and the
                    // confirmed row was intentionally not inserted).
                    L.i { "[FavoriteWrite] optimistic confirm skipped (unfavorited mid-flight), compensating unfavorite tempHash=$tempHash fileHash=${pointer.fileHash}" }
                    // The ciphertext seeded above (keyed by pointer.id) now has no cache row referencing
                    // it (the confirmed row was intentionally not inserted), so it would leak on disk —
                    // delete it. Best-effort.
                    runCatching { FavoriteEncryptedAttachmentProvider.encryptedFile(pointer.id).delete() }
                    compensateUnfavorite(pointer)
                }
            } catch (e: FavoritePermanentRejectException) {
                L.w { "[FavoriteWrite] optimistic permanently rejected, rolled back tempHash=$tempHash: ${e.message}" }
                syncRepo.removeByTempHash(tempHash)
            } catch (e: CasExhaustedException) {
                // Transient: leave the placeholder pending; flushPendingFavorites retries it.
                L.w { "[FavoriteWrite] optimistic CAS exhausted, kept placeholder tempHash=$tempHash" }
            }
        } catch (e: Exception) {
            // Transient failure (offline download / trans-store / pull, etc.): KEEP the placeholder
            // pending — a later favorites-tab open flushes it, so an offline favorite still syncs once
            // back online. A permanent SERVER reject is the inner catch above (rolled back there); this
            // path only bounds transient failures, capped by the pendingSince TTL in flushPendingFavorites
            // so it can't linger forever as a zombie that shows locally but never reaches the server.
            L.w { "[FavoriteWrite] optimistic favorite deferred (kept pending) tempHash=$tempHash: ${e.stackTraceToString()}" }
        } finally {
            resolvingTempHashes.remove(tempHash)
        }
    }

    /** Unfavorite by fileHash: optimistic remove, then CAS PUT (action=unfavorite). */
    suspend fun unfavorite(fileHash: String) {
        val existing = syncRepo.allCached().firstOrNull { it.fileHash == fileHash } ?: return
        // Pending placeholder (optimistic panel/search favorite, not yet uploaded): it was never on the
        // server, so there is nothing to CAS-unfavorite — a record built from its empty attachmentId/key
        // and temp "giphy:<id>" fileHash would be a malformed no-op PUT. Just drop it locally. If its
        // background resolveAndConfirm is still in flight, that task's confirmPlaceholder finds the
        // placeholder gone and issues a compensating server unfavorite once its FAVORITE PUT lands.
        if (existing.pending && existing.attachmentId.isNullOrEmpty()) {
            syncRepo.removeByTempHash(fileHash)
            L.i { "[FavoriteWrite] unfavorite pending placeholder (local-only) tempHash=$fileHash" }
            return
        }
        if (!keyRepo.hasKey()) {
            // No key to CAS — remove locally only; a later pull reconciles with the server.
            syncRepo.removeByFileHash(fileHash)
            L.i { "[FavoriteWrite] unfavorite local-only (no favKey) fileHash=$fileHash" }
            return
        }
        // Pull the current list + version BEFORE the optimistic remove (see favorite()): a stale
        // listVersion=0 CAS is rejected as "invalid param". pullAndDecrypt re-pulls the server list
        // (which still holds this item), so remove AFTER it — else the item is re-added and, since
        // unfavorite has no post-CAS re-remove, would never disappear locally.
        syncRepo.pullAndDecrypt()
        syncRepo.removeByFileHash(fileHash)
        val record = with(FavoriteSyncRepository.Companion) { existing.toRecord() }
        try {
            putWithCas(FavoriteAction.UNFAVORITE, record, wrappedFavKey = null)
        } catch (e: FavoritePermanentRejectException) {
            // Local row already removed; the server still has it, so the next pull restores it.
            L.w { "[FavoriteWrite] unfavorite permanently rejected fileHash=$fileHash: ${e.message}" }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteWrite] unfavorite CAS exhausted fileHash=$fileHash" }
        }
    }

    /**
     * Server-side-only unfavorite of a just-confirmed pointer, used when the local placeholder was
     * removed (user unfavorited it) while the optimistic FAVORITE PUT was in flight: the server now
     * holds the item but the local list must not, so undo it server-side without touching the local
     * cache (there is nothing local to remove). Driven straight from the pointer — [unfavorite] can't
     * be reused because it early-returns on a missing local cache row (which is exactly the case here).
     * Best-effort — a transient failure leaves the item on the server, and the next favorites-tab pull
     * surfaces it (a manual remove then reconciles).
     */
    private suspend fun compensateUnfavorite(pointer: FavoriteAttachmentPointer) {
        if (!keyRepo.hasKey()) return
        // Re-pull the current server list + version so the CAS carries a fresh listVersion (a stale
        // one is rejected "invalid param") and the replay removes THIS item from the latest list.
        syncRepo.pullAndDecrypt()
        val record = FavoriteRecord(pointer, syncRepo.cachedListVersion)
        try {
            putWithCas(FavoriteAction.UNFAVORITE, record, wrappedFavKey = null)
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteWrite] compensating unfavorite permanently rejected fileHash=${pointer.fileHash}: ${e.message}" }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteWrite] compensating unfavorite CAS exhausted fileHash=${pointer.fileHash}" }
        }
    }

    /**
     * Retry every optimistic pending row (created when a prior favorite hit a transient failure or
     * had no key yet). Called on favorites-tab open once the favKey is available, so a pending add
     * can NEVER leak forever: success -> confirmed (pending cleared); permanent reject -> rolled
     * back; still-transient -> left pending for the next open. No-op when there are no pending rows.
     */
    suspend fun flushPendingFavorites() {
        if (!keyRepo.hasKey()) return
        val pending = syncRepo.allCached().filter { it.pending }
        if (pending.isEmpty()) return
        L.i { "[FavoriteWrite] flushPendingFavorites count=${pending.size}" }
        val now = System.currentTimeMillis()
        for (row in pending) {
            // TTL backstop: a pending row that has failed to sync for longer than MAX_PENDING_AGE_MS is
            // given up (removed), so a transient failure can't linger forever as a zombie that shows
            // locally but never reaches the server. Guarded on pendingSince > 0 so legacy rows created
            // before this field existed are retried, not reaped.
            if (row.pendingSince > 0 && now - row.pendingSince > MAX_PENDING_AGE_MS) {
                L.w { "[FavoriteWrite] flush: pending row expired (TTL), giving up fileHash=${row.fileHash} ageMs=${now - row.pendingSince}" }
                syncRepo.removeByFileHash(row.fileHash)
                continue
            }
            // A placeholder row (panel/search optimistic) has no real attachment yet — its record has
            // no id/key/digest, so re-run the background resolve+transStore+PUT keyed by its temp hash.
            val sourceUrl = row.sourceUrl
            if (sourceUrl != null) {
                resolveAndConfirm(row.fileHash, sourceUrl, row.width, row.height)
                continue
            }
            val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
            try {
                // Carry wrappedFavKey if the server still lacks it (recovers a failed first-create upload).
                val newVersion = putWithCas(FavoriteAction.FAVORITE, record, wrappedFavKey = wrappedFavKeyForPut())
                syncRepo.upsert(record.attachment.toConfirmedModel(newVersion))
            } catch (e: FavoritePermanentRejectException) {
                L.w { "[FavoriteWrite] flush: permanent reject, rolled back fileHash=${row.fileHash}: ${e.message}" }
                syncRepo.removeByFileHash(row.fileHash)
            } catch (e: CasExhaustedException) {
                L.w { "[FavoriteWrite] flush: still deferred (kept pending) fileHash=${row.fileHash}" }
            }
        }
    }

    /**
     * Ensure a usable favKey is cached locally (unwrap-first, cross-platform §3.4):
     *  1. local cache present -> done.
     *  2. else GET wrappedFavKey and unwrap with the current KEK (HKDF of the aci identity) -> cache.
     *  3. else no wrappedFavKey on the server (never created) -> first-create: generate favKey,
     *     wrap it, cache; the wrapped key rides the first favorite PUT (returns true).
     *
     * A wrappedFavKey that the current KEK cannot unwrap means the account identity changed
     * (rotation → needs rewrap; re-registration → needs reset). Those are driven by the identity
     * layer (see [rewrapOnMasterKeyRotation] / [resetFavorites]), NOT auto-decided here, so this
     * just leaves no local key and the caller keeps the add pending.
     *
     * @return true if this call FIRST-CREATED the favKey (caller must carry wrappedFavKey on PUT).
     */
    suspend fun ensureFavKey(): Boolean {
        if (keyRepo.hasKey()) return false

        val data = syncRepo.getFavorites()
        if (data != null) syncRepo.applyServerMeta(data)
        val wrapped = data?.wrappedFavKey

        if (!wrapped.isNullOrEmpty()) {
            // Server has a wrapped key: try to unwrap with the current account identity KEK.
            val favKey = withContext(Dispatchers.IO) {
                val kek = deriveCurrentKek() ?: return@withContext null
                FavoriteCrypto.unwrapFavKey(kek, wrapped)
            }
            if (favKey != null) {
                keyRepo.save(FavoriteCrypto.keyId(favKey), favKey)
                L.i { "[FavoriteWrite] favKey unwrapped from server" }
            } else {
                // Current KEK can't unwrap — identity changed. rewrap/reset is identity-layer driven.
                L.w { "[FavoriteWrite] wrappedFavKey present but current KEK cannot unwrap (identity changed)" }
            }
            return false
        }

        // No wrapped key on the server: first-create.
        firstCreateKey()
        return true
    }

    /**
     * Reset favorites (primary-device identity re-registration, old key unrecoverable): generate a
     * NEW favKey + a NEW wrappedFavKey, then PUT action=reset. The server unpins all previously
     * pinned attachments + GCs them, then pins these items (empty => cleared) and stores the new
     * blob/keyId/wrappedFavKey. The old list is unrecoverable, so the new list starts from whatever
     * is still locally cached (re-seedable — the attachment pointers are independent of favKey).
     */
    suspend fun resetFavorites() {
        firstCreateKey() // new favKey + new wrappedFavKey cached locally
        // Seed the new list from ALL locally-cached rows (confirmed + pending). Re-number
        // addedListVersion 0..n-1 (oldest -> newest) so the reset list has a clean, monotonic
        // sort key (old keys may be collapsed/placeholder).
        val seed = syncRepo.allCached() // newest-first
            .reversed()                 // oldest-first
            .mapIndexed { index, row ->
                val rec = with(FavoriteSyncRepository.Companion) { row.toRecord() }
                FavoriteRecord(rec.attachment, index.toLong())
            }
        try {
            putWholeList(seed, FavoriteAction.RESET, wrappedFavKey = currentWrappedFavKey())
            seed.forEach { syncRepo.upsert(it.attachment.toConfirmedModel(it.addedListVersion)) }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteWrite] resetFavorites CAS exhausted" }
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteWrite] resetFavorites permanently rejected: ${e.message}" }
        }
        L.i { "[FavoriteWrite] reset favorites seed=${seed.size}" }
    }

    /**
     * Re-wrap the favKey under the NEW identity KEK and PUT action=rewrap (wrappedFavKey column only,
     * no listVersion, no CAS). The list itself (blob) is preserved.
     *
     * The favKey is sourced in priority order:
     *  1. the locally-cached favKey — the rotating primary almost always has it, and using it also
     *     HEALS a server wrappedFavKey left stale under an even OLDER identity (the old private key
     *     may no longer be on the device, e.g. after a previous rotation that failed to rewrap);
     *  2. otherwise unwrap the server wrappedFavKey with [oldPriv]'s KEK.
     * No-op if the server has no wrappedFavKey, or the favKey is unavailable both ways.
     *
     * Wired into the two aci identity-rotation points: explicit key reset (PrivacySettingFragment)
     * and passive re-login where the favKey is still recoverable (via [onPrimaryLogin]).
     */
    suspend fun rewrapOnMasterKeyRotation(oldPriv: ByteArray?, newPriv: ByteArray) {
        val data = syncRepo.getFavorites()
        if (data != null) syncRepo.applyServerMeta(data)
        val wrapped = data?.wrappedFavKey
        if (wrapped.isNullOrEmpty()) {
            L.i { "[FavoriteWrite] rewrap skipped: no wrappedFavKey on server" }
            return
        }
        val rewrapped = withContext(Dispatchers.IO) {
            val favKey = keyRepo.getFavKey()?.favKey
                ?: oldPriv?.let { FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(it), wrapped) }
            if (favKey == null) {
                L.w { "[FavoriteWrite] rewrap: no cached favKey and old KEK cannot unwrap, abort" }
                return@withContext null
            }
            // Cache the favKey locally so subsequent ops don't re-derive it.
            keyRepo.save(FavoriteCrypto.keyId(favKey), favKey)
            FavoriteCrypto.wrapFavKey(FavoriteCrypto.deriveKek(newPriv), favKey)
        } ?: return
        try {
            putRewrap(rewrapped)
            L.i { "[FavoriteWrite] rewrap done" }
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteWrite] rewrap permanently rejected: ${e.message}" }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteWrite] rewrap CAS exhausted (unexpected — rewrap has no CAS)" }
        }
    }

    /**
     * Primary-device login decision (design §3.4). The login flow just generated a FRESH aci
     * identity and overwrote the previous one, so decide what happens to the favKey:
     *  - Server has no wrappedFavKey -> fresh account, nothing to do (first-create is lazy on panel open).
     *  - The new identity KEK already unwraps it -> nothing to do (identity effectively unchanged).
     *  - The favKey is still RECOVERABLE (locally cached, or the old identity survived login and can
     *    unwrap the server key) -> rewrap under the new identity so the list is PRESERVED.
     *  - Otherwise (favKey unrecoverable: no cache AND old key gone) -> reset: the old list is
     *    unrecoverable, so start a fresh favKey (server unpins + GCs the old attachments).
     *
     * Best-effort; callers wrap this so a failure never blocks login.
     *
     * @param oldPriv previous aci identity private key captured BEFORE login overwrote it, or null
     *                if none was available (fresh install / cleared storage). Must be captured by the
     *                caller before the new identity is written — it is unrecoverable afterwards.
     * @param newPriv the freshly generated aci identity private key (current identity).
     */
    suspend fun onPrimaryLogin(oldPriv: ByteArray?, newPriv: ByteArray) {
        val data = syncRepo.getFavorites()
        if (data != null) syncRepo.applyServerMeta(data)
        val wrapped = data?.wrappedFavKey
        if (wrapped.isNullOrEmpty()) {
            L.i { "[FavoriteWrite] primary login: server has no favorites, nothing to do" }
            return
        }
        val decision = withContext(Dispatchers.IO) {
            // Already unwrappable under the new identity (rare — e.g. re-login kept the same key).
            if (FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(newPriv), wrapped) != null) {
                return@withContext Decision.NONE
            }
            // favKey still recoverable (locally cached, or the old identity survived and can unwrap)
            // -> preserve the list by re-wrapping under the new identity.
            val recoverable = keyRepo.hasKey() ||
                (oldPriv != null && FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(oldPriv), wrapped) != null)
            if (recoverable) Decision.REWRAP else Decision.RESET
        }
        when (decision) {
            Decision.NONE -> L.i { "[FavoriteWrite] primary login: new KEK already unwraps, no action" }
            Decision.REWRAP -> {
                L.i { "[FavoriteWrite] primary login: favKey recoverable -> rewrap (list preserved)" }
                rewrapOnMasterKeyRotation(oldPriv, newPriv)
            }
            Decision.RESET -> {
                L.i { "[FavoriteWrite] primary login: favKey unrecoverable -> reset (list cleared)" }
                resetFavorites()
            }
        }
    }

    private enum class Decision { NONE, REWRAP, RESET }

    /** Generate a fresh favKey, wrap it under the current KEK, and cache it locally. */
    private suspend fun firstCreateKey() = withContext(Dispatchers.IO) {
        val favKey = FavoriteCrypto.generateFavKey()
        keyRepo.save(FavoriteCrypto.keyId(favKey), favKey)
    }

    /** Derive the KEK from the current account identity private key, or null if unavailable. */
    private fun deriveCurrentKek(): ByteArray? = try {
        val priv = encryptionDataManager.getAciIdentityKey().privateKey.serialize()
        FavoriteCrypto.deriveKek(priv)
    } catch (e: Exception) {
        L.w { "[FavoriteWrite] cannot derive KEK: ${e.message}" }
        null
    }

    /** Wrap the currently-cached favKey under the current KEK, for a PUT that carries wrappedFavKey. */
    private suspend fun currentWrappedFavKey(): String? = withContext(Dispatchers.IO) {
        val key = keyRepo.getFavKey() ?: return@withContext null
        val kek = deriveCurrentKek() ?: return@withContext null
        FavoriteCrypto.wrapFavKey(kek, key.favKey)
    }

    /**
     * The wrappedFavKey to attach to a favorite/flush PUT: carry it whenever the server has no key yet
     * — a first-create, OR a prior first-create whose upload failed (local favKey exists but the server
     * keyId is still empty). Without this the server keeps rejecting every favorite as "invalid param"
     * (blob + keyId but no wrappedFavKey), so favoriting would be permanently stuck. Null once the
     * server already holds the wrapped key. Callers must reconcile (serverKeyId is fresh) before this.
     */
    internal suspend fun wrappedFavKeyForPut(firstCreate: Boolean = false): String? =
        if (firstCreate || syncRepo.serverKeyId.isNullOrEmpty()) currentWrappedFavKey() else null

    /**
     * Bounded CAS retry: on conflict re-pull, REPLAY this add/remove onto the latest list,
     * re-encrypt, and re-PUT. Exponential backoff. Throws [CasExhaustedException] after MAX retries.
     *
     * @return the server-arbitrated listVersion on success.
     */
    suspend fun putWithCas(action: String, item: FavoriteRecord, wrappedFavKey: String?): Long {
        var listVersion = syncRepo.cachedListVersion
        var records = syncRepo.confirmedRecords()
        repeat(MAX_CAS_RETRIES) { attempt ->
            val merged = applyAction(action, records, item)
            val capped = enforceCapInternal(merged)
            val resp = putRequest(capped.records, listVersion, action, wrappedFavKey)
            when {
                resp.isSuccess() -> {
                    val data = resp.data
                    if (data != null) syncRepo.applyServerMeta(data)
                    return data?.listVersion ?: (listVersion + 1)
                }
                isConflict(resp) -> {
                    if (attempt < MAX_CAS_RETRIES - 1) {
                        // Re-pull: decrypt + recalibrate the confirmed cache, then replay this op.
                        syncRepo.pullAndDecrypt()
                        listVersion = syncRepo.cachedListVersion
                        records = syncRepo.confirmedRecords()
                        delay(BACKOFF_BASE_MS * (1L shl attempt)) // 100/200/400ms
                    }
                }
                else -> {
                    // Non-conflict non-success = a permanent server rejection (e.g. invalid param).
                    // Distinct from CAS exhaustion: the caller must roll back the optimistic row, not
                    // keep it pending (a never-syncing pending row leaks).
                    L.w { "[FavoriteWrite] putWithCas permanent reject status=${resp.status}" }
                    throw FavoritePermanentRejectException(resp.status)
                }
            }
        }
        throw CasExhaustedException()
    }

    /**
     * PUT the whole [records] list with bounded CAS retry (used by reset). On a version conflict,
     * refresh the CAS listVersion and re-PUT — the whole-list payload is unchanged (reset replaces
     * the list regardless of old contents) and we do NOT decrypt (we may not hold the old key).
     */
    private suspend fun putWholeList(records: List<FavoriteRecord>, action: String, wrappedFavKey: String?): Long {
        repeat(MAX_CAS_RETRIES) { attempt ->
            val resp = putRequest(records, syncRepo.cachedListVersion, action, wrappedFavKey)
            when {
                resp.isSuccess() -> {
                    resp.data?.let { syncRepo.applyServerMeta(it) }
                    return resp.data?.listVersion ?: syncRepo.cachedListVersion
                }
                isConflict(resp) -> {
                    if (attempt < MAX_CAS_RETRIES - 1) {
                        syncRepo.getFavorites()?.let { syncRepo.applyServerMeta(it) } // refresh version only
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

    /** PUT action=rewrap: updates only the wrappedFavKey column (no listVersion, no CAS). */
    private suspend fun putRewrap(wrappedFavKey: String) = withContext(Dispatchers.IO) {
        val resp = httpClient.httpService.putFavorites(
            token(),
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
        listVersion: Long,
        action: String,
        wrappedFavKey: String?
    ): BaseResponse<FavoritesResponse> = withContext(Dispatchers.IO) {
        val key = keyRepo.getFavKey() ?: throw CasExhaustedException()
        val blob = FavoriteCrypto.encrypt(key.favKey, FavoriteListPlain(records))
        val items = records.map {
            FavoriteItemMeta(it.attachment.id, it.attachment.authorizeId.toString(), it.attachment.fileHash)
        }
        // reset replaces the whole list (no CAS); favorite/unfavorite carry listVersion for CAS.
        val casVersion = if (action == FavoriteAction.RESET) null else listVersion
        httpClient.httpService.putFavorites(
            token(),
            favoritesUrl,
            FavoritesPutRequest(
                // Declare the scheme version WE encrypted with (v1), never echo the server's stored
                // value: the GET can return encVersion=0 (legacy/blank), and sending 0 with a v1
                // blob is rejected "invalid param".
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

    /** Apply a favorite/unfavorite to [records] (dedup by fileHash for favorite). */
    private fun applyAction(action: String, records: List<FavoriteRecord>, item: FavoriteRecord): List<FavoriteRecord> =
        if (action == FavoriteAction.FAVORITE) {
            // Re-favoriting an existing gif must bump it to the top: drop any prior copy of the same
            // fileHash, then append it with a fresh (highest) addedListVersion. (A naive distinctBy on
            // records + item keeps the OLD copy, so the item never moves to the front.)
            val bumped = item.copy(addedListVersion = nextAddedVersion(records))
            records.filterNot { it.attachment.fileHash == bumped.attachment.fileHash } + bumped
        } else {
            records.filterNot { it.attachment.fileHash == item.attachment.fileHash }
        }

    private fun nextAddedVersion(records: List<FavoriteRecord>): Long =
        (records.maxOfOrNull { it.addedListVersion } ?: syncRepo.cachedListVersion) + 1

    /** Cap enforcement result: capped list + how many were dropped by overflow. */
    private data class CapResult(val records: List<FavoriteRecord>, val dropped: Int)

    /**
     * Enforce the 200 cap by FIFO-evicting the oldest (lowest addedListVersion). Note: confirmed
     * blob records carry no pending flag, so this operates on confirmed records only — the pending
     * skip is enforced at the cache layer (optimistic rows are not in [records]).
     */
    private fun enforceCapInternal(records: List<FavoriteRecord>): CapResult {
        if (records.size <= MAX_FAVORITES) return CapResult(records, 0)
        val kept = records.sortedByDescending { it.addedListVersion }.take(MAX_FAVORITES)
        return CapResult(kept, records.size - kept.size)
    }

    private fun isConflict(resp: BaseResponse<FavoritesResponse>): Boolean =
        resp.status == FAVORITES_STATUS_CAS_CONFLICT

    /**
     * Encrypt the just-favorited local gif into the favorites ciphertext cache (keyed by
     * [attachmentId], same `[IV16][AES-CBC][HMAC32]` at-rest format as the downloaded copy) so
     * [FavoriteGifLoader] gets a cache hit and skips the download — without ever caching plaintext.
     * Best-effort: a failure here only means the gif is fetched on demand later.
     */
    private suspend fun seedFavoritesCache(file: File, attachmentId: String, key: ByteArray) = withContext(Dispatchers.IO) {
        if (attachmentId.isEmpty() || key.size < 64) return@withContext
        try {
            val encFile = FavoriteEncryptedAttachmentProvider.encryptedFile(attachmentId)
            if (encFile.exists() && encFile.length() > 0) return@withContext
            if (file.exists()) FileEncryptionUtil.encryptFile(file, encFile, key)
        } catch (e: Exception) {
            L.w { "[FavoriteWrite] seed favorites cache failed attachmentId=$attachmentId: ${e.message}" }
        }
    }

    // -- model mappers --

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

        /** Give up a pending favorite that still hasn't synced after this long (3 days), so a transient
         *  failure can't linger forever as a local-only zombie. Checked on each flushPendingFavorites. */
        const val MAX_PENDING_AGE_MS = 3L * 24 * 60 * 60 * 1000

        /**
         * Server CAS-conflict status in the BaseResponse envelope: the request is HTTP 200 and the
         * conflict is signaled via the envelope status (`{"status":9,"reason":"listVersion conflict"}`),
         * not an HTTP code. On this status the client re-pulls the latest listVersion, replays the op,
         * and re-PUTs.
         */
        const val FAVORITES_STATUS_CAS_CONFLICT = 9
    }
}
