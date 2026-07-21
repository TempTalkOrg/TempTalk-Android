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

/**
 * favKey lifecycle (v2 unwrap-first key model), split out of [FavoriteWriteRepository] to keep both
 * under the 500-line limit (SRP: key derivation vs CAS write vs optimistic enqueue).
 *
 * The favKey is a stable random DEK wrapped under KEK=HKDF(aci identity private key) and stored
 * server-side as wrappedFavKey. Any device holding the account identity derives the same KEK and
 * unwraps favKey — no per-device distribution.
 *
 * This class owns the PURE key/crypto surface only (no server PUT): establishing / deriving / wrapping
 * the favKey. Identity-driven orchestration that also PUTs (reset / rewrap / primary-login decision)
 * stays in [FavoriteWriteRepository] and delegates its key bits here — keeping the dependency one-way
 * (writeRepo → keyLifecycle) with no Hilt cycle.
 */
@Singleton
class FavoriteKeyLifecycle @Inject constructor(
    private val syncRepo: FavoriteSyncRepository,
    private val keyRepo: FavoriteKeyRepo,
    private val encryptionDataManager: EncryptionDataManager,
    // dagger.Lazy breaks the keyLifecycle <-> writeRepo cycle: writeRepo injects keyLifecycle directly
    // for key derivation; keyLifecycle reaches back (Lazy) only for the reset/rewrap PUT primitives.
    private val writeRepo: dagger.Lazy<FavoriteWriteRepository>,
) {
    /**
     * Ensure a usable favKey is cached locally (unwrap-first, cross-platform §3.4):
     *  1. local cache present -> done.
     *  2. else GET wrappedFavKey and unwrap with the current KEK (HKDF of the aci identity) -> cache.
     *  3. else no wrappedFavKey on the server (never created) -> first-create: generate favKey,
     *     wrap it, cache; the wrapped key rides the first favorite PUT (returns true).
     *
     * A wrappedFavKey that the current KEK cannot unwrap means the account identity changed
     * (rotation → needs rewrap; re-registration → needs reset). Those are driven by the identity
     * layer (see [FavoriteWriteRepository.rewrapOnMasterKeyRotation] / .resetFavorites), NOT
     * auto-decided here, so this just leaves no local key and the caller keeps the add pending.
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
                L.i { "[FavoriteKey] favKey unwrapped from server" }
            } else {
                // Current KEK can't unwrap — identity changed. rewrap/reset is identity-layer driven.
                L.w { "[FavoriteKey] wrappedFavKey present but current KEK cannot unwrap (identity changed)" }
            }
            return false
        }

        // No wrapped key on the server: first-create.
        firstCreateKey()
        return true
    }

    /** Generate a fresh favKey, wrap it under the current KEK, and cache it locally. */
    suspend fun firstCreateKey() = withContext(Dispatchers.IO) {
        val favKey = FavoriteCrypto.generateFavKey()
        keyRepo.save(FavoriteCrypto.keyId(favKey), favKey)
    }

    /** Derive the KEK from the current account identity private key, or null if unavailable. */
    fun deriveCurrentKek(): ByteArray? = try {
        val priv = encryptionDataManager.getAciIdentityKey().privateKey.serialize()
        FavoriteCrypto.deriveKek(priv)
    } catch (e: Exception) {
        L.w { "[FavoriteKey] cannot derive KEK: ${e.message}" }
        null
    }

    /** Wrap the currently-cached favKey under the current KEK, for a PUT that carries wrappedFavKey. */
    suspend fun currentWrappedFavKey(): String? = withContext(Dispatchers.IO) {
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
    suspend fun wrappedFavKeyForPut(firstCreate: Boolean = false): String? =
        if (firstCreate || syncRepo.serverKeyId.isNullOrEmpty()) currentWrappedFavKey() else null

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

    /**
     * Re-wrap the favKey under the NEW identity KEK and PUT action=rewrap (wrappedFavKey column only,
     * no listVersion, no CAS). The list itself (blob) is preserved.
     *
     * The favKey is sourced in priority order:
     *  1. the locally-cached favKey — the rotating primary almost always has it, and using it also
     *     HEALS a server wrappedFavKey left stale under an even OLDER identity;
     *  2. otherwise unwrap the server wrappedFavKey with [oldPriv]'s KEK.
     * No-op if the server has no wrappedFavKey, or the favKey is unavailable both ways.
     */
    suspend fun rewrapOnMasterKeyRotation(oldPriv: ByteArray?, newPriv: ByteArray) {
        val data = syncRepo.getFavorites()
        if (data != null) syncRepo.applyServerMeta(data)
        val wrapped = data?.wrappedFavKey
        if (wrapped.isNullOrEmpty()) {
            L.i { "[FavoriteKey] rewrap skipped: no wrappedFavKey on server" }
            return
        }
        val rewrapped = withContext(Dispatchers.IO) {
            val favKey = keyRepo.getFavKey()?.favKey
                ?: oldPriv?.let { FavoriteCrypto.unwrapFavKey(FavoriteCrypto.deriveKek(it), wrapped) }
            if (favKey == null) {
                L.w { "[FavoriteKey] rewrap: no cached favKey and old KEK cannot unwrap, abort" }
                return@withContext null
            }
            // Cache the favKey locally so subsequent ops don't re-derive it.
            keyRepo.save(FavoriteCrypto.keyId(favKey), favKey)
            FavoriteCrypto.wrapFavKey(FavoriteCrypto.deriveKek(newPriv), favKey)
        } ?: return
        try {
            writeRepo.get().putRewrap(rewrapped)
            L.i { "[FavoriteKey] rewrap done" }
        } catch (e: FavoritePermanentRejectException) {
            L.w { "[FavoriteKey] rewrap permanently rejected: ${e.message}" }
        } catch (e: CasExhaustedException) {
            L.w { "[FavoriteKey] rewrap CAS exhausted (unexpected — rewrap has no CAS)" }
        }
    }

    /**
     * Primary-device login decision (design §3.4). The login flow just generated a FRESH aci identity
     * and overwrote the previous one, so decide what happens to the favKey:
     *  - Server has no wrappedFavKey -> fresh account, nothing to do.
     *  - The new identity KEK already unwraps it -> nothing to do (identity effectively unchanged).
     *  - The favKey is still RECOVERABLE (locally cached, or the old identity can unwrap the server
     *    key) -> rewrap under the new identity so the list is PRESERVED.
     *  - Otherwise (favKey unrecoverable) -> reset: start a fresh favKey (server unpins + GCs the old).
     *
     * Best-effort; callers wrap this so a failure never blocks login.
     *
     * @param oldPriv previous aci identity private key captured BEFORE login overwrote it, or null.
     * @param newPriv the freshly generated aci identity private key (current identity).
     */
    suspend fun onPrimaryLogin(oldPriv: ByteArray?, newPriv: ByteArray) {
        val data = syncRepo.getFavorites()
        if (data != null) syncRepo.applyServerMeta(data)
        val wrapped = data?.wrappedFavKey
        if (wrapped.isNullOrEmpty()) {
            L.i { "[FavoriteKey] primary login: server has no favorites, nothing to do" }
            return
        }
        val decision = withContext(Dispatchers.IO) {
            // Already unwrappable under the new identity (rare — e.g. re-login kept the same key).
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
