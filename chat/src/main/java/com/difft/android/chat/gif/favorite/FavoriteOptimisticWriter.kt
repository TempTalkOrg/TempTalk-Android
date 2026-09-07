package com.difft.android.chat.gif.favorite

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import com.difft.android.chat.gif.GifSendInput
import com.difft.android.chat.gif.GifSendUseCase
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.media.FavoriteEncryptedAttachmentProvider
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.MSG_TEMP_PREFIX
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.REMOTE_TEMP_PREFIX
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toModel
import com.difft.android.chat.gif.favorite.FavoriteSyncRepository.Companion.toPendingSource
import com.difft.android.network.responses.FavoriteAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optimistic (placeholder-first) favorite enqueue + background resolve, split out of
 * [FavoriteWriteRepository] (SRP + 500-line limit). Owns BOTH optimistic entries (Giphy panel /
 * message gif) plus the shared background resolver that trans-stores the asset, ensures the favKey,
 * reconciles, CAS-PUTs, and swaps the placeholder for the confirmed row.
 *
 * Both entries are placeholder-first: insert a pending row immediately (no network), then run the
 * real work on [appScope] so closing the panel / fragment doesn't cancel it. Offline / transient
 * failure leaves the placeholder pending; [FavoriteWriteRepository.flushPendingFavorites] retries it
 * on the next favorites-tab open (bounded by the 3-day pendingSince TTL).
 *
 * Depends on [FavoriteWriteRepository] for the shared CAS core (putWithCas / refavorite) — one-way,
 * no Hilt cycle (writeRepo reaches back via a dagger.Lazy for the flush delegate).
 */
