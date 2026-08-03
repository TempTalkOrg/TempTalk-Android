package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
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
    private val userManager: UserManager,
    private val keyLifecycle: FavoriteKeyLifecycle,
    // dagger.Lazy breaks the writeRepo <-> optimisticWriter cycle: optimisticWriter injects writeRepo
    // for the CAS core (putWithCas / refavorite); writeRepo only reaches back for the flush delegate.
    private val optimisticWriter: dagger.Lazy<FavoriteOptimisticWriter>
) {
    private val favoritesUrl: String get() = urlManager.gifs + "v1/gifs/favorites"
    // The `/gifs/` route requires token auth (JWT/microToken); the Basic baseAuth fallback gets 401.
    private fun token(): String = userManager.getUserData()?.microToken ?: ""

    /**
     * Favorite a gif. Optimistically caches the item (pending=true, shown immediately), trans-stores
     * the asset, ensures the favKey, appends to the list, encrypts, and CAS-PUTs. On success the
     * optimistic row is backfilled with the server listVersion and pending=false.
     */
    suspend fun favorite(file: File, width: Int, height: Int): FavResult {
        // 0. Cap pre-check (confirm-first): surface CapReached BEFORE trans-store (no wasted upload on
        //    cancel). Capture confirmedRecords ONCE for both the size check and the oldest eviction
        //    target (§12.5#9); onEvictOldest only removes the oldest, the ViewModel re-runs the favorite.
        val confirmed = syncRepo.confirmedRecords()
        if (confirmed.size >= MAX_FAVORITES) {
            val oldestFileHash = confirmed.minByOrNull { it.addedListVersion }?.attachment?.fileHash
            return FavResult.CapReached(onEvictOldest = {
                // Evict via the optimistic tombstone unfavorite (offline-safe + instant). Lazy avoids the
                // writeRepo <-> optimisticWriter Hilt cycle.
                oldestFileHash?.let { optimisticWriter.get().unfavorite(it) }
            })
        }

        // 1. Trans-store the asset (account-level, fast-pass on hit).
        val pointer = assetUploader.transStore(file, width, height)

        // 1b. Seed the favorites ciphertext cache so the just-favorited item renders instantly without
        //     a download round-trip (FavoriteGifLoader keys the cache by attachmentId).
        seedFavoritesCache(file, pointer.id, pointer.key)

        // 2. Optimistic cache row (sorts to the top; pending until CAS confirms). nextTopVersion() is a
        //    monotonically INCREASING key on a high base, so the newest add sorts to the very top.
        val placeholderVersion = syncRepo.nextTopVersion()
        syncRepo.upsert(pointer.toPendingModel(placeholderVersion))

        // 3. Ensure favKey (unwrap-first). If it cannot be established (e.g. identity not ready),
        //    keep the optimistic row pending and let a later favorites-tab open flush it.
        val firstCreate = keyLifecycle.ensureFavKey()
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
            val newVersion = putWithCas(FavoriteAction.FAVORITE, record, wrappedFavKey = keyLifecycle.wrappedFavKeyForPut(firstCreate))
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
     * Sync a pending RE-FAVORITE (a row [FavoriteSyncRepository.markPendingRefavorite] turned pending,
     * keeping its attachment): CAS a FAVORITE to bump it to the top server-side, then confirm it back to
     * a normal row. Offline / transient failure LEAVES it pending so flushPendingFavorites retries it
     * (its None-source branch runs the same CAS) — this is what makes an offline re-favorite durable
     * instead of being reverted by the next pull. Serialized on casMutex by the caller.
     */
    suspend fun reconfirm(fileHash: String) {
        if (!keyRepo.hasKey()) return // stays pending; a later flush (with a key) retries
        // Keyed single-row read: the pending re-favorite row carries the bare fileHash + its attachment.
        val row = syncRepo.firstByFileHash(fileHash)?.takeIf { it.pending && !it.attachmentId.isNullOrEmpty() } ?: return
        val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
        // Pull the current list + version before the CAS (replaceConfirmedCache PRESERVES pending rows,
        // so this row survives the pull); applyAction re-bumps it to the top of the blob.
        syncRepo.pullAndDecrypt()
        try {
            val newVersion = putWithCas(FavoriteAction.FAVORITE, record, wrappedFavKey = keyLifecycle.wrappedFavKeyForPut())
            syncRepo.upsert(record.attachment.toConfirmedModel(newVersion))
        } catch (e: FavoritePermanentRejectException) {
            // Never going to succeed: confirm it back to a normal row (drop the pending flag) at the
            // server's current version so it doesn't linger as a stuck pending row. The top-bump is lost
            // but the favorite itself remains.
            L.w { "[FavoriteWrite] reconfirm permanently rejected fileHash=$fileHash: ${e.message}" }
            syncRepo.upsert(record.attachment.toConfirmedModel(syncRepo.cachedListVersion))
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteWrite] reconfirm CAS exhausted, kept pending fileHash=$fileHash" }
        } catch (e: IOException) {
            // Offline / transient: KEEP it pending; flushPendingFavorites retries it once back online.
            L.w { "[FavoriteWrite] reconfirm transient failure, kept pending fileHash=$fileHash: ${e.message}" }
        }
    }

    /**
     * Sync an already-tombstoned (optimistically-unfavorited) confirmed favorite to the server: CAS
     * PUT (action=unfavorite) and, on success, hard-delete the local tombstone + its ciphertext. The
     * OPTIMISTIC state is now the tombstone (set by [FavoriteOptimisticWriter.unfavorite] before this
     * runs), so — unlike the old eager unfavorite — this does NO local remove up front. Offline /
     * transient failure KEEPS the tombstone (hidden locally, excluded from cap + blob); the next
     * favorites-tab open flush retries it. This is the fix for the original silent-fail bug: the local
     * hide already happened, and the network step here can throw without losing the removal.
     *
     * MUST run under the caller's casMutex (all callers wrap it): the two tombstone re-checks below only
     * hold against a concurrent re-favorite because that re-favorite's CAS is serialized on the same
     * mutex. The re-checks re-derive intent from the CURRENT tombstone flag — a re-favorite that cleared
     * the tombstone (before or during our network round-trip) means "keep the row", so we abort / skip
     * the hard-delete rather than delete a row the user just re-added.
     */
    suspend fun syncUnfavorite(record: FavoriteRecord) {
        val fileHash = record.attachment.fileHash
        // No key to CAS — tombstone waits (hidden locally); flushPendingFavorites syncs it once a key
        // exists. Do NOT hard-delete here: the server still holds the item, so a later pull would need
        // the tombstone to keep it hidden.
        if (!keyRepo.hasKey()) {
            L.i { "[FavoriteWrite] syncUnfavorite kept tombstone (no favKey) fileHash=$fileHash" }
            return
        }
        // Re-derive intent BEFORE the CAS: if a re-favorite cleared the tombstone while this task waited
        // on casMutex, there is nothing to remove — abort (no CAS, no delete). The re-favorite's own CAS
        // re-adds the item server-side.
        if (!syncRepo.hasTombstone(fileHash)) {
            L.i { "[FavoriteWrite] syncUnfavorite aborted, tombstone cleared (re-favorited) fileHash=$fileHash" }
            return
        }
        // Pull the current list + version BEFORE the CAS (see favorite()): a stale listVersion=0 CAS is
        // rejected as "invalid param". The pull re-inserts the server copy, but replaceConfirmedCache's
        // tombstone skip-set keeps this hash out of the cache, so it stays hidden.
        syncRepo.pullAndDecrypt()
        try {
            putWithCas(FavoriteAction.UNFAVORITE, record, wrappedFavKey = null)
            // Success server-side. Atomic check-and-delete: a re-favorite may have cleared the tombstone
            // while the UNFAVORITE CAS was in flight — removeIfTombstone deletes ONLY if the row is STILL
            // a tombstone (under syncMutex, so a re-favorite can't slip into a check/delete gap and get
            // destroyed); a re-favorited row is kept for its own FAVORITE CAS to re-add it server-side.
            if (syncRepo.removeIfTombstone(fileHash)) {
                L.i { "[FavoriteWrite] syncUnfavorite confirmed, tombstone removed fileHash=$fileHash" }
            } else {
                L.i { "[FavoriteWrite] syncUnfavorite CAS ok but re-favorited mid-flight, kept row fileHash=$fileHash" }
            }
        } catch (e: FavoritePermanentRejectException) {
            // Permanent reject (never succeeds): drop the tombstone anyway (else a permanently-hidden
            // zombie) — but only if it's STILL a tombstone (removeIfTombstone), so a mid-flight re-favorite
            // isn't destroyed. A later pull re-adds it only if the server still holds it (rare on reject).
            L.w { "[FavoriteWrite] syncUnfavorite permanently rejected, dropping tombstone fileHash=$fileHash: ${e.message}" }
            syncRepo.removeIfTombstone(fileHash)
        } catch (e: CasExhaustedException) {
            // Transient: KEEP the tombstone (hidden); flushPendingFavorites retries it.
            L.w { "[FavoriteWrite] syncUnfavorite CAS exhausted, kept tombstone fileHash=$fileHash" }
        } catch (e: IOException) {
            // Offline / transient pull failure: KEEP the tombstone; flush retries it once back online.
            L.w { "[FavoriteWrite] syncUnfavorite transient failure, kept tombstone fileHash=$fileHash: ${e.message}" }
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
        val cached = syncRepo.allCached()
        // Optimistic-unfavorite tombstones (confirmed rows marked pendingRemoval): retry the UNFAVORITE
        // CAS. NO TTL reap — reaping (hard-delete) would let the next pull resurrect the item; the ≤200
        // cap already bounds how many tombstones can accumulate. syncUnfavorite hard-deletes on success.
        val tombstones = cached.filter { it.pendingRemoval }
        if (tombstones.isNotEmpty()) {
            // Warm the cache + listVersion ONCE before the loop (design §12.5#6) so the skip-set and
            // CAS state are fresh for the batch; runCatching keeps an offline pull from aborting the
            // whole flush. syncUnfavorite still re-pulls per CAS for a correct per-op listVersion (the
            // CAS's own conflict-retry needs it), so this only avoids a cold first-iteration pull.
            runCatching { syncRepo.pullAndDecrypt() }
                .onFailure { L.w { "[FavoriteWrite] flush: pre-loop pull failed (continuing): ${it.message}" } }
        }
        for (row in tombstones) {
            val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
            // Serialize each tombstone sync against the background confirm / re-favorite / unfavorite
            // cycles (via the optimistic writer's casMutex): syncUnfavorite re-derives its intent from
            // the current tombstone flag under lock, so a concurrent re-favorite can't be clobbered.
            runCatching { optimisticWriter.get().runGuarded { syncUnfavorite(record) } }
                .onFailure { L.w { "[FavoriteWrite] flush: unfavorite tombstone deferred fileHash=${row.fileHash}: ${it.message}" } }
        }
        // Confirm OLDEST-first (ascending addedListVersion): each CAS PUT gets the next (increasing)
        // server listVersion, so confirming in add-order makes the server versions preserve that order —
        // the newest stays on top after confirm, matching the pending display. (allCached() is DESCENDING;
        // iterating it as-is would confirm newest-first → newest gets the lowest server version → the
        // whole batch flips order on sync.)
        val pending = cached.filter { it.pending }.sortedBy { it.addedListVersion }
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
            // A placeholder row (Remote panel/search or Message gif) has no real attachment yet — its
            // record has no id/key/digest, so re-run the background resolve keyed by its temp hash.
            when (row.toPendingSource()) {
                is PendingSource.Remote, is PendingSource.Message -> {
                    optimisticWriter.get().resolvePending(row.fileHash)
                    continue
                }
                // Confirmed-shaped pending: a re-favorite (markPendingRefavorite) that kept its real
                // attachment, or a legacy None row. It already has id/key/digest, so CAS its toRecord
                // directly below (no resolve). This is what makes an offline re-favorite durable.
                PendingSource.None -> { /* fall through to the toRecord path below */ }
            }
            val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
            try {
                // Carry wrappedFavKey if the server still lacks it (recovers a failed first-create upload).
                // Serialized on casMutex like the other background CAS cycles.
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

    /**
     * Ensure a usable favKey is cached locally (unwrap-first). Delegates to [FavoriteKeyLifecycle];
     * kept here as the public entry so callers (ViewModel, identity layer) don't need a second
     * dependency. @return true if this call FIRST-CREATED the favKey.
     */
    suspend fun ensureFavKey(): Boolean = keyLifecycle.ensureFavKey()

    /**
     * Reset favorites (primary-device identity re-registration, old key unrecoverable). Delegates to
     * [FavoriteKeyLifecycle]; kept here as the public entry for the identity layer.
     */
    suspend fun resetFavorites() = keyLifecycle.resetFavorites()

    /**
     * Re-wrap the favKey under the NEW identity KEK (wrappedFavKey column only). Delegates to
     * [FavoriteKeyLifecycle]; kept here as the public entry (PrivacySettingFragment / onPrimaryLogin).
     */
    suspend fun rewrapOnMasterKeyRotation(oldPriv: ByteArray?, newPriv: ByteArray) =
        keyLifecycle.rewrapOnMasterKeyRotation(oldPriv, newPriv)

    /**
     * Primary-device login rewrap-vs-reset decision (design §3.4). Delegates to
     * [FavoriteKeyLifecycle]; kept here as the public entry (LoginViewModel).
     */
    suspend fun onPrimaryLogin(oldPriv: ByteArray?, newPriv: ByteArray) =
        keyLifecycle.onPrimaryLogin(oldPriv, newPriv)

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
            // items = the single affected record (the favorite/unfavorite DELTA), NOT capped.records.
            val resp = putRequest(capped.records, listOf(item), listVersion, action, wrappedFavKey)
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
     * PUT the whole [records] list with bounded CAS retry (used by reset, driven from
     * [FavoriteKeyLifecycle]). On a version conflict, refresh the CAS listVersion and re-PUT — the
     * whole-list payload is unchanged (reset replaces the list regardless of old contents) and we do
     * NOT decrypt (we may not hold the old key).
     */
    internal suspend fun putWholeList(records: List<FavoriteRecord>, action: String, wrappedFavKey: String?): Long {
        repeat(MAX_CAS_RETRIES) { attempt ->
            // reset pins the whole seeded list, so items == records (empty => server clears all).
            val resp = putRequest(records, records, syncRepo.cachedListVersion, action, wrappedFavKey)
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

    /** PUT action=rewrap: updates only the wrappedFavKey column (no listVersion, no CAS). Driven from
     *  [FavoriteKeyLifecycle]. */
    internal suspend fun putRewrap(wrappedFavKey: String) = withContext(Dispatchers.IO) {
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
        itemRecords: List<FavoriteRecord>,
        listVersion: Long,
        action: String,
        wrappedFavKey: String?
    ): BaseResponse<FavoritesResponse> = withContext(Dispatchers.IO) {
        val key = keyRepo.getFavKey() ?: throw CasExhaustedException()
        // [blob] is the encrypted RESULTING list (the client's source of truth). [items] is the plaintext
        // DELTA the server pins/unpins for THIS action — favorite/unfavorite = just the affected record,
        // reset = the whole seeded list — NOT the resulting list (matches iOS DTGifFavoritesRepository).
        // Sending the resulting list as items breaks unfavorite of the LAST item: an empty items array is
        // rejected "invalid param", and for a non-last unfavorite it would mis-pin the survivors server-side.
        val blob = FavoriteCrypto.encrypt(key.favKey, FavoriteListPlain(records))
        val items = itemRecords.map {
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