@Singleton
class FavoriteOptimisticWriter @Inject constructor(
    private val syncRepo: FavoriteSyncRepository,
    private val keyRepo: FavoriteKeyRepo,
    private val keyLifecycle: FavoriteKeyLifecycle,
    private val assetUploader: FavoriteAssetUploader,
    private val gifSendUseCase: GifSendUseCase,
    private val ciphertextFetcher: FavoriteCiphertextFetcher,
    private val writeRepo: FavoriteWriteRepository,
    @param:AppCoroutineScope private val appScope: CoroutineScope
) {
    // tempHashes with a resolveAndConfirm in flight. Each optimistic entry launches one on appScope;
    // flushPendingFavorites (on a fast favorites-tab open) sees the still-pending placeholder and
    // would launch a SECOND for the same tempHash — duplicate download + trans-store + CAS PUT. A
    // concurrent set skips the redundant second run.
    private val resolvingTempHashes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Serializes ALL background favorite CAS cycles (the FAVORITE confirm, the deferred re-favorite,
     * and the UNFAVORITE sync) against each other so no two pull+CAS+local-commit sequences for any
     * content-addressed hash overlap. Without it, an in-flight [syncUnfavorite] hard-delete could race
     * a concurrent confirm/re-favorite that just re-created the same row (data-loss / resurrection).
     *
     * LOCK ORDER is always casMutex -> [FavoriteSyncRepository.syncMutex], NEVER the reverse:
     * a CAS cycle holds casMutex and, inside it, calls pull/confirm/flag-mutation methods that each
     * take syncMutex briefly; syncMutex-only paths (replaceConfirmedCache/confirmPlaceholder) never
     * reach back for casMutex, so the two can't deadlock. casMutex is non-reentrant, so a casMutex
     * body must never call another casMutex-guarded method.
     */
    private val casMutex = Mutex()

    /** Giphy panel optimistic favorite (moved from FavoriteWriteRepository, unchanged behavior). */
    suspend fun favoriteRemote(giphyId: String, previewUrl: String, width: Int, height: Int) {
        val tempHash = REMOTE_TEMP_PREFIX + giphyId
        // Placeholder shown immediately (survives page close + refresh; never wiped by a pull).
        syncRepo.upsertRemotePlaceholder(tempHash, previewUrl, width, height)
        // Real work in the background (app-scoped, so closing the panel doesn't cancel it).
        appScope.launch { resolveAndConfirm(tempHash) }
    }

    /**
     * Message gif optimistic favorite (favorite-without-download). Inserts a placeholder with NO
     * network and returns instantly; the background resolve derives the account fileHash, isExist
     * fast-passes, and only downloads the message ciphertext on a miss.
     */
    suspend fun favoriteMessage(ref: PendingSource.Message, width: Int, height: Int, size: Int, contentType: String) {
        val tempHash = MSG_TEMP_PREFIX + ref.accountFileHash
        // Re-favorite of an already-present favorite for the SAME content-addressed hash — either a
        // confirmed row OR a pending-removal tombstone (both are pending=false, so hasConfirmed matches
        // both). Turn it into a RETRYABLE pending re-favorite (markPendingRefavorite: pending=true +
        // fresh top sort key + clears any tombstone, keeping its attachment) instead of a local-only
        // bump. A local-only bump is NOT retryable — flushPendingFavorites only re-drives pending
        // placeholders / tombstones, so an offline re-favorite CAS that failed was never re-sent and the
        // next pull reverted the bump to the server order. As a pending row it flushes like any pending
        // add (None-source → putWithCas FAVORITE), so the server-side bump is durable and offline-safe.
        // Clearing the tombstone also makes an in-flight syncUnfavorite abort its hard-delete (its
        // under-casMutex hasTombstone re-check now sees false), so the row this keeps alive isn't yanked.
        if (syncRepo.hasConfirmed(ref.accountFileHash)) {
            L.i { "[FavoriteOptimistic] favorite message re-favorite existing hash=${ref.accountFileHash}" }
            syncRepo.markPendingRefavorite(ref.accountFileHash)
            appScope.launch {
                // Immediate CAS (online → instant sync); if it fails offline the row stays pending and
                // flushPendingFavorites retries it. Serialized on casMutex like the other CAS cycles.
                runCatching { casMutex.withLock { writeRepo.reconfirm(ref.accountFileHash) } }
                    .onFailure { L.w { "[FavoriteOptimistic] reconfirm deferred hash=${ref.accountFileHash}: ${it.message}" } }
            }
            return
        }
        syncRepo.upsertMessagePlaceholder(tempHash, ref, width, height, size, contentType)
        L.i { "[FavoriteOptimistic] favorite message enqueued msgId=${ref.messageId} hash=${ref.accountFileHash}" }
        appScope.launch { resolveAndConfirm(tempHash) }
    }

    /**
     * Re-run the background resolve for a pending placeholder by its temp hash (flush delegate). Self-
     * serializes on casMutex via [confirmOrCompensate], so the flush calls this DIRECTLY (not via
     * [runGuarded]) — wrapping it in runGuarded would re-enter casMutex and deadlock.
     */
    suspend fun resolvePending(tempHash: String) = resolveAndConfirm(tempHash)

    /**
     * Run [block] under [casMutex] on behalf of [FavoriteWriteRepository.flushPendingFavorites], which
     * lives in the write repo (no casMutex of its own) but must serialize its tombstone-sync / legacy
     * confirm CAS against the background confirm / re-favorite / unfavorite cycles this class owns. The
     * flush is never itself called from inside casMutex, so this can't self-deadlock (non-reentrant).
     */
    suspend fun <T> runGuarded(block: suspend () -> T): T = casMutex.withLock { block() }

    /**
     * Optimistic unfavorite: hide the item instantly, then sync the removal in the background so it's
     * offline-safe (the old eager unfavorite did a network GET BEFORE the local remove — offline it
     * threw, the row was never removed, and the error was swallowed: a silent-fail bug).
     *
     *  - Pending-ADD placeholder (`pending && attachmentId empty`): never reached the server, so just
     *    drop it locally (no CAS). A mid-flight background resolve's confirmPlaceholder finds it gone and
     *    issues its own compensating server unfavorite. Unchanged behavior.
     *  - Confirmed row: mark a pendingRemoval tombstone (instant hide, excluded from cap + blob, survives
     *    a pull), then defer the UNFAVORITE CAS + hard-delete to appScope. A transient/offline failure
     *    keeps the tombstone; flushPendingFavorites retries it.
     */
    suspend fun unfavorite(fileHash: String) {
        val row = syncRepo.firstByFileHash(fileHash) ?: return
        if (row.pending && row.attachmentId.isNullOrEmpty()) {
            syncRepo.removeByTempHash(fileHash)
            L.i { "[FavoriteOptimistic] unfavorite pending placeholder (local-only) tempHash=$fileHash" }
            return
        }
        // Confirmed row → tombstone now (instant hide), sync in the background.
        syncRepo.markPendingRemoval(fileHash)
        val record = with(FavoriteSyncRepository.Companion) { row.toRecord() }
        L.i { "[FavoriteOptimistic] unfavorite tombstoned, syncing fileHash=$fileHash" }
        appScope.launch {
            // Serialize the whole pull + UNFAVORITE CAS + hard-delete under casMutex so it can't overlap
            // a concurrent confirm/re-favorite for the same hash. syncUnfavorite re-derives its intent
            // from the CURRENT tombstone flag inside this lock (abort if a re-favorite cleared it).
            runCatching { casMutex.withLock { writeRepo.syncUnfavorite(record) } }
                .onFailure { L.w { "[FavoriteOptimistic] unfavorite sync deferred fileHash=$fileHash: ${it.message}" } }
        }
    }

    /**
     * Background body of an optimistic favorite (also reused to flush a placeholder pending row):
     * read the pending row, rebuild its [PendingSource], resolve/trans-store the asset (branching on
     * the source type), then hand off to [confirmOrCompensate]. On success swap the placeholder for
     * the confirmed row; on permanent reject roll it back; on transient failure leave it pending for a
     * later flush.
     */
    private suspend fun resolveAndConfirm(tempHash: String) {
        // Coalesce concurrent runs for the same placeholder (appScope task + a flush on a fast tab open).
        if (!resolvingTempHashes.add(tempHash)) {
            L.i { "[FavoriteOptimistic] resolve already in flight, skipping duplicate tempHash=$tempHash" }
            return
        }
        try {
            val row = syncRepo.firstByFileHash(tempHash) ?: return // unfavorited before resolve started
            val pointer = when (val src = row.toPendingSource()) {
                is PendingSource.Remote -> {
                    val uri = gifSendUseCase.resolveSendable(GifSendInput.FromUrl(src.previewUrl, row.width, row.height))
                    val f = File(uri.path ?: error("resolved uri has no path"))
                    assetUploader.transStore(f, row.width, row.height).also { writeRepo.seedFavoritesCache(f, it.id, it.key) }
                }
                is PendingSource.Message -> {
                    assetUploader.transStoreKnown(src, row.width, row.height, row.size, row.contentType) {
                        downloadMessagePlaintext(src) // ONLY on isExist miss
                    }.also { p ->
                        // Seed the confirmed ciphertext cache from local bytes if present (no re-download).
                        // Delete the decrypted temp after seeding (isTemp) so no plaintext copy lingers.
                        localPlaintextIfPresent(src)?.let { (file, isTemp) ->
                            writeRepo.seedFavoritesCache(file, p.id, p.key)
                            if (isTemp) file.delete()
                        }
                    }
                }
                PendingSource.None -> return // not a pending row (shouldn't happen)
            }
            confirmOrCompensate(tempHash, pointer)
        } catch (e: Exception) {
            // Transient failure (offline isExist/download/trans-store/pull): KEEP the placeholder
            // pending — a later favorites-tab open flushes it, so an offline favorite still syncs once
            // back online. The exception is on appScope (not Main) and caught here (never crashes).
            L.w { "[FavoriteOptimistic] favorite deferred (kept pending) tempHash=$tempHash: ${e.stackTraceToString()}" }
        } finally {
            resolvingTempHashes.remove(tempHash)
        }
    }

    /**
     * Shared confirm tail (both source types): ensure the favKey, reconcile, CAS PUT, then swap the
     * placeholder for the confirmed row. On no-key / CAS-exhaustion leave the placeholder pending; on
     * permanent reject roll it back; on a mid-flight unfavorite (placeholder already gone) issue a
     * compensating server-side unfavorite + clean the seeded ciphertext.
     *
     * The whole pull + CAS + local-commit runs under [casMutex] so it can't overlap a concurrent
     * background CAS cycle (syncUnfavorite / deferred re-favorite) for any hash. [compensateUnfavorite]
     * is called from INSIDE this lock — it must NOT re-acquire casMutex (non-reentrant).
     */
    private suspend fun confirmOrCompensate(tempHash: String, pointer: FavoriteAttachmentPointer) = casMutex.withLock {
        val firstCreate = keyLifecycle.ensureFavKey()
        if (!keyRepo.hasKey()) {
            L.i { "[FavoriteOptimistic] kept pending (no favKey) tempHash=$tempHash" }
            return@withLock
        }
        // Pull the current server list + version right before the CAS (see FavoriteWriteRepository).
        if (!firstCreate) syncRepo.pullAndDecrypt()
        try {
            val newVersion = writeRepo.putWithCas(
                FavoriteAction.FAVORITE,
                FavoriteRecord(pointer, syncRepo.cachedListVersion),
                wrappedFavKey = keyLifecycle.wrappedFavKeyForPut(firstCreate)
            )
            // Swap: delete placeholder + upsert the confirmed real-fileHash row atomically. The
            // server-arbitrated newVersion is the highest so the row stays on top. confirmPlaceholder
            // also clears any pendingRemoval on the bare-hash row it confirms, so a re-favorite that
            // landed a tombstone mid-flight can't hide the freshly-confirmed row.
            val confirmed = FavoriteRecord(pointer, newVersion).toModel().apply { pending = false }
            if (!syncRepo.confirmPlaceholder(tempHash, confirmed)) {
                // The user unfavorited the placeholder while this background write was in flight, so the
                // swap was skipped. The server FAVORITE PUT already landed, so issue a compensating
                // server-side unfavorite (else the gif reappears on the next pull / on other devices).
                L.i { "[FavoriteOptimistic] confirm skipped (unfavorited mid-flight), compensating tempHash=$tempHash fileHash=${pointer.fileHash}" }
                // The seeded ciphertext (keyed by pointer.id) now has no cache row referencing it, so it
                // would leak on disk — delete it. Best-effort.
                runCatching { FavoriteEncryptedAttachmentProvider.encryptedFile(pointer.id).delete() }
                compensateUnfavorite(pointer)
            }
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteOptimistic] permanently rejected, rolled back tempHash=$tempHash: ${e.message}" }
            syncRepo.removeByTempHash(tempHash)
        } catch (e: CasExhaustedException) {
            // Transient: leave the placeholder pending; flushPendingFavorites retries it.
            L.w { "[FavoriteOptimistic] CAS exhausted, kept placeholder tempHash=$tempHash" }
        }
    }

    /**
     * Server-side-only unfavorite of a just-confirmed pointer, used when the local placeholder was
     * removed (user unfavorited it) while the optimistic FAVORITE PUT was in flight: the server now
     * holds the item but the local list must not. Best-effort — a transient failure leaves the item on
     * the server, and the next favorites-tab pull surfaces it.
     */
    private suspend fun compensateUnfavorite(pointer: FavoriteAttachmentPointer) {
        if (!keyRepo.hasKey()) return
        // Re-pull so the CAS carries a fresh listVersion (a stale one is rejected "invalid param").
        syncRepo.pullAndDecrypt()
        try {
            writeRepo.putWithCas(FavoriteAction.UNFAVORITE, FavoriteRecord(pointer, syncRepo.cachedListVersion), wrappedFavKey = null)
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteOptimistic] compensating unfavorite permanently rejected fileHash=${pointer.fileHash}: ${e.message}" }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteOptimistic] compensating unfavorite CAS exhausted fileHash=${pointer.fileHash}" }
        }
    }

    /**
     * Produce a DISPOSABLE plaintext temp File for a message gif on an isExist MISS (server GC'd the
     * account copy). The returned file is ALWAYS a temp the caller ([transStoreKnown]) may delete —
     * never the message's own on-disk plaintext (which must not be deleted). Reuses local bytes when
     * present (copy plaintext / decrypt `.encrypt`, no network); else downloads the message ciphertext
     * via the shared fetcher, then decrypts with the message key. Runs on IO.
     */
    private suspend fun downloadMessagePlaintext(ref: PendingSource.Message): File = withContext(Dispatchers.IO) {
        val basePath = pendingMessageBasePath(ref.messageId, ref.fileName)
        val srcDir = File(application.cacheDir, "gif_fav_src").apply { mkdirs() }
        // Attachment-supplied fileName is attacker-influenceable metadata; reduce it to a bare basename
        // before using it in any NEW temp path under srcDir, so a crafted name with path separators
        // (e.g. "../../x") can't redirect the write outside srcDir. (The read basePath above mirrors the
        // app-wide getMessageAttachmentFilePath()+fileName convention and stays as-is.)
        val safeName = File(ref.fileName).name.takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: ref.accountFileHash.hashCode().toString()
        // 1. Already on disk (plaintext or .encrypt)? resolveMessageGifPlaintext returns the message
        //    plaintext file (isTemp=false) or a decrypted temp (isTemp=true). Copy the non-temp case
        //    into our own temp so the caller can freely delete the returned file (never the original).
        resolveMessageGifPlaintext(application, basePath, ref.key)?.let { (file, isTemp) ->
            if (isTemp) return@withContext file
            val copy = File(srcDir, "${System.currentTimeMillis()}_$safeName")
            file.copyTo(copy, overwrite = true)
            return@withContext copy
        }
        // 2. Not on disk → download the message ciphertext into a temp .encrypt (keyed by the account
        //    fileHash + message authorizeId), then decrypt it with the message key to a plaintext temp.
        val encTemp = File(srcDir, "${System.currentTimeMillis()}_${ref.accountFileHash.hashCode()}.encrypt")
        try {
            if (!ciphertextFetcher.downloadCiphertextTo(ref.authorizeId, ref.accountFileHash, encTemp)) {
                throw java.io.IOException("message ciphertext download failed messageId=${ref.messageId}")
            }
            val plainTemp = File(srcDir, "${System.currentTimeMillis()}_$safeName")
            com.difft.android.chat.util.FileDecryptionUtil.decryptFile(encTemp, plainTemp, ref.key) // verifyMacFirst=true
            plainTemp
        } finally {
            encTemp.delete()
        }
    }

    /**
     * The local plaintext File for a message gif if it (or its `.encrypt`) is already on disk, else
     * null. Used to seed the confirmed favorites ciphertext cache without a re-download after a hit.
     * Returns the `isTemp` flag alongside the file: when the message is stored ciphertext-at-rest the
     * file is a freshly-decrypted temp that the caller MUST delete after seeding (else a plaintext gif
     * copy lingers in gif_fav_src until the stale-prune fires); the plaintext-at-rest case returns the
     * message's own file (isTemp=false) which must NOT be deleted.
     */
    private suspend fun localPlaintextIfPresent(ref: PendingSource.Message): Pair<File, Boolean>? = withContext(Dispatchers.IO) {
        val basePath = pendingMessageBasePath(ref.messageId, ref.fileName)
        if (!EncryptedAttachmentAccess.isReadable(basePath)) return@withContext null
        resolveMessageGifPlaintext(application, basePath, ref.key)
    }
}
